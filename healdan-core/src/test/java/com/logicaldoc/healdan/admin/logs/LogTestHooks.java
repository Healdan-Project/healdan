// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.logicaldoc.util.config.LogConfigurator;

/**
 * What the Server Log's tests change in {@link LogFiles}, here and in other modules' tests: the logging configuration
 * it reads, the test {@code log.xml} with its root in a folder of the test's own under {@code target/}, and a file
 * it can't open. {@link #reset()} puts both back and deletes the folder.
 */
public final class LogTestHooks {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss,SSS", Locale.ROOT);

	private static Path folder;

	private LogTestHooks() {
	}

	/**
	 * Points {@link LogFiles} at the test {@code log.xml}, with its logs in a new folder under {@code target/}.
	 *
	 * @return the folder, empty
	 * @throws IOException if it can't be made
	 */
	public static Path useNewFolder() throws IOException {
		Path target = Path.of("target");
		Files.createDirectories(target);
		folder = Files.createTempDirectory(target, "healdan-logs-").toAbsolutePath();
		LogConfigurator configuration = new LogConfigurator();
		configuration.setLogsRoot(folder.toString());
		LogFiles.get().configuration = () -> configuration;
		return folder;
	}

	/**
	 * @param configuration what {@link LogFiles} reads its configuration with, such as one that fails
	 */
	public static void useConfiguration(Supplier<LogConfigurator> configuration) {
		LogFiles.get().configuration = configuration;
	}

	/**
	 * Makes {@link LogFiles} fail to open a file, as it would one it may not read.
	 *
	 * @param file the file
	 */
	public static void refuseToOpen(Path file) {
		Path refused = file.toAbsolutePath();
		LogFiles.get().opener = path -> {
			if (path.toAbsolutePath().equals(refused))
				throw new AccessDeniedException(path.toString());
			return FileChannel.open(path, StandardOpenOption.READ);
		};
	}

	/**
	 * Makes a file shorter than it was when {@link LogFiles} opened it: it's cut to a size once opened, while the
	 * channel still gives the size it had, as when the file is truncated in the middle of a read.
	 *
	 * @param file the file
	 * @param size what it's cut to
	 */
	public static void shrinkAfterOpening(Path file, long size) {
		Path shrinking = file.toAbsolutePath();
		LogFiles.get().opener = path -> {
			FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
			if (!path.toAbsolutePath().equals(shrinking))
				return channel;
			long was = channel.size();
			try (FileChannel writer = FileChannel.open(path, StandardOpenOption.WRITE)) {
				writer.truncate(size);
			}
			return new SizedChannel(channel, was);
		};
	}

	/**
	 * Puts back how {@link LogFiles} opens its files, after {@link #refuseToOpen}, {@link #shrinkAfterOpening} or
	 * {@link #keepOpened}.
	 */
	public static void openAsUsual() {
		LogFiles.get().opener = path -> FileChannel.open(path, StandardOpenOption.READ);
	}

	/**
	 * Keeps the channels {@link LogFiles} opens, so that a test can tell whether each was closed.
	 *
	 * @return the channels, as they're opened
	 */
	public static List<FileChannel> keepOpened() {
		List<FileChannel> opened = new CopyOnWriteArrayList<>();
		LogFiles.get().opener = path -> {
			FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
			opened.add(channel);
			return channel;
		};
		return opened;
	}

	/** A file's channel that gives a size of its own, the file's before it was cut, and is otherwise the file's */
	private static final class SizedChannel extends FileChannel {

		private final FileChannel file;

		private final long size;

		SizedChannel(FileChannel file, long size) {
			this.file = file;
			this.size = size;
		}

		@Override
		public long size() {
			return size;
		}

		@Override
		public int read(ByteBuffer dst) throws IOException {
			return file.read(dst);
		}

		@Override
		public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
			return file.read(dsts, offset, length);
		}

		@Override
		public int read(ByteBuffer dst, long position) throws IOException {
			return file.read(dst, position);
		}

		@Override
		public int write(ByteBuffer src) throws IOException {
			return file.write(src);
		}

		@Override
		public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
			return file.write(srcs, offset, length);
		}

		@Override
		public int write(ByteBuffer src, long position) throws IOException {
			return file.write(src, position);
		}

		@Override
		public long position() throws IOException {
			return file.position();
		}

		@Override
		public FileChannel position(long newPosition) throws IOException {
			file.position(newPosition);
			return this;
		}

		@Override
		public FileChannel truncate(long newSize) throws IOException {
			file.truncate(newSize);
			return this;
		}

		@Override
		public void force(boolean metaData) throws IOException {
			file.force(metaData);
		}

		@Override
		public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
			return file.transferTo(position, count, target);
		}

		@Override
		public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
			return file.transferFrom(src, position, count);
		}

		@Override
		public MappedByteBuffer map(MapMode mode, long position, long length) throws IOException {
			return file.map(mode, position, length);
		}

		@Override
		public FileLock lock(long position, long length, boolean shared) throws IOException {
			return file.lock(position, length, shared);
		}

		@Override
		public FileLock tryLock(long position, long length, boolean shared) throws IOException {
			return file.tryLock(position, length, shared);
		}

		@Override
		protected void implCloseChannel() throws IOException {
			file.close();
		}
	}

	/**
	 * Puts {@link LogFiles} back as it is outside the tests, and deletes the folder {@link #useNewFolder} made.
	 *
	 * @throws IOException if the folder can't be deleted
	 */
	public static void reset() throws IOException {
		LogFiles files = LogFiles.get();
		files.configuration = LogConfigurator::new;
		files.opener = path -> FileChannel.open(path, StandardOpenOption.READ);
		if (folder != null && Files.exists(folder)) {
			try (Stream<Path> walk = Files.walk(folder)) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
		folder = null;
	}

	/**
	 * @return a line that starts an entry, as the server's logging writes one, with its line feed
	 */
	public static String entry(Instant time, String level, String source, int line, String message) {
		return "%s %-5s (%s:%4d) -- %s\n".formatted(TIME.format(time.atZone(ZoneId.systemDefault())), level, source,
				line, message);
	}

	/**
	 * Adds text to the end of a file, making it if it isn't there.
	 */
	public static void append(Path file, String text) throws IOException {
		Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/**
	 * Sets when a file last changed, which a log's {@code modified} gives for its current file. It doesn't order the
	 * files a log rolled over to: their numbers do, as the log's configuration counts them.
	 */
	public static void modified(Path file, Instant when) throws IOException {
		Files.setLastModifiedTime(file, FileTime.from(when));
	}
}
