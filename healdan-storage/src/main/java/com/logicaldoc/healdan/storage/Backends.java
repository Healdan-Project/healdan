// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.Closeable;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.backend.Backend;
import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.healdan.storage.backend.Endpoints;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.S3Backend;
import com.logicaldoc.healdan.storage.backend.S3Settings;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.crypto.FileCipher;
import com.logicaldoc.util.spring.Context;

import jakarta.annotation.PreDestroy;

/**
 * Each backend, built from its row with its cipher, and staging, kept once built. Nothing here runs until a file is
 * saved or read: building reads the row, the key file and, for S3, opens the sealed secret, so it can't run before
 * Flyway has.
 * <p>
 * A backend that can't be built (no row, a key file missing, a secret that won't open, settings S3 refuses, an
 * endpoint whose host can't be looked up) is a {@link BackendFailingException}, a {@link BackendUnreachableException}
 * for an outage. The failure is remembered for {@value #FAILED_BUILD_MILLIS} milliseconds, and answered as it was
 * meanwhile, so an outage of DNS doesn't make every read and hold look the host up again; {@link #forget} forgets it.
 * Each backend is built under a lock of its own, so one slow build never holds up another backend's.
 */
@Component
public class Backends {

	private static final Logger log = LoggerFactory.getLogger(Backends.class);

	/**
	 * A built backend.
	 *
	 * @param id its id; 0 for staging
	 * @param backend where its bytes go
	 * @param s3 whether it is an S3 backend, whose saves land in staging
	 * @param cipher its files' cipher; null if they're plain
	 */
	public record Entry(long id, Backend backend, boolean s3, FileCipher cipher) {

		/** @return whether its files are encrypted */
		public boolean encrypted() {
			return cipher != null;
		}
	}

	private final StorageBackendDAO dao;

	private final BackendKeys keys;

	private final StorageSettings settings;

	/** How long a failed build is remembered */
	static final long FAILED_BUILD_MILLIS = 30_000L;

	/** A build that failed, and when */
	private record Failed(BackendFailingException failure, long at) {
	}

	/** What the failures' 30 seconds are measured by, in milliseconds; tests move it */
	volatile LongSupplier clock = System::currentTimeMillis;

	private final Map<Long, Entry> built = new ConcurrentHashMap<>();

	private final Map<Long, Failed> failed = new ConcurrentHashMap<>();

	/** One lock for each backend's build */
	private final Map<Long, Object> building = new ConcurrentHashMap<>();

	/** The backends tests gave, by id, which are used in place of what the rows would build */
	private final Map<Long, Backend> given = new ConcurrentHashMap<>();

	/** Each backend's kind, from its row: true for S3 */
	private final Map<Long, Boolean> kinds = new ConcurrentHashMap<>();

	@Autowired
	public Backends(StorageBackendDAO dao, BackendKeys keys, StorageSettings settings) {
		this.dao = dao;
		this.keys = keys;
		this.settings = settings;
	}

	/**
	 * @return the bean
	 */
	public static Backends get() {
		return Context.get(Backends.class);
	}

	/**
	 * @param id a backend's id; 0 for staging
	 * @return the backend, built from its row the first time
	 * @throws BackendFailingException if it can't be built
	 */
	public Entry entry(long id) throws BackendFailingException {
		Entry entry = built.get(id);
		if (entry != null)
			return entry;
		failedRecently(id);
		synchronized (building.computeIfAbsent(id, key -> new Object())) {
			entry = built.get(id);
			if (entry != null)
				return entry;
			failedRecently(id);
			try {
				entry = build(id);
			} catch (BackendFailingException e) {
				failed.put(id, new Failed(e, clock.getAsLong()));
				throw e;
			}
			failed.remove(id);
			built.put(id, entry);
			return entry;
		}
	}

	/**
	 * @throws BackendFailingException the backend's build that failed less than {@value #FAILED_BUILD_MILLIS}
	 *         milliseconds ago, as it was: an outage stays one
	 */
	private void failedRecently(long id) throws BackendFailingException {
		Failed last = failed.get(id);
		if (last == null)
			return;
		long since = clock.getAsLong() - last.at();
		// A clock set back forgets it
		if (since < 0 || since >= FAILED_BUILD_MILLIS)
			return;
		BackendFailingException e = last.failure();
		throw e instanceof BackendUnreachableException ? new BackendUnreachableException(e.getMessage(), e.getCause())
				: new BackendFailingException(e.getMessage(), e.getCause());
	}

	/**
	 * @param id a backend's id; 0 for staging
	 * @return whether it is an S3 backend, from its row, without building it: a save into an S3 Store goes to staging
	 *         whether or not its backend can be built
	 * @throws BackendFailingException if it has no row
	 */
	public boolean isS3(long id) throws BackendFailingException {
		if (id == Staging.ID)
			return false;
		Entry entry = built.get(id);
		if (entry != null)
			return entry.s3();
		Boolean s3 = kinds.get(id);
		if (s3 == null) {
			s3 = StorageBackend.S3.equals(row(id).getKind());
			kinds.put(id, s3);
		}
		return s3;
	}

