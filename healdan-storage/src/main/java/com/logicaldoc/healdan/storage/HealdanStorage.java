// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.store.AbstractStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.crypto.FileCipher;
import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.healdan.util.StorageUnreachableException;
import com.logicaldoc.util.spring.Context;

/**
 * Healdan's {@code Store}, the one bean CE's {@code <bean id="store" class="${store}"/>} makes, so CE's own document
 * code runs through it (spec section 6). Each file's row in {@code hd_storage_file} says where it is; each save finds
 * the document's Store and that Store's backend.
 * <ul>
 * <li>A save into a Store on a local backend is written there, encrypted if the backend is. One into a Store on an S3
 * backend, or whose Store can't be told yet, lands in staging, plain, its row is left for the mover, and the mover is
 * woken once the save commits. Either way the row gets a new write id and the plaintext size, which CE's size check
 * ({@code WRITECHECK}) reads. A backend that is failing refuses the save, and one not yet checked since the start is
 * checked first ({@link BackendHealth}).</li>
 * <li>A save that replaces a copy on another backend, and every delete, holds the old copy (spec section 8): a local
 * one moves under its backend's {@code .held/}, an S3 one stays where it is, and a row in {@code hd_storage_held}
 * keeps it until its purge date.</li>
 * <li>Reads go to the row's place. {@code exists}, {@code size} and {@code listResources} answer from the rows, never
 * a backend, and a file version is matched exactly ({@code 1.2} is not {@code 1.20}). While the row's backend is a
 * known outage ({@link BackendHealth#knownOutage}), a read the read cache can't serve fails at once. A file with no row
 * is looked for in Original's folder, where only CE's own store could have put it (call 11), and listed from there
 * too.</li>
 * </ul>
 * Saves and deletes hold the file's lock ({@link FileLocks}) while they write or delete its bytes and its row. It is not
 * a Spring component: only CE's bean definition makes it, and {@link #init()} reads no table, as Spring calls it before
 * Flyway has run.
 */
public class HealdanStorage extends AbstractStore {

	private static final Logger log = LoggerFactory.getLogger(HealdanStorage.class);

	/** A ranged read goes through a buffer this big: an S3 backend's channel makes one request for each read */
	static final int RANGE_BUFFER = 1 << 20;

	/** How many files read with no row are remembered as warned of */
	private static final int WARNED_LIMIT = 4096;

