// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import java.io.IOException;
import java.nio.channels.FileChannel;
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
import java.util.Locale;
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
	 * Sets when a file last changed, which orders the files a log rolled over to.
	 */
	public static void modified(Path file, Instant when) throws IOException {
		Files.setLastModifiedTime(file, FileTime.from(when));
	}
}
