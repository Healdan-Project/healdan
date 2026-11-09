// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.signin.SecretBox;

/**
 * The key files of encrypted backends (spec section 9): {@code backend-<id>.key} in one folder, each the backend's 32
 * key bytes, mode 600, in a folder of mode 700. The folder is the system property {@code healdan.storageKeys}, which
 * {@code docker/setenv.sh} sets, or else {@code healdan-storage-keys/} beside {@link SecretBox}'s key file.
 * <p>
 * A key is put in place whole, as {@code SecretBox} puts its own: written to a {@code .new} file with its mode, moved
 * into place, and the folder synced, under a lock file in the folder, so two makers (two servers sharing the folder
 * too) make one key. {@link #make} never overwrites a key. Key bytes are never logged or put in a message.
 */
@Component
public class BackendKeys {

	/** The system property that names the folder, which {@code docker/setenv.sh} sets */
	public static final String FOLDER_PROPERTY = "healdan.storageKeys";

	private static final int KEY_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	/** Held while a key is written, so that two makers in one JVM take turns; the lock file does it between processes */
	private static final Object WRITING = new Object();

	private static final Logger log = LoggerFactory.getLogger(BackendKeys.class);

	private final Path folder;

	/** Keys in the folder named by the system property, or beside {@code SecretBox}'s key file */
	public BackendKeys() {
		this(defaultFolder());
	}

	/**
	 * @param folder where the key files are, or will be made
	 */
	public BackendKeys(Path folder) {
		this.folder = folder;
	}

	private static Path defaultFolder() {
		String configured = System.getProperty(FOLDER_PROPERTY);
		if (StringUtils.isNotBlank(configured))
			return Paths.get(configured.trim());
		return configurationFolder().resolve("healdan-storage-keys");
	}

	/**
	 * @return the configuration folder, absolute: the folder of {@link SecretBox}'s key file, as the system property
	 *         {@code healdan.secretKey} names it ({@code /opt/logicaldoc/conf/}, the {@code conf} volume, in Docker),
	 *         or else {@code logicaldoc/conf/} in the user's home folder
	 */
	public static Path configurationFolder() {
		String secret = System.getProperty(SecretBox.KEY_PROPERTY);
		Path secretFile = StringUtils.isNotBlank(secret) ? Paths.get(secret.trim())
				: Paths.get(System.getProperty("user.home"), "logicaldoc", "conf", "healdan-secret.key");
		return secretFile.toAbsolutePath().normalize().getParent();
	}

	/** @return the folder of the key files, which may not exist yet */
	public Path folder() {
		return folder;
	}

	/**
	 * @param backendId the backend's id
	 * @return that backend's key file, which may not exist
	 */
	public Path file(long backendId) {
		return folder.resolve("backend-" + backendId + ".key");
	}

	/**
	 * Makes a backend's key: 32 random bytes.
	 *
	 * @param backendId the backend's id
	 * @return the key
	 * @throws IOException if a key file for it is already there, which this never overwrites, or the file can't be
	 *         written
	 */
	public byte[] make(long backendId) throws IOException {
		byte[] key = new byte[KEY_BYTES];
		RANDOM.nextBytes(key);
		put(backendId, key, false);
		log.info("Made the key file for storage backend {}. Back it up, and keep its recovery key.", backendId);
		return key;
	}