	/** The files read with no row that a warning has named, the oldest forgotten first */
	private static final Set<String> WARNED = Collections.synchronizedSet(Collections.newSetFromMap(
			new LinkedHashMap<String, Boolean>(64, 0.75f) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
					return size() > WARNED_LIMIT;
				}
			}));

	public HealdanStorage() {
		super();
	}

	/**
	 * @return where a document's resource is kept on every backend, in CE's layout: {@code 123/45/doc/1.2}
	 */
	public String keyOf(long docId, String resource) {
		return computeRelativePath(docId, resource);
	}

	private static StoredFiles files() {
		return StoredFiles.get();
	}

	private static Backends backends() {
		return Backends.get();
	}

	/**
	 * Reads nothing, as Spring calls it before Flyway has made Healdan's tables, and CE's Stores screen calls it again
	 * on Save. Backends are built when a file first needs one.
	 */
	@Override
	public void init() {
		log.debug("Healdan's storage is CE's store");
	}

	@Override
	public void destroy() {
		// The backends are Backends' to close
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	// Saves

	/**
	 * Refuses an empty file first, as {@code FSStore} does: CE's {@code replaceFile} and {@code checkin} rely on it, and
	 * nothing else stops a 0-byte replace from wiping a version.
	 */
	@Override
	public void store(File file, StoreResource resource) throws IOException {
		checkEnabled();
		checkNotEmpty(file);
		write(file.toPath(), resource);
		checkWriteAfterStore(resource, file.length());
	}

	/** Like {@code FSStore}'s, this takes an empty stream, and closes the stream */
	@Override
	public void store(InputStream stream, StoreResource resource) throws IOException {
		Path spool = null;
		try (InputStream in = stream) {
			checkEnabled();
			spool = Files.createTempFile("healdan-storage", ".tmp");
			Files.copy(in, spool, StandardCopyOption.REPLACE_EXISTING);
			write(spool, resource);
			checkWriteAfterStore(resource, Files.size(spool));
		} finally {
			if (spool != null)
				Files.deleteIfExists(spool);
		}
	}

	/**
	 * Writes a file where its Store keeps it, records it, and then holds the copy it replaced on another backend.
	 *
	 * @param plain the bytes, plain
	 */
	private void write(Path plain, StoreResource resource) throws IOException {
		long docId = resource.getDocId();
		String name = resource.name();
		String key = keyOf(docId, name);
		long size = Files.size(plain);
		// Got first, as one in the wrong folder is refused before anything is written, and outside the file's lock, as
		// the first one reads the cache's whole folder
		ReadCache cache = ReadCache.get();
		long target;
		try (FileLocks.Held held = FileLocks.lock(docId, name)) {
			FileLocks.writtenInTransaction(docId, name);
			Long storeId = StoreOf.get().of(docId).orElse(null);
			long storeBackend = files().storeBackend(storeId);
			target = target(storeId, storeBackend);
			BackendHealth.get().beforeWrite(target);
			Optional<StoredFile> before = files().find(docId, name);
			// A file with no row can only be in Original's folder (call 11), and this save replaces it
			boolean unrecorded = before.isEmpty() && target != StorageBackend.ORIGINAL
					&& Files.isRegularFile(original(key));
			try {
				put(target, key, plain, docId, name);
			} catch (BackendFailingException e) {
				// The backend's own check refused it, as when its folder was unmounted after its last check passed
				BackendHealth.get().reportFailure(target, e);
				throw e;
			}
			files().recordWrite(docId, name, target, storeId, size, before.isPresent(), target == storeBackend);
			cache.delete(docId, name);
			if (before.isPresent() && before.get().backendId() != target)
				holdReplaced(before.get().backendId(), key, docId, name);
			else if (unrecorded)
				holdReplaced(StorageBackend.ORIGINAL, key, docId, name);
		}
		if (target == Staging.ID)
			Mover.get().wake();
	}

	/**
	 * @param storeId a document's Store; null if it can't be told yet
	 * @param storeBackend the backend the Store uses ({@link StoredFiles#storeBackend})
	 * @return where a save into it lands: its backend if that is local (Original for a Store with none), staging if S3
	 *         or no Store
	 */
	private static long target(Long storeId, long storeBackend) throws IOException {
		if (storeId == null)
			return Staging.ID;
		return backends().isS3(storeBackend) ? Staging.ID : storeBackend;
	}

	/**
	 * Writes the file on a local backend or staging, encrypting it on its way into an encrypted backend: straight into
	 * the backend's temporary file, so the file is written once
	 */
	private static void put(long backendId, String key, Path plain, long docId, String name) throws IOException {
		Backends.Entry entry = backends().entry(backendId);
		if (entry.s3())
			throw new IOException("A save is never written to S3 backend " + backendId + " itself, only staged");
		if (!entry.encrypted()) {
			entry.backend().put(key, plain, null);
			return;
		}
		String associated = FileCipher.associated(docId, name);
		if (entry.backend() instanceof LocalBackend folder) {
			folder.put(key, out -> entry.cipher().encrypt(plain, out, associated));
			return;
		}
		// A backend a test gave in place of a folder
		Path cipher = Files.createTempFile("healdan-storage", ".enc");
		try {
			entry.cipher().encrypt(plain, cipher, associated);
			entry.backend().put(key, cipher, null);
		} finally {
			Files.deleteIfExists(cipher);
		}
	}

	/**
	 * Holds the copy a save replaced on another backend (spec section 6.2). The save has landed and its row is
	 * written, so a copy that can't be held is left where it was, which loses nothing, and logged.
	 */
	private void holdReplaced(long backendId, String key, long docId, String name) {
		try {
			if (!Held.hold(backendId, key, docId, name))
				log.warn("The copy of document {}'s {} that a save replaced stays on storage backend {}, not held",
						docId, name, backendId);
		} catch (RuntimeException e) {
			// The save stands: reporting it as failed would leave CE out of step with the row just written. The hold's
			// statements ran in a savepoint (HeldCopies.hold), so the caller's transaction is still usable
			log.error("The save of document {}'s {} stands, but the copy it replaced on storage backend {} couldn't be "
					+ "recorded as held ({}): it is held with no row in hd_storage_held, under the backend's .held/ or, "
					+ "on S3, where it was, and no purge will delete it", docId, name, backendId, e.getMessage(), e);
		}
	}

	// Deletes

	@Override
	public void delete(StoreResource resource) {
		long docId = resource.getDocId();
		String name = resource.name();
		if (deleteFile(docId, name))
			logDeletion(docId, keyOf(docId, name));
	}

	/**
	 * Deletes every file of a document, from its rows, which needs nothing else of it: CE's {@code destroyDocument}
	 * deletes the document's row first, and calls this twice.
	 */
	@Override
	public void delete(long docId) {
		Set<String> recorded = new HashSet<>();
		for (StoredFile row : files().list(docId)) {
			recorded.add(row.resource());
			if (deleteFile(docId, row.resource()))
				logDeletion(docId, keyOf(docId, row.resource()));
		}
		for (String name : unrecorded(docId, recorded))
			if (deleteFile(docId, name))
				logDeletion(docId, keyOf(docId, name));
	}

	/**
	 * Holds a file's copy and drops its row, holding the file's lock. A file with no row is held in Original's folder,
	 * if it is there (call 11).
	 *
	 * @return whether anything was deleted; false if there was nothing, or the copy couldn't be held, which leaves the
	 *         file and its row as they were
	 */
	private boolean deleteFile(long docId, String name) {
		String key = keyOf(docId, name);
		// Got first, as one in the wrong folder is refused before anything is held, and outside the file's lock, as the
		// first one reads the cache's whole folder
		ReadCache cache = ReadCache.get();
		try (FileLocks.Held held = FileLocks.lock(docId, name)) {
			FileLocks.writtenInTransaction(docId, name);
			Optional<StoredFile> row = files().find(docId, name);
			if (row.isPresent()) {
				if (!Held.hold(row.get().backendId(), key, docId, name))
					return false;
				files().delete(docId, name);
				cache.delete(docId, name);
				return true;
			}
			if (!Files.isRegularFile(original(key)))
				return false;
			log.warn("Document {}'s {} has no storage row; it is deleted from Original's folder", docId, name);
			return Held.hold(StorageBackend.ORIGINAL, key, docId, name);
		}
	}

	/**
	 * @param recorded the names of the document's rows
	 * @return the names of the document's files in Original's folder that have no row (call 11)
	 */
	private List<String> unrecorded(long docId, Set<String> recorded) {
		Path folder = Staging.original().resolve(computeRelativePath(docId));
		if (!Files.isDirectory(folder))
			return List.of();
		try (Stream<Path> list = Files.list(folder)) {
			return list.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
					.filter(n -> !n.startsWith(".") && !n.contains(LocalBackend.TEMPORARY))
					.filter(n -> !recorded.contains(n)).sorted().toList();
		} catch (IOException e) {
			log.warn("Original's folder of document {} can't be listed: {}", docId, e.getMessage());
			return List.of();
		}
	}

	// Reads

	/** @return a file of Original's folder, by its key */
	private static Path original(String key) {
		return Staging.original().resolve(key);
	}

	/**
	 * @param read whether it is to be read, which is warned of once for each file; {@code exists} and {@code size},
	 *        which a page of thumbnails asks of every file, log at debug
	 * @return the file in Original's folder with no row, if it is there (call 11)
	 */
	private Optional<Path> unrecorded(long docId, String name, boolean read) {
		Path file = original(keyOf(docId, name));
		if (!Files.isRegularFile(file))
			return Optional.empty();
		if (read && WARNED.add(file.toString()))
			log.warn("Document {}'s {} has no storage row; it is read from Original's folder", docId, name);
		else
			log.debug("Document {}'s {} has no storage row; it is in Original's folder", docId, name);
		return Optional.of(file);
	}

	@Override
	public boolean exists(StoreResource resource) {
		long docId = resource.getDocId();
		String name = resource.name();
		return files().find(docId, name).isPresent() || unrecorded(docId, name, false).isPresent();
	}

	/** @return the plaintext size, from the row; 0 for a file that isn't there, as {@code FSStore}'s */
	@Override
	public long size(StoreResource resource) {
		long docId = resource.getDocId();
		String name = resource.name();
		Optional<StoredFile> row = files().find(docId, name);
		if (row.isPresent())
			return row.get().size();
		return unrecorded(docId, name, false).map(file -> file.toFile().length()).orElse(0L);
	}

	/**
	 * From the rows, and the files of Original's folder that have none (call 11): CE's {@code replaceFile},
	 * {@code deleteVersion} and M1.5's placeholder sweep delete what this lists, so a stale thumbnail or conversion
	 * only CE's own store wrote isn't left to be read as current. A file version is matched exactly.
	 */
	@Override
	public List<StoreResource> listResources(long docId, String fileVersion) {
		List<StoreResource> resources = new ArrayList<>();
		Set<String> recorded = new HashSet<>();
		for (StoredFile row : files().list(docId)) {
			recorded.add(row.resource());
			if (ofVersion(row.resource(), fileVersion))
				resources.add(StoreResource.builder().docId(docId).name(row.resource()).build());
		}
		for (String name : unrecorded(docId, recorded))
			if (ofVersion(name, fileVersion))
				resource(docId, name).ifPresent(resources::add);
		return resources;
	}

	private static boolean ofVersion(String name, String fileVersion) {
		return StringUtils.isEmpty(fileVersion) || name.equals(fileVersion) || name.startsWith(fileVersion + "-");
	}

	/** @return the resource of a file's name, if CE's resource names can name it */
	private static Optional<StoreResource> resource(long docId, String name) {
		try {
			StoreResource resource = StoreResource.builder().docId(docId).name(name).build();
			return resource.name().equals(name) ? Optional.of(resource) : Optional.empty();
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	@Override
	public InputStream getStream(StoreResource resource) throws IOException {
		return new BufferedInputStream(read(resource.getDocId(), resource.name()));
	}

	/** @return the file's plain bytes, as {@link #open(long, String, boolean)} reads them */
	private InputStream read(long docId, String name) throws IOException {
		return open(docId, name, false);
	}

	/** @return the file's plain bytes as a channel, which reads a range without reading what is before it */
	private SeekableByteChannel channel(long docId, String name) throws IOException {
		return open(docId, name, true);
	}

	/**
	 * @param ranged whether a channel is wanted, else a stream
	 * @return the file's plain bytes, from its row's place; a file the mover took elsewhere while it was looked up is
	 *         read from where it went. A backend that can't be reached, at either place, is a
	 *         {@link StorageUnreachableException} naming the Store.
	 */
	private <T> T open(long docId, String name, boolean ranged) throws IOException {
		String key = keyOf(docId, name);
		Optional<StoredFile> row = files().find(docId, name);
		if (row.isEmpty())
			return openEntry(unrecorded(docId, name, true).orElseThrow(() -> new FileNotFoundException(key)), ranged);
		StoredFile current = row.get();
		try {
			try {
				return outageAware(current, key, ranged);
			} catch (FileNotFoundException e) {
				Optional<StoredFile> now = files().find(docId, name);
				if (now.isEmpty() || !moved(current, now.get()))
					throw e;
				current = now.get();
				// The retry is inside the same catch, so its outage is converted too
				return outageAware(current, key, ranged);
			}
		} catch (BackendUnreachableException e) {
			throw unreachable(current, e);
		} catch (BackendFailingException e) {
			// Refused, as by a bucket that is gone or a key revoked: the backend's check tells its failure from the file's
			BackendHealth.get().reportFailure(current.backendId(), e);
			throw e;
		}
	}

	/**
	 * @return the row's file, opened; an S3 file's stream or channel also converts the outage its own reads meet, so
	 *         that none escapes as a {@link BackendUnreachableException}. The kind is asked before the file is opened,
	 *         so a failure there can't leak an opened stream.
	 */
	@SuppressWarnings("unchecked")
	private static <T> T outageAware(StoredFile row, String key, boolean ranged) throws IOException {
		boolean s3 = backends().isS3(row.backendId());
		T opened = open(row, key, ranged);
		if (!s3)
			return opened;
		return (T) (ranged ? UnreachableReads.wrap((SeekableByteChannel) opened, e -> unreachable(row, e))
				: UnreachableReads.wrap((InputStream) opened, e -> unreachable(row, e)));
	}

	/**
	 * @return the exception for a file whose backend can't be reached, naming its Store (spec section 6.5), and calling
	 *         the storage cloud storage only if the backend is S3's
	 */
	private static StorageUnreachableException unreachable(StoredFile row, BackendUnreachableException cause) {
		String store = null;
		if (row.storeId() != null)
			try {
				Folder folder = FolderDAO.get().findById(row.storeId());
				store = folder == null ? null : folder.getName();
			} catch (PersistenceException e) {
				log.debug("The name of Store {} can't be read: {}", row.storeId(), e.getMessage());
			}
		if (cause instanceof BackendHealth.KnownOutageException)
			// The outage was warned of once, by BackendHealth
			log.debug("Document {}'s {} can't be read: storage backend {} is a known outage", row.docId(),
					row.resource(), row.backendId());
		else
			log.warn("Document {}'s {} can't be read: storage backend {} is unreachable", row.docId(), row.resource(),
					row.backendId());
		// So that an outage met by reads, while nothing moves, shows as one and the alert sees it; a refusal for a
		// known outage reports nothing, as it met nothing
		BackendHealth.get().reportFailure(row.backendId(), cause);
		StorageUnreachableException outage = new StorageUnreachableException(store, cloud(row.backendId()), cause);
		ReadOutages.note(outage);
		return outage;
	}

	/**
	 * @return how long an outage BackendHealth found stays known: {@code storage.retestSeconds}, after which a retest
	 *         finds it again; the default if the setting can't be read, as in a database outage
	 */
	private static Duration knownFor() {
		try {
			return Duration.ofSeconds(StorageSettings.get().retestSeconds());
		} catch (RuntimeException e) {
			return Duration.ofSeconds(StorageSettings.DEFAULT_RETEST_SECONDS);
		}
	}

	/** @return whether the backend is S3's; false if that can't be told, as "storage" is true of either kind */
	private static boolean cloud(long backendId) {
		try {
			return backends().isS3(backendId);
		} catch (BackendFailingException e) {
			return false;
		}
	}

	private static boolean moved(StoredFile before, StoredFile now) {
		return before.backendId() != now.backendId() || before.write() != now.write();
	}

	/**
	 * Opens a row's file. S3's goes through the read cache, which holds plain bytes and is looked in before the backend
	 * is built, so a cached file opens while its backend can't be built (spec section 6.5); S3's other files (over a
	 * tenth of the cache) are read whole or, as a channel, by a ranged request for each read. A local file missing from
	 * its place is read from .held/. An encrypted file is decrypted as it is read, a channel only in the segments it
	 * covers.
	 *
	 * @param ranged whether a channel is wanted, else a stream
	 */
	@SuppressWarnings("unchecked")
	private static <T> T open(StoredFile row, String key, boolean ranged) throws IOException {
		if (backends().isS3(row.backendId())) {
			Optional<T> hit = hit(row, ranged);
			if (hit.isPresent())
				return hit.get();
		}
		// Before the backend is built or asked: a read during a known outage would only wait out the client's attempts
		BackendHealth health = BackendHealth.get();
		if (health.status(row.backendId()).unreachable())
			health.refuseKnownOutage(row.backendId(), knownFor());
		Backends.Entry entry = backends().entry(row.backendId());
		if (entry.s3()) {
			Optional<T> cached = cached(entry, row, key, ranged);
			if (cached.isPresent())
				return cached.get();
		}
		Closeable raw;
		try {
			raw = ranged ? entry.backend().open(key) : entry.backend().get(key);
		} catch (FileNotFoundException e) {
			// A restored database finds a file deleted after its backup in .held/ (spec section 6.3)
			if (!(entry.backend() instanceof LocalBackend folder) || folder.held(key).isEmpty())
				throw e;
			log.warn("Document {}'s {} is not in its place on storage backend {}; it is read from .held/",
					row.docId(), row.resource(), row.backendId());
			raw = ranged ? folder.openHeld(key) : folder.getHeld(key);
		}
		if (!entry.encrypted())
			return (T) raw;
		String associated = FileCipher.associated(row.docId(), row.resource());
		try {
			return (T) (ranged ? entry.cipher().decrypting((SeekableByteChannel) raw, associated)
					: entry.cipher().decrypting((InputStream) raw, associated));
		} catch (IOException | RuntimeException e) {
			raw.close();
			throw e;
		}
	}

	/**
	 * @param ranged whether a channel is wanted, else a stream
	 * @return the file's entry in the read cache, opened, if it is there; nothing is built or fetched
	 */
	private static <T> Optional<T> hit(StoredFile row, boolean ranged) throws IOException {
		Optional<Path> path = ReadCache.get().cached(row.backendId(), row.docId(), row.resource(), row.write());
		if (path.isEmpty())
			return Optional.empty();
		try {
			return Optional.of(openEntry(path.get(), ranged));
		} catch (NoSuchFileException e) {
			// Dropped to make room between the lookup and the open: the backend is built and it is fetched again
			return Optional.empty();
		}
	}

	/**
	 * @param ranged whether a channel is wanted, else a stream
	 * @return the file's entry in the read cache, opened (fetched first if it isn't there); empty if the file is too
	 *         big to be cached, whose ranges are read from the bucket
	 */
	private static <T> Optional<T> cached(Backends.Entry entry, StoredFile row, String key, boolean ranged)
			throws IOException {
		ReadCache cache = ReadCache.get();
		for (int attempt = 0;; attempt++) {
			Optional<Path> path = cache.read(entry.backend(), entry.cipher(), row.backendId(), row.docId(),
					row.resource(), row.write(), row.size(), key);
			if (path.isEmpty())
				return Optional.empty();
			try {
				return Optional.of(openEntry(path.get(), ranged));
			} catch (NoSuchFileException e) {
				// Dropped to make room between the lookup and the open: fetch it again
				if (attempt > 0)
					throw e;
			}
		}
	}

	/** @return a file opened as a channel if ranged, else as a stream */
	@SuppressWarnings("unchecked")
	private static <T> T openEntry(Path path, boolean ranged) throws IOException {
		return (T) (ranged ? FileChannel.open(path, StandardOpenOption.READ) : Files.newInputStream(path));
	}

	/**
	 * Copies a range of the file's plain bytes, as {@code IOUtils.copyLarge} does: {@code length} bytes from
	 * {@code start}, or fewer at the end of the file; a negative length copies to the end.
	 */
	private void copyRange(StoreResource resource, OutputStream output, long start, long length) throws IOException {
		if (start < 0)
			throw new IOException("A range can't start at " + start);
		try (SeekableByteChannel channel = channel(resource.getDocId(), resource.name())) {
			channel.position(start);
			ByteBuffer buffer = ByteBuffer.allocate(RANGE_BUFFER);
			long remaining = length < 0 ? Long.MAX_VALUE : length;
			while (remaining > 0) {
				buffer.clear();
				if (remaining < buffer.capacity())
					buffer.limit((int) remaining);
				int read = channel.read(buffer);
				if (read < 0)
					break;
				output.write(buffer.array(), 0, read);
				remaining -= read;
			}
		}
	}

	@Override
	public void writeToStream(StoreResource resource, OutputStream output, long start, long length)
			throws IOException {
		copyRange(resource, output, start, length);
	}

	@Override
	public void writeToStream(StoreResource resource, OutputStream output) throws IOException {
		try (InputStream in = read(resource.getDocId(), resource.name())) {
			in.transferTo(output);
		}
	}

	@Override
	public byte[] getBytes(StoreResource resource) throws IOException {
		try (InputStream in = read(resource.getDocId(), resource.name())) {
			return in.readAllBytes();
		}
	}

	@Override
	public byte[] getBytes(StoreResource resource, long start, long length) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		copyRange(resource, out, start, length);
		return out.toByteArray();
	}

	@Override
	public void writeToFile(StoreResource resource, File out) throws IOException {
		try (InputStream in = read(resource.getDocId(), resource.name())) {
			Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** @return the file as UTF-8 text; null if it can't be read, as {@code AbstractStore}'s */
	@Override
	public String getString(StoreResource resource) {
		try {
			return new String(getBytes(resource), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.warn("Document {}'s {} can't be read: {}", resource.getDocId(), resource.name(), e.getMessage());
			return null;
		}
	}

	// CE's store administration, which Healdan's Storage tab replaces

	/** @return the rows' plaintext sizes, summed */
	@Override
	public long getTotalSize() {
		return files().totalSize();
	}

	/** Files move between backends with the mover, never through CE */
	@Override
	public int moveResourcesToStore(long docId, int targetStoreId) {
		return 0;
	}

	/**
	 * Test (spec section 10.1) on each backend, staging's and Original's included: its marker, a test object written,
	 * read whole and by a range, listed on S3 and deleted, and its key if it is encrypted. Records no row and holds
	 * nothing.
	 *
	 * @return whether every backend passed
	 */
	@Override
	public boolean test() {
		List<Long> ids = new ArrayList<>();
		ids.add(Staging.ID);
		try {
			ids.addAll(backends().ids());
		} catch (PersistenceException e) {
			log.warn("The storage backends can't be listed: {}", e.getMessage());
			return false;
		}
		boolean ok = true;
		for (long id : ids)
			try {
				BackendTest.run(backends().entry(id), Context.get(BackendKeys.class), true);
			} catch (BackendFailingException e) {
				log.warn("Storage backend {} fails its check: {}", id, e.getMessage());
				ok = false;
			}
		return ok;
	}

	@Override
	public List<String> getParameterNames() {
		return List.of();
	}

	@Override
	public Map<String, String> getParameters() {
		return Map.of();
	}

	/** CE's other stores aren't used: there is one store, and the backends are Healdan's */
	@Override
	public Store newStore(int id) {
		return null;
	}

	@Override
	public Map<String, Store> getStoreDefinitions() {
		return Map.of();
	}
}
