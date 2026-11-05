// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.spring.Context;

/**
 * M5 spec §6.2 and §12: every new email's Message-ID is in {@code hd_email}, and
 * a lookup answers the documents not deleted that carry exactly that
 * Message-ID.
 */
public class EmailIndexTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private Folder documents;

	/** The Documents of matter 998.2: the rule lets a matter hold a message once */
	private Folder otherDocuments;

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
		Client client = fx.client(998, "Healdan");
		documents = FolderDAO.get().findByName(
				FolderDAO.get().findById(fx.matter(client, 1, "Demo", store, "Documents").getFolderId()),
				"Documents", Tenant.DEFAULT_ID, true).get(0);
		otherDocuments = FolderDAO.get().findByName(
				FolderDAO.get().findById(fx.matter(client, 2, "Other", store, "Documents").getFolderId()),
				"Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private Document file(String resource, String asName) throws Exception {
		return file(resource, asName, documents);
	}

	private Document file(String resource, String asName, Folder folder) throws Exception {
		File copy = File.createTempFile("email", "." + asName.substring(asName.lastIndexOf('.') + 1));
		try {
			Files.copy(new File("src/test/resources/email/" + resource).toPath(), copy.toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			Document vo = new Document();
			vo.setFileName(asName);
			vo.setFolder(folder);
			vo.setTenantId(Tenant.DEFAULT_ID);
			vo.setLanguage("en");
			Document created = DocumentManager.get().create(copy, vo, fx.documentHistory(admin)).getDocument();
			awaitAsyncWork();
			return DocumentDAO.get().findById(created.getId(), true);
		} finally {
			copy.delete();
		}
	}

	private static void awaitAsyncWork() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 20000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				fail("Background version saves didn't finish");
			Thread.sleep(50L);
		}
	}

	private int rows() {
		return jdbc.queryForObject("select count(*) from hd_email", Integer.class);
	}

	@Test
	public void testANewEmailIsIndexed() throws Exception {
		Document doc = file("healdan-cc.eml", "upload.eml");

		assertEquals(1, rows());
		Map<String, Object> row = jdbc.queryForMap("select * from hd_email");
		assertEquals(doc.getId(), ((Number) row.get("HD_DOCID")).longValue());
		assertEquals("1@example.com", row.get("HD_MESSAGEID"));
		assertEquals(MessageIds.hash("1@example.com"), row.get("HD_MESSAGEIDHASH"));
		assertEquals(List.of(doc.getId()), EmailIndex.get().copies("1@example.com"));
		assertEquals("1@example.com", EmailIndex.get().messageIdOf(doc.getId()));
		// Compared exactly, case included
		assertTrue(EmailIndex.get().copies("1@EXAMPLE.COM").isEmpty());
	}

	@Test
	public void testNotAnEmailNoRow() throws Exception {
		Document docx = fx.upload(documents, "a.docx", "text", admin);
		Document noId = file("healdan-badsubject.eml", "upload.eml");
		awaitAsyncWork();

		assertEquals(0, rows());
		assertNull(EmailIndex.get().messageIdOf(docx.getId()));
		assertNull(EmailIndex.get().messageIdOf(noId.getId()));
	}

	@Test
	public void testAnEmailStoredAgainKeepsItsOneRow() throws Exception {
		Document doc = file("healdan-cc.eml", "upload.eml");

		// A new version of the document, saved through the DAO, doesn't add a row
		doc.setComment("again");
		DocumentDAO.get().store(doc, fx.documentHistory(admin));
		awaitAsyncWork();

		assertEquals(1, rows());
		assertEquals(List.of(doc.getId()), EmailIndex.get().copies("1@example.com"));
	}

	@Test
	public void testDeletedCopiesLeftOut() throws Exception {
		Document doc = file("healdan-cc.eml", "upload.eml");
		assertEquals(List.of(doc.getId()), EmailIndex.get().copies("1@example.com"));

		DocumentDAO.get().delete(doc.getId(), fx.documentHistory(admin));

		assertTrue(EmailIndex.get().copies("1@example.com").isEmpty());
		assertEquals("1@example.com", EmailIndex.get().messageIdOf(doc.getId()));
	}

	@Test
	public void testGoneForGoodLeftOut() {
		EmailIndex.get().put(987654321L, "ghost@example.com");

		assertEquals(1, rows());
		assertTrue(EmailIndex.get().copies("ghost@example.com").isEmpty());
		assertEquals("ghost@example.com", EmailIndex.get().messageIdOf(987654321L));
	}

	@Test
	public void testManyAtOnce() throws Exception {
		Document first = file("healdan-cc.eml", "one.eml");
		Document second = file("healdan-emoji.eml", "two.eml");
		Document third = file("healdan-emoji.eml", "three.eml", otherDocuments);
		assertEquals("2@example.com", EmailIndex.get().messageIdOf(second.getId()));

		Map<String, List<Long>> copies = EmailIndex.get().copies(List.of("2@example.com", "1@example.com", "missing@example.com"));

		assertEquals(List.of("2@example.com", "1@example.com", "missing@example.com"), new ArrayList<>(copies.keySet()));
		assertEquals(List.of(second.getId(), third.getId()), copies.get("2@example.com"));
		assertEquals(List.of(first.getId()), copies.get("1@example.com"));
		assertEquals(List.of(), copies.get("missing@example.com"));
	}

	@Test
	public void testAskedWithBracketsAnswersUnderTheNormalizedId() throws Exception {
		Document doc = file("healdan-cc.eml", "upload.eml");

		Map<String, List<Long>> copies = EmailIndex.get().copies(Arrays.asList("<1@example.com>", " ", null));

		assertEquals(List.of("1@example.com"), new ArrayList<>(copies.keySet()));
		assertEquals(List.of(doc.getId()), copies.get("1@example.com"));
	}

	@Test
	public void testManyMoreThanOneQueryHolds() throws Exception {
		Document doc = file("healdan-cc.eml", "upload.eml");
		List<String> asked = new ArrayList<>();
		for (int i = 0; i < 250; i++)
			asked.add(i == 230 ? "1@example.com" : "none-" + i + "@example.com");

		Map<String, List<Long>> copies = EmailIndex.get().copies(asked);

		assertEquals(asked, new ArrayList<>(copies.keySet()));
		assertEquals(List.of(doc.getId()), copies.get("1@example.com"));
		assertEquals(1, copies.values().stream().filter(l -> !l.isEmpty()).count());
	}

	@Test
	public void testNothingToAskAboutAnswersNothing() {
		assertTrue(EmailIndex.get().copies((String) null).isEmpty());
		assertTrue(EmailIndex.get().copies("").isEmpty());
		assertTrue(EmailIndex.get().copies("x".repeat(MessageIds.MAX_LENGTH + 1)).isEmpty());
		assertTrue(EmailIndex.get().copies(List.of()).isEmpty());
	}

	@Test
	public void testSameHashDifferentIds() throws Exception {
		Document first = file("healdan-cc.eml", "one.eml");
		Document second = file("healdan-emoji.eml", "two.eml");

		// A hash collision, made by hand: the second message's row gets the first's hash
		jdbc.update("update hd_email set hd_messageidhash = ? where hd_docid = ?", MessageIds.hash("1@example.com"),
				second.getId());
		assertEquals(2, jdbc.queryForObject("select count(*) from hd_email where hd_messageidhash = ?", Integer.class,
				MessageIds.hash("1@example.com")).intValue());

		// The exact Message-ID still decides, whether one is asked or several
		assertEquals(List.of(first.getId()), EmailIndex.get().copies("1@example.com"));
		assertEquals(List.of(first.getId()), EmailIndex.get().copies(List.of("1@example.com")).get("1@example.com"));
	}

	@Test
	public void testACopyOfAnEmailIsIndexedToo() throws Exception {
		Document original = file("healdan-cc.eml", "upload.eml");
		Folder elsewhere = fx.folder(otherDocuments, "Elsewhere");

		Document copy = DocumentManager.get().copyToFolder(original, elsewhere, fx.documentHistory(admin), true, true, true)
				.getDocument();
		awaitAsyncWork();

		assertEquals(List.of(original.getId(), copy.getId()), EmailIndex.get().copies("1@example.com"));
		assertEquals("1@example.com", EmailIndex.get().messageIdOf(copy.getId()));
	}

	@Test
	public void testAnIndexFailureNeverRefusesTheEmail() throws Exception {
		// The rows can be read, as the once-per-matter rule does, but none can be added
		jdbc.execute("alter table hd_email add constraint hd_email_refuses check (hd_docid < 0)");
		try (LogCapture log = LogCapture.of(EmailDocumentListener.class)) {
			Document doc = file("healdan-cc.eml", "upload.eml");

			assertTrue(doc.getId() != 0L);
			assertEquals("1@example.com", doc.getValue(HealdanConstants.ATTR_EMAILMESSAGEID));
			// The warning names the document, never the Message-ID
			assertTrue(log.lines().toString(), log.lines().stream()
					.anyMatch(l -> l.matches("WARN Cannot index the Message-ID of document " + doc.getId() + ": [\\w.$]+")));
			assertTrue(log.lines().toString(), log.lines().stream().noneMatch(l -> l.contains("1@example.com")));
		} finally {
			jdbc.execute("alter table hd_email drop constraint hd_email_refuses");
		}
	}

	@Test
	public void testShortcutsAreNoCopies() throws Exception {
		Document doc = file("healdan-cc.eml", "upload.eml");
		// A shortcut with a row, as one filed before the listener left shortcuts out could have
		Document shortcut = fx.legacyShortcut(doc, otherDocuments);
		EmailIndex.get().put(shortcut.getId(), "1@example.com");

		assertEquals(List.of(doc.getId()), EmailIndex.get().copies("1@example.com"));
		assertEquals(List.of(doc.getId()), EmailIndex.get().copies(List.of("1@example.com")).get("1@example.com"));
		assertTrue(EmailIndex.get().emailsIn(List.of(otherDocuments.getId()), false).isEmpty());
	}

	@Test
	public void testEmailsInAFolder() throws Exception {
		Document live = file("healdan-cc.eml", "one.eml");
		Document trashed = file("healdan-emoji.eml", "two.eml");
		DocumentDAO.get().delete(trashed.getId(), fx.documentHistory(admin));
		fx.upload(documents, "a.docx", "text", admin);
		awaitAsyncWork();

		assertEquals(Map.of(live.getId(), "1@example.com"), EmailIndex.get().emailsIn(List.of(documents.getId()), false));
		assertEquals(Map.of(trashed.getId(), "2@example.com"),
				EmailIndex.get().emailsIn(List.of(documents.getId()), true));
		assertTrue(EmailIndex.get().emailsIn(List.of(otherDocuments.getId()), false).isEmpty());
		assertTrue(EmailIndex.get().emailsIn(List.of(), false).isEmpty());
	}

	private void checkIn(Document doc, byte[] content, String asName) throws Exception {
		DocumentManager.get().checkout(doc.getId(), fx.documentHistory(admin));
		DocumentManager.get().checkin(doc.getId(), new ByteArrayInputStream(content), asName, false, null,
				fx.documentHistory(admin));
		awaitAsyncWork();
	}

	private static Object messageIdProperty(Document doc) throws Exception {
		return DocumentDAO.get().findById(doc.getId(), true).getValue(HealdanConstants.ATTR_EMAILMESSAGEID);
	}

	@Test
	public void testACheckInOfAnotherMessageReplacesItsRow() throws Exception {
		Document doc = file("healdan-cc.eml", "upload.eml");

		checkIn(doc, Files.readAllBytes(Path.of("src/test/resources/email/healdan-emoji.eml")), "upload.eml");
		assertEquals("2@example.com", EmailIndex.get().messageIdOf(doc.getId()));
		assertTrue(EmailIndex.get().copies("1@example.com").isEmpty());
		assertEquals(List.of(doc.getId()), EmailIndex.get().copies("2@example.com"));
		assertEquals("2@example.com", messageIdProperty(doc));

		// A new version that is no message takes the row and the property away
		checkIn(doc, "Plain text".getBytes(StandardCharsets.UTF_8), "upload.txt");
		assertNull(EmailIndex.get().messageIdOf(doc.getId()));
		assertEquals(0, rows());
		assertNull(messageIdProperty(doc));

		// Nor does a check-in of a document that is no message add a row
		Document docx = fx.upload(documents, "a.docx", "text", admin);
		checkIn(docx, "v2".getBytes(StandardCharsets.UTF_8), "a.docx");
		assertEquals(0, rows());
	}

	@Test
	public void testANewDocumentWhoseFileGivesNoneIsIndexedByItsProperty() throws Exception {
		// A copy's clone stored without its file, which carries the Message-ID property
		Document vo = new Document();
		vo.setFileName("clone.eml");
		vo.setFolder(documents);
		vo.setTenantId(Tenant.DEFAULT_ID);
		vo.setLanguage("en");
		vo.setValue(HealdanConstants.ATTR_EMAILMESSAGEID, "<clone@example.com>");
		DocumentDAO.get().store(vo, fx.documentHistory(admin));

		// What the once-per-matter rule checked is what the index holds
		assertEquals(List.of(vo.getId()), EmailIndex.get().copies("clone@example.com"));
	}

	@Test
	public void testAnIdInTwoPairsOfBracketsIsFound() throws Exception {
		File eml = File.createTempFile("email", ".eml");
		try {
			Files.writeString(eml.toPath(), "From: ann@example.com\nTo: bob@example.com\nSubject: Doubled\n"
					+ "Message-ID: <<doubled@example.com>>\nMIME-Version: 1.0\nContent-Type: text/plain\n\nHello.\n");
			Document vo = new Document();
			vo.setFileName("doubled.eml");
			vo.setFolder(documents);
			vo.setTenantId(Tenant.DEFAULT_ID);
			vo.setLanguage("en");
			Document doc = DocumentManager.get().create(eml, vo, fx.documentHistory(admin)).getDocument();
			awaitAsyncWork();

			// Stored as the lookups ask for it: as sent, and as a client that normalised the header sends it
			assertEquals("doubled@example.com", EmailIndex.get().messageIdOf(doc.getId()));
			assertEquals("doubled@example.com", messageIdProperty(doc));
			for (String asked : List.of("<<doubled@example.com>>", "<doubled@example.com>", "doubled@example.com"))
				assertEquals(asked, Map.of("doubled@example.com", List.of(doc.getId())),
						EmailIndex.get().copies(List.of(asked)));
		} finally {
			eml.delete();
		}
	}

	@Test
	public void testOnlyAnEmailIsIndexedByItsProperty() throws Exception {
		// CE's upload form can set the property on any document: only an .eml or a .msg is a message
		for (String name : List.of("notes.pdf", "notes", "notes.eml.pdf")) {
			Document vo = new Document();
			vo.setFileName(name);
			vo.setFolder(documents);
			vo.setTenantId(Tenant.DEFAULT_ID);
			vo.setLanguage("en");
			vo.setValue(HealdanConstants.ATTR_EMAILMESSAGEID, "<typed@example.com>");
			DocumentDAO.get().store(vo, fx.documentHistory(admin));
			assertNull(name, EmailIndex.get().messageIdOf(vo.getId()));
		}
		assertTrue(EmailIndex.get().copies("typed@example.com").isEmpty());
		assertEquals(0, rows());

		// In any case, as the extension is compared
		Document upper = new Document();
		upper.setFileName("CLONE.MSG");
		upper.setFolder(documents);
		upper.setTenantId(Tenant.DEFAULT_ID);
		upper.setLanguage("en");
		upper.setValue(HealdanConstants.ATTR_EMAILMESSAGEID, "<typed@example.com>");
		DocumentDAO.get().store(upper, fx.documentHistory(admin));
		assertEquals(List.of(upper.getId()), EmailIndex.get().copies("typed@example.com"));
	}

	@Test
	public void testPutReplacesAndNullRemoves() {
		EmailIndex index = EmailIndex.get();
		index.put(77L, "a@example.com");
		index.put(77L, "b@example.com");

		assertEquals(1, rows());
		assertEquals("b@example.com", index.messageIdOf(77L));
		assertEquals(MessageIds.hash("b@example.com"),
				jdbc.queryForObject("select hd_messageidhash from hd_email where hd_docid = 77", String.class));

		index.put(77L, null);
		assertEquals(0, rows());
		assertNull(index.messageIdOf(77L));

		// Removing what isn't there is nothing; so is a Message-ID that counts as none
		index.put(77L, null);
		index.put(77L, "a@example.com");
		index.put(77L, "");
		assertEquals(0, rows());
		index.put(77L, "a@example.com");
		index.put(77L, "x".repeat(MessageIds.MAX_LENGTH + 1));
		assertEquals(0, rows());
	}

	/**
	 * The shared DataSource, with every statement prepared through it added to
	 * the list in lower case
	 */
	private static DataSource recording(List<String> statements) {
		DataSource real = Context.get(DataSource.class);
		InvocationHandler connections = (proxy, method, args) -> {
			if (!"getConnection".equals(method.getName()))
				return invoke(real, method, args);
			Connection connection = (Connection) invoke(real, method, args);
			return Proxy.newProxyInstance(EmailIndexTest.class.getClassLoader(), new Class<?>[] { Connection.class },
					(c, m, a) -> {
						if (m.getName().startsWith("prepare") && a != null && a[0] instanceof String sql)
							statements.add(sql.toLowerCase(Locale.ROOT));
						return invoke(connection, m, a);
					});
		};
		return (DataSource) Proxy.newProxyInstance(EmailIndexTest.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, connections);
	}

	private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	@Test
	public void testRemovingARowThatIsntThereDeletesNothing() {
		// On MariaDB a delete that matches no row takes a gap lock, which two wide transactions can deadlock on, so
		// none runs when the document has no row: a check-in of an email whose new version has no Message-ID
		List<String> statements = new ArrayList<>();
		EmailIndex index = new EmailIndex(recording(statements));

		index.put(79L, null);
		index.put(79L, "");
		assertTrue(statements.toString(), statements.stream().noneMatch(s -> s.startsWith("delete")));

		// A row that is there still goes
		index.put(79L, "a@example.com");
		index.put(79L, null);
		assertNull(index.messageIdOf(79L));
		assertEquals(0, rows());
		assertEquals(1, statements.stream().filter(s -> s.startsWith("delete")).count());
	}

	@Test
	public void testTheLongestMessageIdFits() {
		String longest = "x".repeat(MessageIds.MAX_LENGTH);
		EmailIndex.get().put(78L, longest);

		assertEquals(longest, EmailIndex.get().messageIdOf(78L));
	}
}
