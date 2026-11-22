// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jdom2.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.task.Task;
import com.logicaldoc.core.task.TaskManager;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.jobs.Jobs;
import com.logicaldoc.healdan.admin.logs.LogChain.Part;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.util.config.LogConfigurator;
import com.logicaldoc.util.spring.Context;

/**
 * The server's logs, for the Server Log (Admin app spec section 8.8; rulings P5-R2, P5-R5, P5-R6 and P5-R27): the
 * files LogicalDOC's logging writes, as its configuration, {@code log.xml}, names them, read at each call through
 * {@link LogConfigurator}.
 * <p>
 * A log is a {@code RollingFile} appender, named by its appender's name: its current file
 * ({@link LogConfigurator#getFile}) and the files it rolled over to beside it, {@code <file>.1} up to its
 * {@code DefaultRolloverStrategy}'s {@code max}, read as one, oldest to newest. The HTML copies, the appenders whose
 * names end {@code _WEB}, aren't logs here. A name a request sends is only ever compared with the names
 * {@link #list} gives: nothing a request sends is joined to a path.
 * <p>
 * The lines are answered as the files hold them, but for any URL's credentials, which are taken out; a download is
 * the files as they are. Reading and downloading are for administrators only.
 */
@Component
public class LogFiles {

	private static final Logger log = LoggerFactory.getLogger(LogFiles.class);

	/** The main log's appender */
	public static final String MAIN = "DMS";

	/** The main log's label */
	public static final String MAIN_LABEL = "Main Log";

	/** The most entries a read gives */
	public static final int MAX_LINES = 200;

	/** The most bytes a read looks through, across the log's files */
	public static final long MAX_SCAN_BYTES = 5L * 1024 * 1024;

	/** The most characters of a line an entry gives */
	public static final int MAX_CHARS = 10_000;

	/** The refusal of anyone but an administrator */
	public static final String ONLY_ADMINS = "Only an administrator can read the server's logs.";

	/** The files a log rolls over to when its configuration doesn't say: log4j's own default */
	static final int DEFAULT_ROLLED = 7;

	/** The most files a log rolls over to that are looked for */
	static final int MAX_ROLLED = 100;

	/**
	 * An appender's name as the routes take it (the contract's {@code LogName}): not only dots, with no slash or
	 * backslash; and with no apostrophe, as {@link LogConfigurator#getFile} finds an appender by its name in quotes
	 */
	private static final Pattern NAME = Pattern.compile("[^/\\\\']*[^/\\\\.'][^/\\\\']*");

	private static final String HTML_COPY = "_WEB";

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
			.withZone(ZoneOffset.UTC);

	/** The logging configuration, read afresh each time: a seam, so that a test can give one of its own */
	volatile Supplier<LogConfigurator> configuration = LogConfigurator::new;

	/** What opens a log's files: a seam, so that a test can fail one */
	volatile LogChain.Opener opener = path -> FileChannel.open(path, StandardOpenOption.READ);

	private final TaskManager tasks;

	@Autowired
	public LogFiles(TaskManager tasks) {
		this.tasks = tasks;
	}

	/**
	 * @return the bean in the application context
	 */
	public static LogFiles get() {
		return Context.get(LogFiles.class);
	}

	/**
	 * A log as the configuration names it.
	 *
	 * @param name its appender's name
	 * @param label its label
	 * @param current its current file
	 * @param rolled the files it may have rolled over to
	 */
	private record Log(String name, String label, Path current, List<Path> rolled) {

		/** @return its current file and the files it rolled over to that are there, together, in bytes */
		long size() {
			long size = size(current);
			for (Path file : rolled)
				size += size(file);
			return size;
		}

		private static long size(Path file) {
			try {
				return Files.isRegularFile(file) ? Files.size(file) : 0;
			} catch (NoSuchFileException e) {
				return 0;
			} catch (IOException e) {
				throw new UncheckedIOException("Can't read the size of a log's file", e);
			}
		}
	}

	/**
	 * The logs: the main log, {@code DMS}, whenever the configuration has it, first; then each other one whose
	 * files hold anything, by label, ignoring case.
	 *
	 * @param actor who asks
	 * @return the logs, each with its files' size and when its current file last changed
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator
	 */
	public List<LogInfo> list(User actor) throws PersistenceException {
		requireAdmin(actor);
		List<LogInfo> infos = new ArrayList<>();
		for (Log listed : listed(configuration.get()))
			infos.add(new LogInfo(listed.name(), listed.label(), listed.size(), modified(listed.current())));
		return infos;
	}

