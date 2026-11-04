// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * M5 spec §6.3: the worker gives the emails filed before M5 their
 * {@code emailmessageid} and their {@code hd_email} row, in batches, from a
 * cursor kept in {@code hd_setting}.
 */
public class EmailIndexWorkerTest extends AbstractHealdanTestCase {

	private static final String CURSOR = "email.index.cursor";

	private static final String DONE = "email.index";

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private Folder documents;

	private JdbcTemplate jdbc;

	@Override
	protected List<String> getDatabaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "sql/logicaldoc-webservice.sql", "data.sql");
	}

	@Before
	public void createLayout() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		admin = fx.admin();
		Folder store = fx.store("Main");
		documents = FolderDAO.get().findByName(
				FolderDAO.get().findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId()),
				"Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private static EmailIndexWorker worker() {
		return new EmailIndexWorker(Context.get(DataSource.class), HealdanSettings.get(), EmailIndex.get(),
				CacheEvictor.get());
	}

	private Document create(File source, String asName) throws Exception {
		File copy = File.createTempFile("email", "." + asName.substring(asName.lastIndexOf('.') + 1));
		try {
			Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
			Document vo = new Document();
			vo.setFileName(asName);
			vo.setFolder(documents);
			vo.setTenantId(Tenant.DEFAULT_ID);
			vo.setLanguage("en");
			return DocumentManager.get().create(copy, vo, fx.documentHistory(admin)).getDocument();
		} finally {
			copy.delete();
		}
	}

	private Document file(String resource, String asName) throws Exception {
		Document created = create(new File("src/test/resources/email/" + resource), asName);
		awaitAsyncWork();
		return DocumentDAO.get().findById(created.getId(), true);
	}

	/** A small email with a Message-ID of its own, on disk */
	private static File eml(int n) throws Exception {
		File eml = File.createTempFile("generated", ".eml");
		Files.writeString(eml.toPath(),
				"From: ann@example.com\nTo: bob@example.com\nSubject: Message " + n
						+ "\nMessage-ID: <generated-" + n + "@example.com>\nDate: Tue, 14 Oct 2025 09:29:00 +0200\n"
						+ "MIME-Version: 1.0\nContent-Type: text/plain\n\nBody " + n + "\n",
				StandardCharsets.UTF_8);
		return eml;
	}

	private static void awaitAsyncWork() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 60000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				fail("Background version saves didn't finish");
			Thread.sleep(50L);
		}
	}

	/** Takes away what the listener wrote, as an email filed before M5 has it: no Message-ID, no row */
	private void asBeforeM5(Document... docs) {
		jdbc.update("delete from hd_email");
		jdbc.update("delete from ld_document_ext where ld_name = ?", HealdanConstants.ATTR_EMAILMESSAGEID);
		CacheEvictor.get().evictDocuments(java.util.Arrays.stream(docs).map(Document::getId).toList());
	}

	private void runAll(EmailIndexWorker worker) {
		int rounds = 0;
		while (worker.runOnce())
			assertTrue("It never ends", ++rounds < 1000);
	}

	private String attribute(long docId) {
		List<String> values = jdbc.queryForList(
				"select ld_stringvalue from ld_document_ext where ld_docid = ? and ld_name = ?", String.class, docId,
				HealdanConstants.ATTR_EMAILMESSAGEID);
		return values.isEmpty() ? null : values.get(0);
	}

	private int attributeRows(long docId) {
		return jdbc.queryForObject("select count(*) from ld_document_ext where ld_docid = ? and ld_name = ?",
				Integer.class, docId, HealdanConstants.ATTR_EMAILMESSAGEID);
	}

	private int rows() {
		return jdbc.queryForObject("select count(*) from hd_email", Integer.class);
	}

	private boolean threadRuns() {
		return Thread.getAllStackTraces().keySet().stream()
				.anyMatch(t -> "healdan-email-index".equals(t.getName()) && t.isAlive());
	}

	@Test
	public void testFillsExistingEmails() throws Exception {
		Document eml = file("healdan-cc.eml", "one.eml");
		Document upper = file("healdan-emoji.eml", "TWO.EML");
		Document msg = file("test.msg", "three.msg");
		Document other = fx.upload(documents, "notes.docx", "text", admin);
		asBeforeM5(eml, upper, msg);
		assertEquals(0, rows());
		assertNull(DocumentDAO.get().findById(eml.getId(), true).getValue(HealdanConstants.ATTR_EMAILMESSAGEID));
		int versions = jdbc.queryForObject("select count(*) from ld_version", Integer.class);
		long recordVersion = jdbc.queryForObject("select ld_recordversion from ld_document where ld_id = ?",
				Long.class, eml.getId());

		assertFalse(worker().runOnce());

		assertEquals("1@example.com", DocumentDAO.get().findById(eml.getId(), true).getValue(HealdanConstants.ATTR_EMAILMESSAGEID));
		assertEquals("2@example.com", DocumentDAO.get().findById(upper.getId(), true).getValue(HealdanConstants.ATTR_EMAILMESSAGEID));
		String msgId = "18293115.12305.1420447910189.JavaMail.open-xchange@srvmaox.olig.ch";
		assertEquals(msgId, DocumentDAO.get().findById(msg.getId(), true).getValue(HealdanConstants.ATTR_EMAILMESSAGEID));
		assertEquals(3, rows());
		assertEquals(List.of(eml.getId()), EmailIndex.get().copies("1@example.com"));
		assertEquals(List.of(upper.getId()), EmailIndex.get().copies("2@example.com"));
		assertEquals(List.of(msg.getId()), EmailIndex.get().copies(msgId));
		assertEquals("done", HealdanSettings.get().get(DONE));

		// Not an email: untouched
		assertEquals(0, attributeRows(other.getId()));
		// No listener ran: no new version, and the document wasn't saved again
		assertEquals(versions, jdbc.queryForObject("select count(*) from ld_version", Integer.class).intValue());
		assertEquals(recordVersion, jdbc.queryForObject("select ld_recordversion from ld_document where ld_id = ?",
				Long.class, eml.getId()).longValue());
		// The attribute is the profile's, from the template's definition
		assertEquals(1, jdbc.queryForObject("""
				select count(*) from ld_document_ext e join ld_document d on d.ld_id = e.ld_docid
				  join ld_template_ext t on t.ld_templateid = d.ld_templateid and t.ld_name = e.ld_name
				 where e.ld_docid = ? and e.ld_name = ? and e.ld_label = 'Message-ID' and e.ld_type = t.ld_type
				   and e.ld_readonly = t.ld_readonly and e.ld_hidden = t.ld_hidden and e.ld_position = t.ld_position
				   and e.ld_editor = t.ld_editor and e.ld_mandatory = t.ld_mandatory
				""", Integer.class, eml.getId(), HealdanConstants.ATTR_EMAILMESSAGEID).intValue());
	}

	@Test
	public void testSkipsWhatItCantRead() throws Exception {
		Document corrupt = file("corrupt.msg", "corrupt.msg");
		Document noId = file("healdan-badsubject.eml", "nomessageid.eml");
		Document good = file("healdan-cc.eml", "good.eml");
		asBeforeM5(corrupt, noId, good);

		runAll(worker());

		assertEquals(0, attributeRows(corrupt.getId()));
		assertEquals(0, attributeRows(noId.getId()));
		assertNull(EmailIndex.get().messageIdOf(corrupt.getId()));
		assertNull(EmailIndex.get().messageIdOf(noId.getId()));
		// It went past them
		assertEquals("1@example.com", attribute(good.getId()));
		assertEquals(1, rows());
		assertEquals("done", HealdanSettings.get().get(DONE));
		assertEquals(String.valueOf(good.getId()), HealdanSettings.get().get(CURSOR));
	}

	@Test
	public void testAFileThatIsGoneIsSkippedAndNamedByItsId() throws Exception {
		Document gone = file("healdan-cc.eml", "gone.eml");
		Document good = file("healdan-emoji.eml", "good.eml");
		asBeforeM5(gone, good);
		Store.get().delete(StoreResource.builder().docId(gone.getId()).fileVersion(gone.getFileVersion()).build());

		try (LogCapture log = LogCapture.of(EmailIndexWorker.class)) {
			runAll(worker());

			assertEquals(0, attributeRows(gone.getId()));
			assertEquals("2@example.com", attribute(good.getId()));
			assertEquals("done", HealdanSettings.get().get(DONE));
			assertTrue(log.lines().toString(), log.lines().stream()
					.anyMatch(l -> l.startsWith("WARN") && l.contains("document " + gone.getId())));
			assertTrue(log.lines().toString(), log.lines().stream()
					.noneMatch(l -> l.contains("example.com") || l.contains("gone.eml")));
		}
	}

	/**
	 * Makes emails by copying the row of a real one and storing a file of its own
	 * for each, which is far cheaper than filing them: a test of the batches
	 * needs more than {@link EmailIndexWorker#BATCH}.
	 *
	 * @return the ids of the copies, in order; copy {@code n} holds the message
	 *         {@code generated-n@example.com}
	 */
	private List<Long> copiesOf(Document model, int count) throws Exception {
		Map<String, Object> row = jdbc.queryForMap("select * from ld_document where ld_id = ?", model.getId());
		String columns = String.join(", ", row.keySet());
		String marks = String.join(", ", java.util.Collections.nCopies(row.size(), "?"));
		long next = jdbc.queryForObject("select max(ld_id) from ld_document", Long.class) + 1;
		List<Long> ids = new ArrayList<>();
		for (int n = 0; n < count; n++) {
			long id = next + n;
			row.put("LD_ID", id);
			row.put("LD_CUSTOMID", "copy-" + id);
			jdbc.update("insert into ld_document (" + columns + ") values (" + marks + ")", row.values().toArray());
			File eml = eml(n);
			try {
				Store.get().store(eml,
						StoreResource.builder().docId(id).fileVersion((String) row.get("LD_FILEVERSION")).build());
			} finally {
				eml.delete();
			}
			ids.add(id);
		}
		return ids;
	}

	@Test
	public void testResumesFromItsCursor() throws Exception {
		Document model = file("healdan-cc.eml", "model.eml");
		// BATCH + 5 emails in all: the model and its copies
		List<Long> all = new ArrayList<>(List.of(model.getId()));
		all.addAll(copiesOf(model, EmailIndexWorker.BATCH + 4));
		asBeforeM5(model);
		long lastOfBatch = all.get(EmailIndexWorker.BATCH - 1);
		long last = all.get(all.size() - 1);

		assertTrue(worker().runOnce());

		assertEquals(EmailIndexWorker.BATCH, rows());
		assertEquals(String.valueOf(lastOfBatch), HealdanSettings.get().get(CURSOR));
		assertNull(HealdanSettings.get().get(DONE));
		assertEquals(0, attributeRows(all.get(EmailIndexWorker.BATCH)));

		// A new worker, as after a restart, carries on from the cursor
		assertFalse(worker().runOnce());

		assertEquals(EmailIndexWorker.BATCH + 5, rows());
		for (long id : all)
			assertEquals(1, attributeRows(id));
		assertEquals("generated-" + (EmailIndexWorker.BATCH + 3) + "@example.com", EmailIndex.get().messageIdOf(last));
		assertEquals(String.valueOf(last), HealdanSettings.get().get(CURSOR));
		assertEquals("done", HealdanSettings.get().get(DONE));
	}

	@Test
	public void testAFullBatchThenNothingIsDone() throws Exception {
		Document model = file("healdan-cc.eml", "model.eml");
		asBeforeM5(model);
		HealdanSettings.get().set(CURSOR, String.valueOf(model.getId()));
		copiesOf(model, EmailIndexWorker.BATCH);

		assertTrue(worker().runOnce());
		assertEquals(EmailIndexWorker.BATCH, rows());
		assertNull(HealdanSettings.get().get(DONE));

		assertFalse(worker().runOnce());
		assertEquals(EmailIndexWorker.BATCH, rows());
		assertEquals("done", HealdanSettings.get().get(DONE));
	}

	@Test
	public void testLeavesDeletedDocumentsAlone() throws Exception {
		Document doc = file("healdan-cc.eml", "trashed.eml");
		DocumentDAO.get().delete(doc.getId(), fx.documentHistory(admin));
		asBeforeM5(doc);

		runAll(worker());

		// A restore checks the row (Task 4); the document itself stays in the recycle bin
		assertEquals("1@example.com", EmailIndex.get().messageIdOf(doc.getId()));
		assertEquals("1@example.com", attribute(doc.getId()));
		assertTrue(EmailIndex.get().copies("1@example.com").isEmpty());
		assertEquals(1, jdbc.queryForObject("select ld_deleted from ld_document where ld_id = ?", Integer.class,
				doc.getId()).intValue());
	}

	@Test
	public void testIndexedAlreadyUnchanged() throws Exception {
		Document doc = file("healdan-cc.eml", "indexed.eml");
		assertEquals("1@example.com", EmailIndex.get().messageIdOf(doc.getId()));
		assertEquals("1@example.com", attribute(doc.getId()));
		// The row is what Healdan trusts; the property may have been edited since
		jdbc.update("update ld_document_ext set ld_stringvalue = 'edited' where ld_docid = ? and ld_name = ?",
				doc.getId(), HealdanConstants.ATTR_EMAILMESSAGEID);
		CacheEvictor.get().evictDocuments(List.of(doc.getId()));
		Object before = jdbc.queryForMap("select * from hd_email where hd_docid = ?", doc.getId());

		runAll(worker());

		assertEquals(1, rows());
		assertEquals(before, jdbc.queryForMap("select * from hd_email where hd_docid = ?", doc.getId()));
		assertEquals("edited", attribute(doc.getId()));
		assertEquals(1, attributeRows(doc.getId()));
	}

	@Test
	public void testAnIndexedEmailWithoutTheAttributeGetsIt() throws Exception {
		Document doc = file("healdan-cc.eml", "indexed.eml");
		jdbc.update("delete from ld_document_ext where ld_name = ?", HealdanConstants.ATTR_EMAILMESSAGEID);
		CacheEvictor.get().evictDocuments(List.of(doc.getId()));

		runAll(worker());

		assertEquals("1@example.com", attribute(doc.getId()));
		assertEquals("1@example.com", EmailIndex.get().messageIdOf(doc.getId()));
		assertEquals(1, rows());
	}

	@Test
	public void testAnEmptyAttributeRowIsFilled() throws Exception {
		Document doc = file("healdan-cc.eml", "empty.eml");
		jdbc.update("delete from hd_email");
		jdbc.update("update ld_document_ext set ld_stringvalue = null where ld_docid = ? and ld_name = ?",
				doc.getId(), HealdanConstants.ATTR_EMAILMESSAGEID);
		CacheEvictor.get().evictDocuments(List.of(doc.getId()));
		assertEquals(1, attributeRows(doc.getId()));

		runAll(worker());

		assertEquals("1@example.com", attribute(doc.getId()));
		assertEquals(1, attributeRows(doc.getId()));
		assertEquals(1, rows());
	}

	@Test
	public void testLeavesShortcutsOut() throws Exception {
		Document target = file("healdan-cc.eml", "target.eml");
		// A shortcut is a document row that points to another and has no file of its own
		Document shortcut = file("healdan-emoji.eml", "shortcut.eml");
		jdbc.update("update ld_document set ld_docref = ? where ld_id = ?", target.getId(), shortcut.getId());
		asBeforeM5(target, shortcut);

		runAll(worker());

		assertEquals(List.of(target.getId()), EmailIndex.get().copies("1@example.com"));
		assertTrue(EmailIndex.get().copies("2@example.com").isEmpty());
		assertEquals(0, attributeRows(shortcut.getId()));
		assertNull(EmailIndex.get().messageIdOf(shortcut.getId()));
		assertEquals(1, rows());
	}

	@Test
	public void testDoneMeansNothingMoreToDo() throws Exception {
		Document doc = file("healdan-cc.eml", "one.eml");
		asBeforeM5(doc);
		HealdanSettings.get().set(DONE, "done");

		assertFalse(worker().runOnce());

		assertEquals(0, rows());
		assertEquals(0, attributeRows(doc.getId()));
	}

	@Test
	public void testDoneMeansIdle() {
		HealdanSettings.get().set(DONE, "done");
		EmailIndexWorker worker = worker();

		worker.start();
		try {
			assertFalse(threadRuns());
		} finally {
			worker.stop();
		}
	}

	@Test
	public void testStartsAThreadUntilItsDone() {
		EmailIndexWorker worker = worker();

		worker.start();
		try {
			assertTrue(threadRuns());
			// A second start leaves the thread as it is
			worker.start();
			assertEquals(1, Thread.getAllStackTraces().keySet().stream()
					.filter(t -> "healdan-email-index".equals(t.getName()) && t.isAlive()).count());
		} finally {
			worker.stop();
		}
		assertFalse(threadRuns());
	}
}
