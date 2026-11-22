// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import static com.logicaldoc.healdan.admin.logs.LogTestHooks.append;
import static com.logicaldoc.healdan.admin.logs.LogTestHooks.entry;
import static com.logicaldoc.healdan.admin.logs.LogTestHooks.modified;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.logs.LogQuery.Level;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;

/**
 * The server's logs (Admin app spec section 8.8; rulings P5-R2, P5-R5, P5-R6 and P5-R27), read from files of the
 * tests' own, which the test {@code log.xml} names: which logs are listed, a log's current file and the files it
 * rolled over to read as one, back and forward, entries joined from their lines, the caps on a read, a name taken
 * only as one of the logs' names, the download, and which log a logger writes to.
 */
public class LogFilesTest extends AbstractHealdanTestCase {

	private static final String SOURCE = "com.logicaldoc.healdan.preview.PreviewWorker";

	private final HealdanFixtures fx = new HealdanFixtures();

	private LogFiles files;

	private User admin;

	private Path folder;

	private Path dms;

	/**
	 * The time of the first entry the tests write, each later one a millisecond after the one before: in no zone's
	 * change of its clocks, as the log writes its times in the server's zone
	 */
	private final Instant start = Instant.parse("2026-11-20T09:30:00.250Z");

	private int written;

	@Before
	public void prepare() throws Exception {
		files = LogFiles.get();
		admin = fx.admin();
		folder = LogTestHooks.useNewFolder();
		dms = folder.resolve("dms.log");
	}

	@After
	public void putBack() throws IOException {
		LogTestHooks.reset();
	}

	@Test
	public void testTheBean() {
		assertSame(files, LogFiles.get());
	}

	@Test
	public void testListGivesTheMainLogFirstThenTheOthersThatHoldAnythingByLabel() throws Exception {
		append(dms, line("INFO", "Started"));
		append(folder.resolve("dms.log.1"), line("INFO", "Older"));
		append(folder.resolve("indexertask.log"), line("INFO", "Indexed"));
		// A current file that's empty, and a file it rolled over to that isn't
		Files.createFile(folder.resolve("cmis.log"));
		append(folder.resolve("cmis.log.2"), line("WARN", "Slow"));
		append(folder.resolve("spring.log"), line("INFO", "Security"));
		// Not listed: an empty log, an HTML copy, and a name that's only dots
		Files.createFile(folder.resolve("notifier.log"));
		append(folder.resolve("dms_web.log.html"), "<p>Started</p>");
		append(folder.resolve("dots.log"), line("INFO", "Dots"));
		Instant changed = Instant.parse("2026-11-20T10:15:30Z");
		modified(dms, changed);

		List<LogInfo> logs = files.list(admin);

		assertEquals(List.of("DMS", "CMIS", "IndexerTask", "SPRING"), logs.stream().map(LogInfo::name).toList());
		// The main log's label, a task's log by its job's label, and any other by its name
		assertEquals(List.of("Main Log", "CMIS", "Indexer", "SPRING"), logs.stream().map(LogInfo::label).toList());
		// The size of all its files together, and when the current one changed
		assertEquals(Files.size(dms) + Files.size(folder.resolve("dms.log.1")), logs.get(0).size());
		assertEquals(changed, logs.get(0).modified());
		assertEquals(Files.size(folder.resolve("cmis.log.2")), logs.get(1).size());
	}

	@Test
	public void testTheMainLogIsListedWithNoFile() throws Exception {
		assertEquals(List.of(new LogInfo("DMS", "Main Log", 0, null)), files.list(admin));
	}