	/**
	 * Reads entries of a log, oldest first: with neither {@code before} nor {@code after}, the newest; with
	 * {@code before}, the newest before it, going on into the next older file at the start of one; with
	 * {@code after}, the oldest at it or after it, in the current file, or from the current file's start when the
	 * position's file isn't the current one any more ({@link LogLines#rolled}). A {@code before} whose file has
	 * gone gives no entries, at the log's start.
	 *
	 * @param name the log's name, as {@link #list} gives it
	 * @param query where to read from, and which entries to keep
	 * @param actor who asks
	 * @return the entries; empty when the name isn't one of the logs {@link #list} gives
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator, before anything else
	 * @throws UncheckedIOException when one of the log's files can't be read
	 */
	public Optional<LogLines> read(String name, LogQuery query, User actor) throws PersistenceException {
		requireAdmin(actor);
		Objects.requireNonNull(query, "query");
		Optional<Log> found = find(name);
		if (found.isEmpty())
			return Optional.empty();
		Log listed = found.get();
		try (LogChain chain = LogChain.open(listed.current(), listed.rolled(), opener)) {
			LogReader reader = new LogReader(chain, query, MAX_SCAN_BYTES);
			int current = chain.current();
			long size = chain.parts().get(current).size();
			if (query.after() != null) {
				int part = chain.find(query.after());
				return Optional.of(part == current && query.after().offset() <= size
						? reader.forward(current, query.after().offset(), false)
						: reader.forward(current, 0, true));
			}
			if (query.before() != null) {
				int part = chain.find(query.before());
				if (part < 0) {
					LogPosition start = chain.position(0, 0);
					return Optional.of(new LogLines(List.of(), start, start, true, false, false));
				}
				return Optional.of(reader.back(part,
						Math.min(query.before().offset(), chain.parts().get(part).size())));
			}
			return Optional.of(reader.back(current, size));
		} catch (IOException e) {
			throw new UncheckedIOException("Can't read the log " + listed.name(), e);
		}
	}

	/**
	 * A log's download: its current file and the files it rolled over to, as they are, zipped. Nothing is held open
	 * between {@link LogFiles#zip} and {@link #writeTo}, so a download whose body is never written, as for a
	 * {@code HEAD}, leaves nothing open.
	 */
	public static final class LogZip {

		private final LogFiles files;

		private final Log log;

		private final String fileName;

		private LogZip(LogFiles files, Log log, String fileName) {
			this.files = files;
			this.log = log;
			this.fileName = fileName;
		}

		/**
		 * @return the zip's file name: the current file's name without its extension, and when it was made, in UTC,
		 *         such as {@code dms-20261122-104107.zip}
		 */
		public String fileName() {
			return fileName;
		}

		/**
		 * Writes the zip: an entry for each file, under its own file name, the current file first, then the newest
		 * of the files it rolled over to; each as long as it was when it was opened. The files are opened here, and
		 * closed before it returns.
		 *
		 * @param out where to write it, which it leaves open
		 * @throws IOException if a file can't be read or the zip can't be written
		 */
		public void writeTo(OutputStream out) throws IOException {
			try (LogChain chain = LogChain.open(log.current(), log.rolled(), files.opener)) {
				ZipOutputStream zip = new ZipOutputStream(out);
				ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
				List<Part> parts = chain.parts();
				for (int i = parts.size() - 1; i >= 0; i--) {
					Part part = parts.get(i);
					if (part.channel() == null)
						continue;
					zip.putNextEntry(new ZipEntry(part.path().getFileName().toString()));
					long at = 0;
					while (at < part.size()) {
						buffer.clear();
						buffer.limit((int) Math.min(buffer.capacity(), part.size() - at));
						int read = part.channel().read(buffer, at);
						if (read < 0)
							break;
						zip.write(buffer.array(), 0, read);
						at += read;
					}
					zip.closeEntry();
				}
				zip.finish();
			}
		}
	}

	/**
	 * A log's download. Its files are opened and closed again before anything is written, so that a file that
	 * can't be read fails the request rather than the download; {@link LogZip#writeTo} opens them again.
	 *
	 * @param name the log's name, as {@link #list} gives it
	 * @param actor who asks
	 * @return the download; empty when the name isn't one of the logs {@link #list} gives
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator, before anything else
	 * @throws UncheckedIOException when one of the log's files can't be read
	 */
	public Optional<LogZip> zip(String name, User actor) throws PersistenceException {
		requireAdmin(actor);
		Optional<Log> found = find(name);
		if (found.isEmpty())
			return Optional.empty();
		Log listed = found.get();
		try (LogChain chain = LogChain.open(listed.current(), listed.rolled(), opener)) {
			return Optional.of(new LogZip(this, listed, zipName(listed.current(), Instant.now())));
		} catch (IOException e) {
			throw new UncheckedIOException("Can't read the log " + listed.name(), e);
		}
	}

