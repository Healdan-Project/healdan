// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
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
import com.logicaldoc.core.store.AbstractStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.crypto.FileCipher;

/**
 * Healdan's {@code Store}, the one bean CE's {@code <bean id="store" class="${store}"/>} makes, so CE's own document
 * code runs through it (spec section 6). Each file's row in {@code hd_storage_file} says where it is; each save finds
 * the document's Store and that Store's backend.
 * <ul>
 * <li>A save into a Store on a local backend is written there, encrypted if the backend is. One into a Store on an S3
 * backend, or whose Store can't be told yet, lands in staging, plain, and its row is left for the mover. Either way the
 * row gets a new write id and the plaintext size, which CE's size check ({@code WRITECHECK}) reads.</li>
 * <li>A save that replaces a copy on another backend, and every delete, holds the old copy (spec section 8): a local
 * one moves under its backend's {@code .held/}, an S3 one stays where it is, and a row in {@code hd_storage_held}
 * keeps it until its purge date.</li>
 * <li>Reads go to the row's place. {@code exists}, {@code size} and {@code listResources} answer from the rows, never
 * a backend, and a file version is matched exactly ({@code 1.2} is not {@code 1.20}). A file with no row is looked
 * for in Original's folder, where only CE's own store could have put it (call 11), and listed from there too.</li>
 * </ul>
 * Saves and deletes hold the file's lock ({@link FileLocks}) while they write or delete its bytes and its row. It is not
 * a Spring component: only CE's bean definition makes it, and {@link #init()} reads no table, as Spring calls it before
 * Flyway has run.
 */
public class HealdanStorage extends AbstractStore {

	private static final Logger log = LoggerFactory.getLogger(HealdanStorage.class);

	/** A ranged read goes through a buffer this big: an S3 backend's channel makes one request for each read */
	static final int RANGE_BUFFER = 1 << 20;

	private static final long DAY_MS = 24L * 3600 * 1000;

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
	String keyOf(long docId, String resource) {
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
		try (FileLocks.Held held = FileLocks.lock(docId, name)) {
			Long storeId = StoreOf.get().of(docId).orElse(null);
			long target = target(storeId);
			Optional<StoredFile> before = files().find(docId, name);
			// A file with no row can only be in Original's folder (call 11), and this save replaces it
			boolean unrecorded = before.isEmpty() && target != StorageBackend.ORIGINAL
					&& Files.isRegularFile(original(key));
			put(target, key, plain, docId, name);
			files().recordWrite(docId, name, target, storeId, size, before.isPresent());
			if (before.isPresent() && before.get().backendId() != target)
				holdReplaced(before.get().backendId(), key, docId, name);
			else if (unrecorded)
				holdReplaced(StorageBackend.ORIGINAL, key, docId, name);
		}
	}

	/**
	 * @param storeId a document's Store; null if it can't be told yet
	 * @return where a save into it lands: its backend if that is local (Original for a Store with none), staging if S3
	 *         or no Store
	 */
	private static long target(Long storeId) throws IOException {
		if (storeId == null)
			return Staging.ID;
		long backendId;
		try {
			backendId = StoreBackendDAO.get().findByStore(storeId).map(StoreBackend::getBackendId)
					.orElse(StorageBackend.ORIGINAL);
		} catch (PersistenceException e) {
			throw new IOException("The backend of Store " + storeId + " can't be read", e);
		}
		return backends().isS3(backendId) ? Staging.ID : backendId;
	}

	/** Writes the file on a local backend or staging, encrypting it on its way into an encrypted backend */
	private static void put(long backendId, String key, Path plain, long docId, String name) throws IOException {
		Backends.Entry entry = backends().entry(backendId);
		if (entry.s3())
			throw new IOException("A save is never written to S3 backend " + backendId + " itself, only staged");
		if (!entry.encrypted()) {
			entry.backend().put(key, plain, null);
			return;
		}
		Path cipher = Files.createTempFile("healdan-storage", ".enc");
		try {
			entry.cipher().encrypt(plain, cipher, FileCipher.associated(docId, name));
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
			if (!hold(backendId, key, docId, name))
				log.warn("The copy of document {}'s {} that a save replaced stays on storage backend {}, not held",
						docId, name, backendId);
		} catch (RuntimeException e) {
			// The save stands: reporting it as failed would leave CE out of step with the row just written
			log.error("The save of document {}'s {} stands, but the copy it replaced on storage backend {} couldn't be "
					+ "recorded as held ({}): it is held with no row in hd_storage_held, under the backend's .held/ or, "
					+ "on S3, where it was, and no purge will delete it", docId, name, backendId, e.getMessage(), e);
		}
	}

