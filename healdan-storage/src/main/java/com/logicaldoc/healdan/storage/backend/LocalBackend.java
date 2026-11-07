// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A backend in a folder (a disk, or a share mounted on one). The folder holds {@code .healdan-backend}, the marker
 * naming this install and the backend, and {@code .held/}, where deleted files wait (CE's layout, under it).
 *
 * <p>
 * Every write checks the marker first, so a share that didn't mount, which leaves an empty mount point, is never
 * written to. A file is written under a temporary name in its own folder, forced to disk, checked, and then moved
 * into place in one step, so a key never holds half a file.
 * </p>
 */
public class LocalBackend implements Backend {

	public static final String MARKER = ".healdan-backend";

	public static final String HELD = ".held";

	/** A temporary file's name is the key's, then this, then a UUID */
	static final String TEMPORARY = ".healdan-tmp-";

	private final long id;

	private final String installId;

	private final Path root;

	/**
	 * @param id the backend's id
	 * @param installId the id of this install, which the marker names
	 * @param root the folder
	 * @param create writes the marker and makes {@code .held/}; otherwise the marker must already be there and name
	 *        this install and this backend, or {@link #check()} fails
	 * @throws IllegalStateException if creating, and the folder holds the marker of another backend or install
	 * @throws UncheckedIOException if creating, and the folder can't be written
	 */
	public LocalBackend(long id, String installId, Path root, boolean create) {
		this.id = id;
		this.installId = installId;
		this.root = root.toAbsolutePath().normalize();
		if (create)
			try {
				createMarker();
			} catch (IOException e) {
				throw new UncheckedIOException("Can't make the backend's folder " + this.root, e);
			}
	}

	private String marker() {
		return installId + " " + id + "\n";
	}

	private void createMarker() throws IOException {
		Path marker = root.resolve(MARKER);
		if (Files.exists(marker)) {
			String found = Files.readString(marker, StandardCharsets.UTF_8);
			if (!found.equals(marker()))
				throw new IllegalStateException("The folder " + root + " holds the marker of another backend or install");
		}
		Files.createDirectories(root.resolve(HELD));
		Path temporary = root.resolve(MARKER + TEMPORARY + UUID.randomUUID());
		try {
			try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE)) {
				out.write(ByteBuffer.wrap(marker().getBytes(StandardCharsets.UTF_8)));
				out.force(true);
			}
			move(temporary, marker);
		} finally {
			Files.deleteIfExists(temporary);
		}
		sync(root);
	}

	/**
	 * Test for a folder not saved yet: it exists, is writable (a probe file is written and deleted) and holds no
	 * marker.
	 *
	 * @param root the folder
	 * @throws BackendFailingException if it isn't such a folder
	 */
	public static void probe(Path root) throws BackendFailingException {
		if (!Files.isDirectory(root))
			throw new BackendFailingException("The folder " + root + " doesn't exist");
		if (Files.exists(root.resolve(MARKER)))
			throw new BackendFailingException("The folder " + root + " already belongs to a backend");
		Path probe = root.resolve(".healdan-test-" + UUID.randomUUID());
		try {
			Files.write(probe, new byte[] { 1 }, StandardOpenOption.CREATE_NEW);
			Files.delete(probe);
		} catch (IOException e) {
			try {
				Files.deleteIfExists(probe);
			} catch (IOException ignored) {
				// The folder is refused anyway
			}
			throw new BackendFailingException("The folder " + root + " can't be written to", e);
		}
	}

	@Override
	public long id() {
		return id;
	}

	/** @return where the key's file is, never outside the root */
	private Path path(String key) {
		if (key == null || key.isEmpty() || key.startsWith("/") || key.indexOf('\\') >= 0 || key.indexOf('\0') >= 0)
			throw new IllegalArgumentException("Not a storage key: " + key);
		for (String segment : key.split("/", -1))
			if (segment.isEmpty() || segment.startsWith("."))
				throw new IllegalArgumentException("Not a storage key: " + key);
		Path path = root.resolve(key).normalize();
		if (!path.startsWith(root))
			throw new IllegalArgumentException("Not a storage key: " + key);
		return path;
	}

	@Override
	public void check() throws BackendFailingException {
		Path marker = root.resolve(MARKER);
		String found;
		try {
			found = Files.readString(marker, StandardCharsets.UTF_8);
		} catch (NoSuchFileException e) {
			throw new BackendFailingException("The folder of backend " + id + " has no " + MARKER
					+ ": it may not be mounted");
		} catch (IOException e) {
			throw new BackendUnreachableException("The folder of backend " + id + " can't be read: " + e.getMessage(),
					e);
		}
		if (!found.equals(marker()))
			throw new BackendFailingException("The folder of backend " + id + " belongs to another backend or install");
	}

	@Override
	public void put(String key, Path file, byte[] md5) throws IOException {
		Path target = path(key);
		check();
		Path temporary = write(target, file, md5);
		try {
			move(temporary, target);
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(temporary);
			throw e;
		}
		sync(target.getParent());
	}

	@Override
	public Staged stage(String key, Path file, byte[] md5) throws IOException {
		Path target = path(key);
		check();
		Path temporary = write(target, file, md5);
		return new Staged() {
			@Override
			public void commit() throws IOException {
				check();
				try {
					move(temporary, target);
				} catch (IOException | RuntimeException e) {
					Files.deleteIfExists(temporary);
					throw e;
				}
				sync(target.getParent());
			}

			@Override
			public void discard() throws IOException {
				Files.deleteIfExists(temporary);
			}
		};
	}

	/**
	 * Writes the file under a temporary name next to its key, forced to disk and checked.
	 *
	 * @return the temporary file, which is gone if this throws
	 */
	private Path write(Path target, Path source, byte[] md5) throws IOException {
		Files.createDirectories(target.getParent());
		Path temporary = target.resolveSibling(target.getFileName() + TEMPORARY + UUID.randomUUID());
		try {
			long expected = Files.size(source);
			MessageDigest digest = md5 == null ? null : digest();
			try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE)) {
				long copied = copy(source, out, digest);
				out.force(true);
				if (copied != expected || out.size() != expected)
					throw new IOException("The copy of " + source.getFileName() + " is " + out.size() + " bytes, not "
							+ expected);
			}
			if (digest != null && !Arrays.equals(md5, digest.digest()))
				throw new IOException("The copy of " + source.getFileName() + " doesn't match its checksum");
			return temporary;
		} catch (IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	/**
	 * Copies the source's bytes.
	 *
	 * @param digest hashes what is copied, if not null
	 * @return the number of bytes copied
	 */
	protected long copy(Path source, FileChannel out, MessageDigest digest) throws IOException {
		long total = 0;
		ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
		try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ)) {
			while (in.read(buffer) >= 0) {
				buffer.flip();
				if (digest != null)
					digest.update(buffer.duplicate());
				total += buffer.remaining();
				while (buffer.hasRemaining())
					out.write(buffer);
				buffer.clear();
			}
		}
		return total;
	}

	private static MessageDigest digest() throws IOException {
		try {
			return MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			throw new IOException("This folder can't move a file into place in one step", e);
		}
	}

	/** Makes a folder's entries durable. Where a folder can't be opened (Windows), there is nothing to do. */
	private static void sync(Path folder) throws IOException {
		FileChannel channel;
		try {
			channel = FileChannel.open(folder, StandardOpenOption.READ);
		} catch (IOException | UnsupportedOperationException e) {
			return;
		}
		try (channel) {
			channel.force(true);
		}
	}

	@Override
	public InputStream get(String key) throws IOException {
		try {
			return Files.newInputStream(path(key));
		} catch (NoSuchFileException e) {
			throw missing(key, e);
		}
	}

	@Override
	public SeekableByteChannel open(String key) throws IOException {
		try {
			return Files.newByteChannel(path(key), StandardOpenOption.READ);
		} catch (NoSuchFileException e) {
			throw missing(key, e);
		}
	}

	private static FileNotFoundException missing(String key, Exception cause) {
		FileNotFoundException e = new FileNotFoundException(key);
		e.initCause(cause);
		return e;
	}

	@Override
	public long size(String key) throws IOException {
		try {
			return Files.size(path(key));
		} catch (NoSuchFileException e) {
			return -1;
		}
	}

	@Override
	public void delete(String key) throws IOException {
		Files.deleteIfExists(path(key));
	}

	@Override
	public List<String> list(String prefix, int max) throws IOException {
		List<String> keys = new ArrayList<>();
		if (max <= 0 || !Files.isDirectory(root))
			return keys;
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				return !dir.equals(root) && dir.getFileName().toString().startsWith(".") ? FileVisitResult.SKIP_SUBTREE
						: FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				String name = file.getFileName().toString();
				if (!name.startsWith(".") && !name.contains(TEMPORARY)) {
					String key = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
					if (key.startsWith(prefix))
						keys.add(key);
				}
				return keys.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
			}
		});
		return keys;
	}

	@Override
	public Optional<String> held(String key) {
		return Files.isRegularFile(heldPath(key)) ? Optional.of(HELD + "/" + key) : Optional.empty();
	}

	private Path heldPath(String key) {
		path(key);
		return root.resolve(HELD).resolve(key);
	}

	@Override
	public String hold(String key) throws IOException {
		Path from = path(key);
		check();
		Path to = heldPath(key);
		if (!Files.isRegularFile(from))
			throw new FileNotFoundException(key);
		Files.createDirectories(to.getParent());
		Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		sync(to.getParent());
		sync(from.getParent());
		return HELD + "/" + key;
	}

	@Override
	public void purgeHeld(String location) throws IOException {
		if (location == null || !location.startsWith(HELD + "/"))
			throw new IOException("Not a held location: " + location);
		Path held;
		try {
			held = heldPath(location.substring(HELD.length() + 1));
		} catch (IllegalArgumentException e) {
			throw new IOException("Not a held location: " + location, e);
		}
		Files.deleteIfExists(held);
	}
}