	/**
	 * The log a logger's lines go to (ruling P5-R5), for a fault's Show in Server Log: from the logger
	 * {@code log.xml} configures that is the logger or the nearest package of it, as log4j finds it, up through the
	 * loggers that pass their lines on to their parents, the first listed log of the first that doesn't, or of the
	 * root, {@code DMS}'s, when they all do. Never refused, and never failing: a configuration that can't be read
	 * gives none.
	 *
	 * @param logger a logger's name
	 * @return the log's name, as {@link #list} gives it; null when no log listed holds the logger's lines
	 */
	public String logOf(String logger) {
		try {
			LogConfigurator config = configuration.get();
			Set<String> listed = new HashSet<>();
			for (Log one : listed(config))
				listed.add(one.name());
			Collection<Element> loggers = config.getLoggers();
			String name = logger != null ? logger : "";
			while (true) {
				Element configured = configured(loggers, name);
				if (configured != null && "false".equalsIgnoreCase(configured.getAttributeValue("additivity")))
					return firstListed(configured, listed);
				int dot = name.lastIndexOf('.');
				if (dot < 0)
					break;
				name = name.substring(0, dot);
			}
			for (Element element : loggers)
				if (element.getName().endsWith("Root"))
					return firstListed(element, listed);
			return null;
		} catch (RuntimeException e) {
			log.warn("Can't tell which log a logger writes to: {}", e.getClass().getName());
			log.debug("Can't tell which log a logger writes to", e);
			return null;
		}
	}

	/** @return the logger the configuration has by that name; null when it has none */
	private static Element configured(Collection<Element> loggers, String name) {
		if (name.isEmpty())
			return null;
		for (Element element : loggers)
			if (element.getName().endsWith("Logger") && name.equals(element.getAttributeValue("name")))
				return element;
		return null;
	}

	/** @return the first log listed that a logger, or the root, writes to, but for the HTML copies; or null */
	private static String firstListed(Element logger, Set<String> listed) {
		for (Element child : logger.getChildren()) {
			String ref = child.getName().replace("-", "").equalsIgnoreCase("AppenderRef")
					? child.getAttributeValue("ref")
					: null;
			if (ref != null && !ref.endsWith(HTML_COPY) && listed.contains(ref))
				return ref;
		}
		return null;
	}

	/** @return the log listed with that name, compared with the names alone */
	private Optional<Log> find(String name) {
		if (name == null)
			return Optional.empty();
		return listed(configuration.get()).stream().filter(listed -> listed.name().equals(name)).findFirst();
	}

	/** @return the logs listed, as {@link #list} gives them */
	private List<Log> listed(LogConfigurator config) {
		Set<String> taskNames = new HashSet<>();
		for (Task task : tasks.getTasks())
			taskNames.add(task.getName());
		List<Log> logs = new ArrayList<>();
		for (String name : config.getAppenders()) {
			if (name == null || name.endsWith(HTML_COPY) || !NAME.matcher(name).matches())
				continue;
			String file = config.getFile(name);
			if (file == null)
				continue;
			Path current;
			try {
				current = Path.of(file);
			} catch (InvalidPathException e) {
				log.debug("The appender {} names a file that can't be a path", name);
				continue;
			}
			Log one = new Log(name, label(name, taskNames), current, rolled(config.getAppender(name), current));
			if (name.equals(MAIN) || one.size() > 0)
				logs.add(one);
		}
		logs.sort(Comparator.comparing((Log one) -> !one.name().equals(MAIN))
				.thenComparing(Log::label, String.CASE_INSENSITIVE_ORDER).thenComparing(Log::name));
		return logs;
	}

	/**
	 * @return the files beside the current file the log may have rolled over to, as many as it keeps, the oldest
	 *         first: by its strategy's {@code fileIndex}, the highest number the newest ({@code max}, log4j's default)
	 *         or the lowest ({@code min})
	 */
	private static List<Path> rolled(Element appender, Path current) {
		int max = DEFAULT_ROLLED;
		boolean newestFirst = false;
		Element strategy = appender != null ? appender.getChild("DefaultRolloverStrategy") : null;
		if (strategy != null) {
			if (strategy.getAttributeValue("max") != null) {
				try {
					max = Math.clamp(Integer.parseInt(strategy.getAttributeValue("max").strip()), 0, MAX_ROLLED);
				} catch (NumberFormatException e) {
					// log4j's own default, then
				}
			}
			newestFirst = "min".equalsIgnoreCase(strategy.getAttributeValue("fileIndex", "").strip());
		}
		List<Path> rolled = new ArrayList<>();
		for (int i = 1; i <= max; i++)
			rolled.add(current.resolveSibling(current.getFileName() + "." + (newestFirst ? max + 1 - i : i)));
		return rolled;
	}

	/** @return Main Log for {@code DMS}, the task's label for a task's log, as the Background Jobs give it, or the name */
	private static String label(String name, Set<String> taskNames) {
		if (name.equals(MAIN))
			return MAIN_LABEL;
		return taskNames.contains(name) ? Jobs.label(name) : name;
	}

	private static Instant modified(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toInstant() : null;
		} catch (NoSuchFileException e) {
			return null;
		} catch (IOException e) {
			throw new UncheckedIOException("Can't read when a log's file changed", e);
		}
	}

	/** @return the zip's name for the log's current file, at a time */
	static String zipName(Path current, Instant when) {
		String name = current.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String base = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[^A-Za-z0-9._-]", "_");
		return base + "-" + STAMP.format(when) + ".zip";
	}

	/*
	 * FilingRules reloads the user's groups, which are transient
	 */
	private static void requireAdmin(User actor) throws PersistenceException {
		Objects.requireNonNull(actor, "actor");
		if (!FilingRules.get().isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
	}
}