	/**
	 * @param backendId the backend's id
	 * @return the key, or empty if the file is missing, can't be read or isn't 32 bytes
	 */
	public Optional<byte[]> read(long backendId) {
		Path file = file(backendId);
		try {
			if (!Files.isRegularFile(file) || Files.size(file) != KEY_BYTES)
				return Optional.empty();
			byte[] key = Files.readAllBytes(file);
			return key.length == KEY_BYTES ? Optional.of(key) : Optional.empty();
		} catch (IOException e) {
			// An unreadable file (permissions, a failing disk) is not a missing one, which the admin needs to tell apart
			log.warn("The key file {} of storage backend {} can't be read: {}: {}", file, backendId,
					e.getClass().getSimpleName(), e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Puts a key back (Enter Recovery Key), replacing a file that is missing or holds another key. The file it replaces
	 * may be another backend's only copy of its key (a file renamed onto the wrong id), so unless it holds this same key
	 * it is kept as {@code backend-<id>.key.replaced-<millis>}, with mode 600.
	 *
	 * @param backendId the backend's id
	 * @param key the 32 key bytes
	 * @throws IOException if the file can't be written
	 * @throws IllegalArgumentException if the key is not 32 bytes
	 */
	public void restore(long backendId, byte[] key) throws IOException {
		if (key == null || key.length != KEY_BYTES)
			throw new IllegalArgumentException("A key is " + KEY_BYTES + " bytes");
		put(backendId, key, true);
		log.info("Restored the key file for storage backend {}.", backendId);
	}

	/**
	 * Removes a backend's key file, once the backend is empty. A file that is already gone is no error.
	 *
	 * @param backendId the backend's id
	 * @throws IOException if the file can't be removed
	 */
	public void delete(long backendId) throws IOException {
		if (!Files.isDirectory(folder))
			return;
		synchronized (WRITING) {
			try (FileLock lock = lock()) {
				Files.deleteIfExists(file(backendId));
				syncFolder();
			}
		}
		log.info("Removed the key file for storage backend {}.", backendId);
	}

	@Override
	public String toString() {
		return "BackendKeys[" + folder + "]";
	}

	private void put(long backendId, byte[] key, boolean replace) throws IOException {
		synchronized (WRITING) {
			try (FileLock lock = lock()) {
				Path file = file(backendId);
				// Another server sharing the folder may have made a key while this one waited
				if (!replace && Files.exists(file))
					throw new IOException("The key file " + file + " exists already, which is not overwritten");
				if (replace)
					keepReplaced(backendId, file, key);
				Path fresh = folder.resolve(file.getFileName() + ".new");
				Files.deleteIfExists(fresh);
				try {
					try (FileChannel channel = FileChannel.open(fresh,
							EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), ownerOnly(false))) {
						channel.write(ByteBuffer.wrap(key));
						channel.force(true);
					}
					if (replace)
						Files.move(fresh, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
					else
						// Fails if a process that takes no lock made a key meanwhile, rather than replacing it
						Files.move(fresh, file);
				} finally {
					Files.deleteIfExists(fresh);
				}
				syncFolder();
			}
		}
	}

	/** Keeps the bytes of a key file that {@code key} is about to replace, unless they are the same */
	private void keepReplaced(long backendId, Path file, byte[] key) throws IOException {
		if (!Files.isRegularFile(file))
			return;
		byte[] old = Files.readAllBytes(file);
		if (java.util.Arrays.equals(old, key))
			return;
		for (long millis = System.currentTimeMillis();; millis++) {
			Path aside = folder.resolve(file.getFileName() + ".replaced-" + millis);
			try (FileChannel channel = FileChannel.open(aside,
					EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), ownerOnly(false))) {
				channel.write(ByteBuffer.wrap(old));
				channel.force(true);
				log.warn("The key file of storage backend {} held another key, kept as {}.", backendId, aside.getFileName());
				return;
			} catch (java.nio.file.FileAlreadyExistsException taken) {
				// the same millisecond: try the next
			}
		}
	}

	/** @return the lock on the folder, which the folder is made for first */
	private FileLock lock() throws IOException {
		if (!Files.isDirectory(folder))
			Files.createDirectories(folder, ownerOnly(true));
		if (isPosix())
			Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
		FileChannel channel = FileChannel.open(folder.resolve("keys.lock"),
				EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), ownerOnly(false));
		try {
			return new Lock(channel, channel.lock());
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	/** A file lock that closes its channel too */
	private static final class Lock extends FileLock {

		private final FileLock inner;

		Lock(FileChannel channel, FileLock inner) {
			super(channel, 0, Long.MAX_VALUE, false);
			this.inner = inner;
		}

		@Override
		public boolean isValid() {
			return inner.isValid();
		}

		/** Releases the lock and closes the channel, as {@code close()} does */
		@Override
		public void release() throws IOException {
			try {
				inner.release();
			} finally {
				channel().close();
			}
		}
	}

	private static boolean isPosix() {
		return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
	}

	/** @return the mode of a file only the app's user can use, where the file system has modes */
	private static FileAttribute<?>[] ownerOnly(boolean folder) {
		return isPosix() ? new FileAttribute<?>[] {
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(folder ? "rwx------" : "rw-------")) }
				: new FileAttribute<?>[0];
	}

	private void syncFolder() {
		try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException e) {
			// Some systems, such as Windows, can't open a folder to sync it
			log.warn("Couldn't sync the folder {} after changing a storage key file ({}). Back the key files up: a crash "
					+ "before the system writes the folder out could lose a key.", folder, e.getClass().getSimpleName());
		}
	}
}