	@Test
	public void testOnlyAnAdministrator() throws Exception {
		append(dms, line("INFO", "Started"));
		User clerk = fx.user("clerk");

		for (ThrowingRunnable call : List.<ThrowingRunnable>of(() -> files.list(clerk),
				() -> files.read("DMS", query(null, null), clerk), () -> files.read("nope", query(null, null), clerk),
				() -> files.zip("DMS", clerk)))
			assertEquals(LogFiles.ONLY_ADMINS, assertThrows(HealdanRuleException.class, call::run).getMessage());
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	@Test
	public void testANameIsOnlyOneOfTheLogsNames() throws Exception {
		append(dms, line("INFO", "Started"));
		append(folder.resolve("dms_web.log.html"), "<p>Started</p>");
		append(folder.resolve("dots.log"), line("INFO", "Dots"));
		Files.createFile(folder.resolve("notifier.log"));
		Path outside = folder.resolve("secret.txt");
		append(outside, "secret\n");

		for (String name : List.of("../dms.log", "..", "./DMS", "dms.log", "dms", "DMS ", "DMS\u0000",
				dms.toString(), outside.toString(), "../secret.txt", "..%2Fdms.log", "DMS_WEB", "Notifier", "console",
				"nope", ""))
			assertTrue(name, files.read(name, query(null, null), admin).isEmpty());
		assertTrue(files.read(null, query(null, null), admin).isEmpty());
		assertTrue(files.zip("../dms.log", admin).isEmpty());
		assertTrue(files.zip(dms.toString(), admin).isEmpty());
		assertTrue(files.read("DMS", query(null, null), admin).isPresent());
	}

	@Test
	public void testTheNewestEntriesThenTheOnesBefore() throws Exception {
		List<String> messages = writeEntries(dms, 250, "INFO");

		LogLines newest = read(query(null, null));

		assertEquals(messages.subList(50, 250), messages(newest));
		assertFalse(newest.atStart());
		assertTrue(newest.atEnd());
		assertFalse(newest.rolled());
		assertEquals(newest.lines().get(0).position(), newest.before());

		LogLines older = read(query(newest.before(), null));
		assertEquals(messages.subList(0, 50), messages(older));
		assertTrue(older.atStart());
		assertFalse(older.atEnd());
		assertEquals(newest.before(), older.after());
		assertEquals(older.lines().get(0).position(), older.before());

		// Each entry as the log wrote it, its time to the millisecond and its source without spaces
		LogLine first = older.lines().get(0);
		assertEquals(start, first.time());
		assertEquals("INFO", first.level());
		assertEquals(SOURCE + ":12", first.source());
		assertEquals(List.of(), first.continuation());
	}

	@Test
	public void testForwardOverMoreThan200Entries() throws Exception {
		List<String> messages = writeEntries(dms, 250, "INFO");

		LogLines first = read(query(null, LogPosition.currentStart()));
		assertEquals(messages.subList(0, 200), messages(first));
		assertFalse("stopped at 200", first.atEnd());
		LogLines second = read(query(null, first.after()));
		assertEquals(messages.subList(200, 250), messages(second));
		assertTrue(second.atEnd());
		// Every entry once, none twice: the 201st starts where the first read ended
		assertEquals(first.after(), second.lines().get(0).position());
		LogLines none = read(query(null, second.after()));
		assertEquals(List.of(), none.lines());
		assertTrue(none.atEnd());
		assertEquals(second.after(), none.after());
	}

	@Test
	public void testForwardOverMoreThan200EntriesWithALevel() throws Exception {
		List<String> warnings = new ArrayList<>();
		for (int i = 0; i < 500; i++) {
			String level = i % 2 == 0 ? "INFO" : "WARN";
			String message = "Entry " + i;
			append(dms, line(level, message));
			if (level.equals("WARN"))
				warnings.add(message);
		}

		LogLines first = read(new LogQuery(null, LogPosition.currentStart(), Level.WARN, null));
		assertEquals(warnings.subList(0, 200), messages(first));
		LogLines second = read(new LogQuery(null, first.after(), Level.WARN, null));
		assertEquals(warnings.subList(200, 250), messages(second));
		assertTrue(second.atEnd());
		// After the 200th warning, the next entry, which isn't one
		assertEquals("Entry 400", read(query(null, first.after())).lines().get(0).message());
	}

	@Test
	public void testAfterGivesOnlyWhatsNew() throws Exception {
		writeEntries(dms, 3, "INFO");
		LogLines newest = read(query(null, null));

		append(dms, line("WARN", "Something new"));
		LogLines followed = read(query(null, newest.after()));

		assertEquals(List.of("Something new"), messages(followed));
		assertTrue(followed.atEnd());
		assertFalse(followed.rolled());
		assertEquals(List.of(), read(query(null, followed.after())).lines());
	}

	@Test
	public void testALastLineNotYetEndedIsLeftForTheNextRead() throws Exception {
		writeEntries(dms, 2, "INFO");
		String half = line("INFO", "Half written");
		append(dms, half.substring(0, 20));

		LogLines newest = read(query(null, null));
		assertEquals(List.of("Entry 0", "Entry 1"), messages(newest));
		assertTrue(newest.atEnd());

		append(dms, half.substring(20));
		assertEquals(List.of("Half written"), messages(read(query(null, newest.after()))));
	}

	@Test
	public void testAStacksLinesJoinTheirEntry() throws Exception {
		append(dms, line("ERROR", "The preview failed"));
		append(dms, "java.lang.IllegalStateException: no converter\n\tat a.B.c(B.java:12)\r\n\n");
		append(dms, line("INFO", "Next"));

		for (LogLines lines : List.of(read(query(null, null)), read(query(null, LogPosition.currentStart())))) {
			assertEquals(List.of("The preview failed", "Next"), messages(lines));
			assertEquals(List.of("java.lang.IllegalStateException: no converter", "\tat a.B.c(B.java:12)", ""),
					lines.lines().get(0).continuation());
		}
	}

	@Test
	public void testLinesThatStartNoEntryAtTheStartOfWhatWasRead() throws Exception {
		// At the start of the oldest file, the rest of a stack whose entry isn't in the log
		append(dms, "\tat a.B.c(B.java:12)\nCaused by: x\n");
		append(dms, line("ERROR", "It failed"));
		append(dms, "java.lang.IllegalStateException\n");

		LogLines all = read(query(null, null));
		assertEquals(List.of("\tat a.B.c(B.java:12)", "Caused by: x", "It failed"), messages(all));
		assertNull(all.lines().get(0).time());
		assertNull(all.lines().get(0).level());
		assertNull(all.lines().get(0).source());
		assertEquals(List.of(), all.lines().get(1).continuation());
		assertEquals(List.of("java.lang.IllegalStateException"), all.lines().get(2).continuation());
		assertTrue(all.atStart());

		// Forward, the rest of a stack an earlier read ended in while it was still being written
		append(dms, "\tat a.B.c(B.java:13)\n\tat a.B.d(B.java:14)\n");
		append(dms, line("WARN", "Next"));
		LogLines rest = read(query(null, all.after()));
		assertEquals(List.of("\tat a.B.c(B.java:13)", "\tat a.B.d(B.java:14)", "Next"), messages(rest));
		assertNull(rest.lines().get(0).level());
		assertEquals(List.of(), rest.lines().get(0).continuation());
		assertEquals(List.of(), rest.lines().get(2).continuation());
		// A line of its own has no level, so only every level keeps it
		assertEquals(List.of("Next"), messages(read(new LogQuery(null, all.after(), Level.WARN, null))));
	}

	@Test
	public void testLevelAndTextFilters() throws Exception {
		append(dms, line("DEBUG", "Debugging"));
		append(dms, line("INFO", "Indexed document 5"));
		append(dms, line("WARN", "Slow conversion"));
		append(dms, line("ERROR", "The preview failed"));
		append(dms, "java.lang.IllegalStateException: no CONVERTER\n");
		append(dms, line("FATAL", "Out of memory"));

		assertEquals(List.of("Slow conversion", "The preview failed", "Out of memory"),
				messages(read(new LogQuery(null, null, Level.WARN, null))));
		assertEquals(List.of("The preview failed", "Out of memory"),
				messages(read(new LogQuery(null, null, Level.ERROR, null))));
		// In any case, in the message, the source or a continuation line, the whitespace at either end left out
		assertEquals(List.of("Indexed document 5"), messages(read(new LogQuery(null, null, Level.ALL, " DOCUMENT "))));
		assertEquals(List.of("The preview failed"), messages(read(new LogQuery(null, null, Level.ALL, "converter"))));
		assertEquals(5, messages(read(new LogQuery(null, null, Level.ALL, "previewworker:12"))).size());
		assertEquals(List.of(), messages(read(new LogQuery(null, null, Level.ERROR, "slow"))));
		assertEquals(5, read(new LogQuery(null, null, Level.ALL, "  ")).lines().size());
	}

	@Test
	public void testALongLineIsCutAndTheReadGoesOn() throws Exception {
		append(dms, line("INFO", "Before"));
		append(dms, line("ERROR", "x".repeat(1024 * 1024)));
		append(dms, "y".repeat(1024 * 1024) + "\n");
		append(dms, line("INFO", "After"));

		for (LogLines lines : List.of(read(query(null, null)), read(query(null, LogPosition.currentStart())))) {
			assertEquals(List.of("Before", "x".repeat(LogFiles.MAX_CHARS), "After"), messages(lines));
			assertEquals(List.of("y".repeat(LogFiles.MAX_CHARS)), lines.lines().get(1).continuation());
			assertEquals("ERROR", lines.lines().get(1).level());
		}
	}

	@Test
	public void testCredentialsInAUrlAreTakenOut() throws Exception {
		append(dms, line("ERROR", "Can't reach https://alice:s3cret@backup.example/files for bob@example.com"));
		append(dms, "Caused by: ftp://bob:pa55@ftp.example/x\n");

		LogLine entry = read(query(null, null)).lines().get(0);

		assertEquals("Can't reach https://backup.example/files for bob@example.com", entry.message());
		assertEquals(List.of("Caused by: ftp://ftp.example/x"), entry.continuation());
		// And the text is looked for in what's answered
		assertEquals(List.of(), read(new LogQuery(null, null, Level.ALL, "s3cret")).lines());
	}

	@Test
	public void testAReadLooksThroughAtMost5MiB() throws Exception {
		// An entry whose stack is longer than a read looks through, and an entry after it
		String long40k = "z".repeat(40_000) + "\n";
		append(dms, line("ERROR", "The first"));
		append(dms, long40k.repeat(160));
		append(dms, line("INFO", "The second"));
		long size = Files.size(dms);
		assertTrue(size > LogFiles.MAX_SCAN_BYTES);

		// Back: the second, and none of the first's lines, which aren't its own
		LogLines newest = read(query(null, null));
		assertEquals(List.of("The second"), messages(newest));
		assertFalse(newest.atStart());
		// Then, with no entry's start in 5 MiB, the lines a line each
		LogLines lines = read(query(newest.before(), null));
		assertTrue(lines.lines().size() > 100 && lines.lines().size() < 160);
		assertTrue(lines.lines().stream().allMatch(l -> l.level() == null && l.message().length() == LogFiles.MAX_CHARS));
		assertEquals(lines.lines().get(0).position(), lines.before());
		LogLines rest = read(query(lines.before(), null));
		assertEquals(List.of("The first"), messages(rest));
		assertEquals(160 - lines.lines().size(), rest.lines().get(0).continuation().size());
		assertTrue(rest.atStart());

		// Forward: it stops after a whole line once it has looked through 5 MiB, and the next read goes on
		LogLines forward = read(query(null, LogPosition.currentStart()));
		assertEquals(List.of("The first"), messages(forward));
		int first = forward.lines().get(0).continuation().size();
		assertTrue(first > 100 && first < 160);
		assertFalse(forward.atEnd());
		LogLines more = read(query(null, forward.after()));
		assertEquals(160 - first + 1, more.lines().size());
		assertEquals("The second", more.lines().get(more.lines().size() - 1).message());
		assertTrue(more.atEnd());
	}

	@Test
	public void testAReadLooksAtABoundedNumberOfLines() throws Exception {
		// An entry with a stack of more short lines than a read looks at, and an entry after it
		append(dms, line("ERROR", "The first"));
		append(dms, "x\n".repeat(LogReader.MAX_LINES_LOOKED + 10_000));
		append(dms, line("INFO", "The second"));

		LogLines forward = read(query(null, LogPosition.currentStart()));
		assertEquals(List.of("The first"), messages(forward));
		assertEquals(LogReader.MAX_LINES_LOOKED - 1, forward.lines().get(0).continuation().size());
		assertFalse(forward.atEnd());
		LogLines more = read(query(null, forward.after()));
		assertEquals(LogFiles.MAX_LINES, more.lines().size());
		assertEquals(forward.after(), more.lines().get(0).position());

		LogLines newest = read(query(null, null));
		assertEquals(List.of("The second"), messages(newest));
		assertFalse(newest.atStart());
		// No entry's start among the lines it looked at: they're a line each
		LogLines lines = read(query(newest.before(), null));
		assertEquals(LogFiles.MAX_LINES, lines.lines().size());
		assertTrue(lines.lines().stream().allMatch(l -> l.level() == null && l.message().equals("x")));
	}

	@Test
	public void testBackAcrossTheFilesItRolledOverTo() throws Exception {
		Path older = folder.resolve("dms.log.1");
		List<String> old = writeEntries(older, 150, "INFO");
		modified(older, start.plusSeconds(60));
		List<String> current = writeEntries(dms, 100, "INFO");
		List<String> all = new ArrayList<>(old);
		all.addAll(current);

		LogLines newest = read(query(null, null));
		assertEquals(all.subList(50, 250), messages(newest));
		assertFalse(newest.atStart());
		LogLines oldest = read(query(newest.before(), null));
		assertEquals(all.subList(0, 50), messages(oldest));
		assertTrue(oldest.atStart());

		// Each entry's position is its own, though two files have entries at the same offsets
		Set<LogPosition> positions = new HashSet<>();
		newest.lines().forEach(l -> positions.add(l.position()));
		oldest.lines().forEach(l -> positions.add(l.position()));
		assertEquals(250, positions.size());

		// A read back that ends at a file's start goes on in the older one next
		LogLines atFileStart = read(query(newest.lines().get(100).position(), null));
		assertEquals(all.subList(0, 150), messages(atFileStart));
	}

	@Test
	public void testTheFilesItRolledOverToByWhenTheyChanged() throws Exception {
		// Rolled over to with the newest at 1, as a fixed window would
		Path newer = folder.resolve("dms.log.1");
		Path older = folder.resolve("dms.log.2");
		append(older, line("INFO", "Oldest"));
		append(newer, line("INFO", "Older"));
		append(dms, line("INFO", "Newest"));
		modified(older, start.plusSeconds(10));
		modified(newer, start.plusSeconds(20));

		assertEquals(List.of("Oldest", "Older", "Newest"), messages(read(query(null, null))));
	}

	@Test
	public void testFollowingAcrossARollOver() throws Exception {
		writeEntries(dms, 10, "INFO");
		LogLines newest = read(query(null, null));
		append(dms, line("INFO", "Written after"));
		LogLines followed = read(query(null, newest.after()));
		assertEquals(List.of("Written after"), messages(followed));

		// The log rolls over: what was written to the old file after the position isn't given again here
		append(dms, line("INFO", "Written last to the old file"));
		Path rolled = folder.resolve("dms.log.1");
		Files.move(dms, rolled);
		append(dms, line("INFO", "New 1"));
		append(dms, line("INFO", "New 2"));

		LogLines after = read(query(null, followed.after()));
		assertTrue(after.rolled());
		assertEquals(List.of("New 1", "New 2"), messages(after));
		assertTrue(after.atEnd());
		// Paging back reaches it
		LogLines back = read(query(after.before(), null));
		assertFalse(back.rolled());
		assertEquals("Written last to the old file", back.lines().get(back.lines().size() - 1).message());
		assertEquals(12, back.lines().size());
		assertTrue(back.atStart());
		// And following goes on in the new file
		append(dms, line("INFO", "New 3"));
		LogLines next = read(query(null, after.after()));
		assertFalse(next.rolled());
		assertEquals(List.of("New 3"), messages(next));
	}

	@Test
	public void testBackFromAFileThatRolledOverIsReadUnderItsNewName() throws Exception {
		writeEntries(dms, 5, "INFO");
		LogLines newest = read(query(null, null));
		LogPosition third = newest.lines().get(2).position();
		Files.move(dms, folder.resolve("dms.log.1"));
		append(dms, line("INFO", "New"));

		LogLines back = read(query(third, null));

		assertEquals(List.of("Entry 0", "Entry 1"), messages(back));
		assertFalse(back.rolled());
		assertTrue(back.atStart());
	}

	@Test
	public void testBackFromAFileThatsGone() throws Exception {
		Path rolled = folder.resolve("dms.log.1");
		writeEntries(rolled, 5, "INFO");
		modified(rolled, start);
		append(dms, line("INFO", "Current"));
		LogPosition inRolled = read(query(null, null)).lines().get(2).position();
		Files.delete(rolled);

		LogLines gone = read(query(inRolled, null));

		assertEquals(List.of(), gone.lines());
		assertTrue(gone.atStart());
		// Followed from, it's a file that isn't the current one
		LogLines followed = read(query(null, inRolled));
		assertTrue(followed.rolled());
		assertEquals(List.of("Current"), messages(followed));
	}

	@Test
	public void testALogWithNoFile() throws Exception {
		LogLines none = read(query(null, null));

		assertEquals(List.of(), none.lines());
		assertTrue(none.atStart());
		assertTrue(none.atEnd());
		assertFalse(none.rolled());
		assertEquals(LogPosition.currentStart(), none.before());
		assertEquals(LogPosition.currentStart(), none.after());
		assertEquals(List.of(), read(query(none.before(), null)).lines());
		assertEquals(List.of(), read(query(null, none.after())).lines());
	}

	@Test
	public void testACurrentFileWithNoWholeLineYet() throws Exception {
		String first = line("INFO", "First");
		append(dms, first.substring(0, 10));

		LogLines none = read(query(null, null));
		assertEquals(List.of(), none.lines());
		assertEquals(LogPosition.currentStart(), none.after());

		append(dms, first.substring(10));
		append(dms, line("INFO", "Second"));
		LogLines followed = read(query(null, none.after()));
		assertFalse(followed.rolled());
		assertEquals(List.of("First", "Second"), messages(followed));
	}

	@Test
	public void testAPositionsFileIsFoundByWhatItHoldsNotItsName() throws Exception {
		writeEntries(dms, 3, "INFO");
		LogPosition position = read(query(null, null)).lines().get(1).position();

		// Another file at the same offsets is another file
		Files.delete(dms);
		append(dms, line("INFO", "Another"));
		append(dms, line("INFO", "Another 2"));
		append(dms, line("INFO", "Another 3"));
		LogLines followed = read(query(null, position));
		assertTrue(followed.rolled());
		assertEquals(List.of("Another", "Another 2", "Another 3"), messages(followed));
		assertNotEquals(position.identity(), followed.lines().get(0).position().identity());
	}

	@Test
	public void testAFileThatCantBeReadFails() throws Exception {
		append(dms, line("INFO", "Started"));
		LogTestHooks.refuseToOpen(dms);

		UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> read(query(null, null)));
		assertTrue(failure.getCause() instanceof AccessDeniedException);
		assertThrows(UncheckedIOException.class, () -> files.zip("DMS", admin));
	}

