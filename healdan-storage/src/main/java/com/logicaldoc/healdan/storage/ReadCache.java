// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.healdan.storage.backend.Backend;
import com.logicaldoc.healdan.storage.crypto.FileCipher;

/**
 * The read cache (spec section 6.3): plain copies of S3 files on the server, so a second read costs no request and an
 * encrypted backend's files are decrypted once. An entry is {@code <backendId>/<docId>/<resource>@<write>}, named by
 * the row's write id (call 21), which a rewrite changes and a database restored in place, which hands out document ids
 * again, never repeats: so neither can be served another file's bytes.
 * <p>
 * The cache holds at most the cap's bytes, the least recently read dropped first. The order is kept in memory; on a
 * restart the index is rebuilt from the folder, in the files' modification times, which a hit renews. A file larger
 * than a tenth of the cap isn't cached, so a few big files can't empty the cache: the caller reads its ranges from the
 * bucket.
 * <p>
 * A miss downloads the whole file into a temporary file in the cache folder and moves it into place atomically, so a
 * reader never meets a partial entry. Two misses of one entry may both download; the later move replaces the first with
 * the same bytes. A temporary file a crash left is deleted when the index is rebuilt. An entry dropped while a reader
 * has it open stays readable to that reader (POSIX).
 */
public class ReadCache {

	private static final Logger log = LoggerFactory.getLogger(ReadCache.class);

	/** The system property that names the folder */
	public static final String FOLDER_PROPERTY = "healdan.storageCache";

	private static final String TEMPORARY = ".tmp-";

	private static volatile ReadCache instance;

	private final Path folder;

	private final LongSupplier capBytes;

	/** The entries, oldest read first: relative path to size */
	private final LinkedHashMap<String, Long> lru = new LinkedHashMap<>(256, 0.75f, true);

	/** Each file's entries, by {@code docId/resource} */
	private final Map<String, Set<String>> byFile = new HashMap<>();

	private long total;

	/**
	 * @param folder the cache's folder, made if missing; its index is rebuilt from what it holds
	 * @param capBytes the cache's size in bytes, asked each time it is used so a saved setting applies at once
	 */
	public ReadCache(Path folder, LongSupplier capBytes) {
		this.folder = folder.toAbsolutePath().normalize();
		this.capBytes = capBytes;
		rebuild();
	}

	/**
	 * @return the cache's folder: {@code healdan.storageCache}, or else {@code cache/} beside Original's folder
	 *         ({@code store.1.dir})
	 */
	public static Path folder() {
		String configured = System.getProperty(FOLDER_PROPERTY);
		if (StringUtils.isNotBlank(configured))
			return Path.of(configured.trim()).toAbsolutePath().normalize();
		return Staging.original().resolveSibling("cache");
	}

	/**
	 * @return the cache of this server, sized by {@code storage.cacheGb}, which is read each time
	 */
	public static ReadCache get() {
		Path wanted = folder();
		ReadCache current = instance;
		if (current != null && current.folder.equals(wanted))
			return current;
		synchronized (ReadCache.class) {
			current = instance;
			if (current == null || !current.folder.equals(wanted)) {
				current = new ReadCache(wanted, () -> (long) StorageSettings.get().cacheGb() << 30);
				instance = current;
			}
			return current;
		}
	}

	/** Forgets the cache of this server, so the next {@link #get} rebuilds its index; for tests */
	static void reset() {
		synchronized (ReadCache.class) {
			instance = null;
		}
	}

	/** Uses a cache in place of the server's; null clears it. For tests, which size it in megabytes */
	static void use(ReadCache cache) {
		synchronized (ReadCache.class) {
			instance = cache;
		}
	}

	/** @return the folder this cache keeps */
	Path root() {
		return folder;
	}

	private static String fileKey(long docId, String resource) {
		return docId + "/" + resource;
	}

	private static String entryName(long backendId, long docId, String resource, long write) {
		return backendId + "/" + docId + "/" + resource + "@" + write;
	}

	/** @return whether a file of this size is too big to be cached: over a tenth of the cap */
	public boolean tooBig(long size) {
		return size > capBytes.getAsLong() / 10;
	}