	/**
	 * Drops a backend, so the next call builds it again from its row and key file as they are then (Task 14). A backend
	 * a test gave stays given; its kind and cipher are read again.
	 *
	 * @param id the backend's id
	 */
	public void forget(long id) {
		kinds.remove(id);
		failed.remove(id);
		Entry entry = built.remove(id);
		if (entry != null && !given.containsKey(id))
			close(entry);
	}

	/**
	 * Uses a backend in place of the one its row would build, for tests: a {@code MemoryBackend} for an S3 row, say.
	 * Its kind and cipher still come from the row.
	 */
	void use(long id, Backend backend) {
		forget(id);
		given.put(id, backend);
	}

	/**
	 * @return the ids of every backend with a row, Original first
	 * @throws PersistenceException error at data layer
	 */
	public List<Long> ids() throws PersistenceException {
		List<Long> ids = new ArrayList<>();
		for (StorageBackend backend : dao.findAll())
			if (backend.getDeleted() == 0)
				ids.add(backend.getId());
		ids.sort(null);
		return ids;
	}

	private Entry build(long id) throws BackendFailingException {
		try {
			return id == Staging.ID ? new Entry(id, Staging.backend(settings.installId()), false, null) : fromRow(id);
		} catch (Endpoints.HostNotFound e) {
			// DNS may be down: an outage, which the retest recovers
			throw new BackendUnreachableException("Storage backend " + id + " can't be reached: " + e.getMessage());
		} catch (IllegalArgumentException e) {
			// A blank secret or install id, or a folder that is no path: refused, as S3 refuses bad settings
			throw new BackendFailingException("Storage backend " + id + "'s settings are refused: " + e.getMessage());
		} catch (IllegalStateException | UncheckedIOException e) {
			// No install id yet, or staging's folder can't be made or holds another install's marker
			throw new BackendFailingException(
					(id == Staging.ID ? "Staging" : "Storage backend " + id) + " can't be used: " + e.getMessage(), e);
		}
	}

	private Entry fromRow(long id) throws BackendFailingException {
		StorageBackend row = row(id);
		boolean s3 = StorageBackend.S3.equals(row.getKind());
		Backend backend = given.get(id);
		if (backend == null)
			backend = s3 ? s3(row) : local(row);
		FileCipher cipher = null;
		if (row.isEncryptedOn()) {
			Optional<byte[]> key = keys.read(id);
			if (key.isEmpty()) {
				if (!given.containsKey(id))
					close(new Entry(id, backend, s3, null));
				throw new BackendFailingException("The key file of storage backend " + id + " is missing");
			}
			cipher = new FileCipher(key.get());
		}
		return new Entry(id, backend, s3, cipher);
	}

	private StorageBackend row(long id) throws BackendFailingException {
		StorageBackend row;
		try {
			row = dao.findById(id);
		} catch (PersistenceException e) {
			throw new BackendFailingException("Storage backend " + id + " can't be read", e);
		}
		if (row == null || row.getDeleted() != 0)
			throw new BackendFailingException("There is no storage backend " + id);
		return row;
	}

	private Backend local(StorageBackend row) throws BackendFailingException {
		Path root;
		if (row.getId() == StorageBackend.ORIGINAL)
			root = Staging.original();
		else if (StringUtils.isNotBlank(row.getPath()))
			root = Path.of(row.getPath().trim());
		else
			throw new BackendFailingException("Storage backend " + row.getId() + " has no folder");
		return new LocalBackend(row.getId(), settings.installId(), root, false);
	}

	private Backend s3(StorageBackend row) throws BackendFailingException {
		S3Settings s3;
		try {
			s3 = new S3Settings(URI.create(StringUtils.trimToEmpty(row.getEndpoint())), row.getRegion(),
					row.getBucket(), row.getPrefix(), row.isPathStyleOn(), row.getAccessKey());
		} catch (IllegalArgumentException e) {
			throw new BackendFailingException("Storage backend " + row.getId() + "'s settings are refused: "
					+ e.getMessage());
		}
		// Task 12 words this for the admin: enter the secret again
		String secret = SecretBox.get().open(row.getSecret());
		if (secret == null)
			throw new BackendFailingException("The secret of storage backend " + row.getId() + " doesn't open");
		return new S3Backend(row.getId(), settings.installId(), s3, secret, false);
	}

	private static void close(Entry entry) {
		if (entry.backend() instanceof Closeable closeable)
			try {
				closeable.close();
			} catch (Exception e) {
				log.warn("Storage backend {} didn't close: {}", entry.id(), e.getMessage());
			}
	}

	@PreDestroy
	public void destroy() {
		for (Long id : List.copyOf(built.keySet()))
			forget(id);
	}
}