	@Test
	public void testZip() throws Exception {
		Path newer = folder.resolve("dms.log.2");
		Path older = folder.resolve("dms.log.1");
		append(older, line("INFO", "Older https://alice:s3cret@backup.example/"));
		append(newer, line("INFO", "Newer"));
		append(dms, line("INFO", "Current"));
		modified(older, start.plusSeconds(10));
		modified(newer, start.plusSeconds(20));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (LogFiles.LogZip zip = files.zip("DMS", admin).orElseThrow()) {
			assertTrue(zip.fileName(), zip.fileName().matches("dms-\\d{8}-\\d{6}\\.zip"));
			zip.writeTo(out);
		}

		// Each file as it is, under its own name, the current one first, then the newest
		Map<String, String> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry())
				entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		assertEquals(List.of("dms.log", "dms.log.2", "dms.log.1"), List.copyOf(entries.keySet()));
		assertEquals(Files.readString(dms), entries.get("dms.log"));
		assertEquals(Files.readString(older), entries.get("dms.log.1"));
		assertTrue(entries.get("dms.log.1").contains("alice:s3cret@"));
	}

	@Test
	public void testZipName() {
		assertEquals("dms-20261122-104107.zip",
				LogFiles.zipName(Path.of("logs/dms.log"), Instant.parse("2026-11-22T10:41:07.900Z")));
		assertEquals("console-20261122-104107.zip",
				LogFiles.zipName(Path.of("console"), Instant.parse("2026-11-22T10:41:07Z")));
	}

	@Test
	public void testTheLogALoggerWritesTo() throws Exception {
		append(dms, line("INFO", "Started"));
		append(folder.resolve("cmis.log"), line("WARN", "Slow"));
		append(folder.resolve("spring.log"), line("INFO", "Security"));
		Files.createFile(folder.resolve("notifier.log"));

		// The root's, the main log
		assertEquals("DMS", files.logOf(SOURCE));
		assertEquals("DMS", files.logOf("com.logicaldoc.core.document.DocumentManager"));
		assertEquals("DMS", files.logOf(null));
		// A logger that passes its lines on, though it has a log of its own too
		assertEquals("DMS", files.logOf("org.springframework.security.web.FilterChainProxy"));
		// One that doesn't, its first log listed that isn't an HTML copy, and the loggers in it
		assertEquals("CMIS", files.logOf("com.logicaldoc.cmis"));
		assertEquals("CMIS", files.logOf("com.logicaldoc.cmis.LDRepository"));
		assertEquals("CMIS", files.logOf("com.logicaldoc.cmis.CmisServlet"));
		// Its log not listed, as its files hold nothing: none
		assertNull(files.logOf("com.logicaldoc.core.searchengine.indexer.IndexerTask"));
		assertNull(files.logOf("com.logicaldoc.notifier.Notifier"));
		// A name the logger's starts with that isn't a package of it
		assertEquals("DMS", files.logOf("com.logicaldoc.cmisx.Other"));

		append(folder.resolve("indexertask.log"), line("INFO", "Indexed"));
		assertEquals("IndexerTask", files.logOf("com.logicaldoc.core.searchengine.indexer.IndexerTask"));
	}

	@Test
	public void testTheLogALoggerWritesToWhenTheConfigurationCantBeRead() throws Exception {
		LogTestHooks.useConfiguration(() -> {
			throw new IllegalStateException("No log.xml");
		});

		try (LogCapture capture = LogCapture.of(LogFiles.class)) {
			assertNull(files.logOf(SOURCE));
			assertEquals(List.of("WARN Can't tell which log a logger writes to: java.lang.IllegalStateException"),
					capture.lines());
		}
	}

	private String line(String level, String message) {
		return entry(start.plusMillis(written++), level, SOURCE, 12, message);
	}

	/** Writes entries "Entry 0" and on, at a level, and gives their messages */
	private List<String> writeEntries(Path file, int count, String level) throws IOException {
		List<String> messages = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < count; i++) {
			messages.add("Entry " + i);
			text.append(line(level, "Entry " + i));
		}
		append(file, text.toString());
		return messages;
	}

	private LogLines read(LogQuery query) throws Exception {
		return files.read("DMS", query, admin).orElseThrow();
	}

	private static LogQuery query(LogPosition before, LogPosition after) {
		return new LogQuery(before, after, Level.ALL, null);
	}

	private static List<String> messages(LogLines lines) {
		return lines.lines().stream().map(LogLine::message).toList();
	}
}