	/**
	 * Looks a write of a file up in the cache, with no backend: one that can't be built, in an outage, still has its
	 * cached files read (spec section 6.5).
	 *
	 * @param backendId the row's backend
	 * @param write the row's write id
	 * @return the entry, marked read; empty if it isn't cached, and nothing is downloaded
	 */
	public Optional<Path> cached(long backendId, long docId, String resource, long write) {
		return Optional.ofNullable(hit(entryName(backendId, docId, resource, write)));
	}

	/**
	 * The file's plain bytes as a file in the cache, downloaded and decrypted if it isn't there.
	 *
	 * @param backend where the file is
	 * @param cipher the backend's cipher; null if its files are plain
	 * @param backendId the row's backend
	 * @param write the row's write id
	 * @param size the row's plaintext size, which what arrives must match: a download that came up short is an error,
	 *        never an entry
	 * @param key the file's key on the backend
	 * @return the entry; empty if the file is too big to cache, in which case nothing is downloaded
	 * @throws java.io.FileNotFoundException if the backend has no such file
	 * @throws com.logicaldoc.healdan.storage.backend.BackendUnreachableException if it can't be reached and the file
	 *         isn't cached; the caller knows the Store
	 * @throws IOException if the download or its decryption failed, or arrived with another size
	 */
	public Optional<Path> read(Backend backend, FileCipher cipher, long backendId, long docId, String resource,
			long write, long size, String key) throws IOException {
		String name = entryName(backendId, docId, resource, write);
		Path hit = hit(name);
		if (hit != null)
			return Optional.of(hit);
		if (tooBig(size))
			return Optional.empty();
		Path temp = temporary();
		try {
			try (InputStream raw = backend.get(key);
					InputStream in = cipher == null ? raw
							: cipher.decrypting(raw, FileCipher.associated(docId, resource))) {
				Files.copy(in, temp);
			}
			long arrived = Files.size(temp);
			if (arrived != size)
				throw new IOException("Document " + docId + "'s " + resource + " arrived with " + arrived
						+ " bytes, not the " + size + " it has");
			return Optional.of(insert(name, docId, resource, temp, size));
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/**
	 * Makes a copy the mover has uploaded this write's entry (spec question 9), so it isn't downloaded again. Staging
	 * is on the repository volume and the cache on another, so the file is copied and then deleted from staging. A
	 * file too big to cache is just deleted.
	 *
	 * @param staged the staged copy, plain
	 * @throws IOException if the copy failed; the staged file is then kept
	 */
	public void adopt(long backendId, long docId, String resource, long write, Path staged) throws IOException {
		long size = Files.size(staged);
		if (!tooBig(size)) {
			Path temp = temporary();
			try {
				Files.copy(staged, temp);
				insert(entryName(backendId, docId, resource, write), docId, resource, temp, size);
			} finally {
				Files.deleteIfExists(temp);
			}
		}
		Files.deleteIfExists(staged);
	}

	/** Drops every entry of a file, whichever backend and write: it is deleted or has been rewritten */
	public void delete(long docId, String resource) {
		List<String> doomed;
		synchronized (this) {
			Set<String> names = byFile.get(fileKey(docId, resource));
			if (names == null)
				return;
			doomed = new ArrayList<>(names);
			for (String name : doomed)
				forget(name);
		}
		for (String name : doomed)
			remove(folder.resolve(name));
	}

	private Path temporary() throws IOException {
		Files.createDirectories(folder);
		return folder.resolve(TEMPORARY + UUID.randomUUID());
	}

	/** @return the entry's file if it is there, marking it read; null if not */
	private Path hit(String name) {
		Path file = folder.resolve(name);
		synchronized (this) {
			Long size = lru.get(name);
			if (size == null)
				return null;
			if (Files.isRegularFile(file)) {
				try {
					Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
				} catch (IOException e) {
					log.debug("The read time of cache entry {} wasn't kept: {}", name, e.getMessage());
				}
				return file;
			}
			// Gone from under the cache: forget it
			forget(name);
			return null;
		}
	}

	/** Moves a downloaded file into place as an entry, and drops the oldest entries while the cache is over its cap */
	private Path insert(String name, long docId, String resource, Path temp, long size) throws IOException {
		Path file = folder.resolve(name);
		Files.createDirectories(file.getParent());
		try {
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
		List<String> evicted = new ArrayList<>();
		synchronized (this) {
			forget(name);
			lru.put(name, size);
			byFile.computeIfAbsent(fileKey(docId, resource), k -> new HashSet<>()).add(name);
			total += size;
			long cap = capBytes.getAsLong();
			Iterator<Map.Entry<String, Long>> oldest = lru.entrySet().iterator();
			while (total > cap && oldest.hasNext()) {
				Map.Entry<String, Long> entry = oldest.next();
				if (entry.getKey().equals(name))
					break;
				evicted.add(entry.getKey());
				oldest.remove();
				total -= entry.getValue();
				unlink(entry.getKey());
			}
		}
		for (String old : evicted)
			remove(folder.resolve(old));
		return file;
	}

	/** Takes an entry out of the index; the caller deletes its file. Holds the monitor. */
	private void forget(String name) {
		Long size = lru.remove(name);
		if (size != null)
			total -= size;
		unlink(name);
	}

	private void unlink(String name) {
		int at = name.lastIndexOf('@');
		String[] parts = (at < 0 ? name : name.substring(0, at)).split("/", 3);
		if (parts.length < 3)
			return;
		String key = fileKey(Long.parseLong(parts[1]), parts[2]);
		Set<String> names = byFile.get(key);
		if (names != null) {
			names.remove(name);
			if (names.isEmpty())
				byFile.remove(key);
		}
	}

	private static void remove(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.warn("The cache entry {} wasn't deleted: {}", file, e.getMessage());
		}
	}

	/** The index, from the folder: oldest modification first; temporary and unrecognised files are deleted */
	private void rebuild() {
		record Found(String name, long size, long modified, long docId, String resource) {
		}
		List<Found> found = new ArrayList<>();
		if (Files.isDirectory(folder)) {
			try (Stream<Path> walk = Files.walk(folder)) {
				for (Path file : walk.filter(Files::isRegularFile).toList()) {
					String name = folder.relativize(file).toString().replace('\\', '/');
					Found entry = null;
					String[] parts = name.split("/", 3);
					int at = name.lastIndexOf('@');
					if (parts.length == 3 && at > 0 && !name.contains(TEMPORARY)) {
						try {
							Long.parseLong(parts[0]);
							long docId = Long.parseLong(parts[1]);
							Long.parseLong(name.substring(at + 1));
							entry = new Found(name, Files.size(file), Files.getLastModifiedTime(file).toMillis(), docId,
									name.substring(parts[0].length() + parts[1].length() + 2, at));
						} catch (NumberFormatException | IOException e) {
							entry = null;
						}
					}
					if (entry == null)
						remove(file);
					else
						found.add(entry);
				}
			} catch (IOException e) {
				log.warn("The cache folder {} can't be read: {}", folder, e.getMessage());
			}
		}
		found.sort((a, b) -> Long.compare(a.modified(), b.modified()));
		List<String> doomed = new ArrayList<>();
		synchronized (this) {
			for (Found entry : found) {
				lru.put(entry.name(), entry.size());
				byFile.computeIfAbsent(fileKey(entry.docId(), entry.resource()), k -> new HashSet<>()).add(entry.name());
				total += entry.size();
			}
			long cap = capBytes.getAsLong();
			Iterator<Map.Entry<String, Long>> oldest = lru.entrySet().iterator();
			while (total > cap && oldest.hasNext()) {
				Map.Entry<String, Long> entry = oldest.next();
				doomed.add(entry.getKey());
				oldest.remove();
				total -= entry.getValue();
				unlink(entry.getKey());
			}
		}
		for (String name : doomed)
			remove(folder.resolve(name));
		if (!found.isEmpty())
			log.info("The read cache in {} holds {} files", folder, found.size());
	}
}