	/**
	 * Holds a file's copy (spec section 8): a local backend's, staging's included, moves under its {@code .held/}; an
	 * S3 one stays where it is, and nothing is sent. A row then keeps it until its purge date, {@code storage.holdDays}
	 * from now. Called holding the file's lock.
	 *
	 * @return true if the copy is held, or wasn't there to hold; false if it couldn't be held and is still in its place
	 */
	private boolean hold(long backendId, String key, long docId, String name) {
		Backends.Entry entry;
		try {
			entry = backends().entry(backendId);
		} catch (BackendFailingException e) {
			if (!isS3(backendId)) {
				log.warn("Document {}'s {} can't be held on storage backend {}: {}", docId, name, backendId,
						e.getMessage());
				return false;
			}
			// S3's hold sends nothing and leaves the object where it is, so one that can't be built holds the same
			log.warn("Storage backend {} can't be used ({}); document {}'s {} is held where it is", backendId,
					e.getMessage(), docId, name);
			recordHeld(backendId, key, docId, name);
			return true;
		}

		String location;
		try {
			location = entry.backend().hold(key);
		} catch (FileNotFoundException e) {
			log.warn("Document {}'s {} wasn't on storage backend {} to be held", docId, name, backendId);
			return true;
		} catch (IOException | IllegalArgumentException e) {
			// A local folder that fails its marker, or a stray file whose name is no storage key
			log.warn("Document {}'s {} can't be held on storage backend {}: {}", docId, name, backendId,
					e.getMessage());
			return false;
		}
		recordHeld(backendId, location, docId, name);
		return true;
	}

	private static boolean isS3(long backendId) {
		try {
			return backends().isS3(backendId);
		} catch (BackendFailingException e) {
			return false;
		}
	}

	private static void recordHeld(long backendId, String location, long docId, String name) {
		Date until = new Date(System.currentTimeMillis() + StorageSettings.get().holdDays() * DAY_MS);
		HeldCopies.get().hold(backendId, location, docId, name, until);
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
		try (FileLocks.Held held = FileLocks.lock(docId, name)) {
			Optional<StoredFile> row = files().find(docId, name);
			if (row.isPresent()) {
				if (!hold(row.get().backendId(), key, docId, name))
					return false;
				files().delete(docId, name);
				return true;
			}
			if (!Files.isRegularFile(original(key)))
				return false;
			log.warn("Document {}'s {} has no storage row; it is deleted from Original's folder", docId, name);
			return hold(StorageBackend.ORIGINAL, key, docId, name);
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

	/**
	 * @return the file's plain bytes, from its row's place; a file the mover took elsewhere while it was looked up is
	 *         read from where it went
	 */
	private InputStream read(long docId, String name) throws IOException {
		String key = keyOf(docId, name);
		Optional<StoredFile> row = files().find(docId, name);
		if (row.isEmpty())
			return Files.newInputStream(unrecorded(docId, name, true).orElseThrow(() -> new FileNotFoundException(key)));
		try {
			return open(row.get(), key);
		} catch (FileNotFoundException e) {
			Optional<StoredFile> now = files().find(docId, name);
			if (now.isPresent() && moved(row.get(), now.get()))
				return open(now.get(), key);
			throw e;
		}
	}

	private static boolean moved(StoredFile before, StoredFile now) {
		return before.backendId() != now.backendId() || before.write() != now.write();
	}

	/** S3: a whole read, decrypted as it is read (until Task 8's cache) */
	private static InputStream open(StoredFile row, String key) throws IOException {
		Backends.Entry entry = backends().entry(row.backendId());
		InputStream in = entry.backend().get(key);
		if (!entry.encrypted())
			return in;
		try {
			return entry.cipher().decrypting(in, FileCipher.associated(row.docId(), row.resource()));
		} catch (IOException | RuntimeException e) {
			in.close();
			throw e;
		}
	}

	/** @return the file's plain bytes as a channel, which reads a range without reading what is before it */
	private SeekableByteChannel channel(long docId, String name) throws IOException {
		String key = keyOf(docId, name);
		Optional<StoredFile> row = files().find(docId, name);
		if (row.isEmpty())
			return FileChannel.open(unrecorded(docId, name, true).orElseThrow(() -> new FileNotFoundException(key)),
					StandardOpenOption.READ);
		try {
			return channel(row.get(), key);
		} catch (FileNotFoundException e) {
			Optional<StoredFile> now = files().find(docId, name);
			if (now.isPresent() && moved(row.get(), now.get()))
				return channel(now.get(), key);
			throw e;
		}
	}

	/** S3: each read is a ranged request, and an encrypted file decrypts only the segments it covers */
	private static SeekableByteChannel channel(StoredFile row, String key) throws IOException {
		Backends.Entry entry = backends().entry(row.backendId());
		SeekableByteChannel channel = entry.backend().open(key);
		if (!entry.encrypted())
			return channel;
		try {
			return entry.cipher().decrypting(channel, FileCipher.associated(row.docId(), row.resource()));
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
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
	 * Runs each backend's check, staging's and Original's included, and records nothing (Task 12 makes this Test).
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
				backends().entry(id).backend().check();
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
