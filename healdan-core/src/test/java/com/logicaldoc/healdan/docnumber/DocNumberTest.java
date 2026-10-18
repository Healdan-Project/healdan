package com.logicaldoc.healdan.docnumber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.channels.ChannelHarness;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;
import com.logicaldoc.webservice.model.WSDocument;
import com.logicaldoc.webservice.soap.endpoint.SoapDocumentService;

/**
 * Spec §6.1–§6.3: every new document gets the next doc # from Healdan's
 * counter, and nothing but the counter ever changes it.
 */
public class DocNumberTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private DocumentDAO documentDao;

	private User admin;

	private Folder documents;

	@Override
	protected List<String> getDatabaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "sql/logicaldoc-webservice.sql", "data.sql");
	}

	@Before
	public void createLayout() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		documentDao = DocumentDAO.get();
		admin = fx.admin();
		Folder store = fx.store("Main");
		documents = FolderDAO.get().findByName(
				FolderDAO.get().findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId()),
				"Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private Document upload(String name) throws Exception {
		return fx.upload(documents, name, "text " + name, admin);
	}

	private String customId(long docId) {
		return jdbc.queryForObject("select ld_customid from ld_document where ld_id = ?", String.class, docId);
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

	private Document reload(Document doc) throws Exception {
		return documentDao.findById(doc.getId(), true);
	}

	@Test
	public void testNumbersStartAtOneAndIncrease() throws Exception {
		assertEquals("1", customId(upload("a.txt").getId()));
		assertEquals("2", customId(upload("b.txt").getId()));
	}

	@Test
	public void testCopyGetsNewNumber() throws Exception {
		Document source = upload("a.txt");
		Document copy = DocumentManager.get()
				.copyToFolder(reload(source), documents, fx.documentHistory(admin), false, false, false).getDocument();
		assertEquals("2", customId(copy.getId()));
		assertEquals("1", customId(source.getId()));
	}

	@Test
	public void testSuppliedCustomIdIgnored() throws Exception {
		WSDocument document = new WSDocument();
		document.setFolderId(documents.getId());
		document.setFileName("rest.txt");
		document.setCustomId("999");
		long id = new ChannelHarness().soapDocuments()
				.create("", document, new ByteArrayInputStream("rest".getBytes(StandardCharsets.UTF_8))).getId();
		assertEquals("1", customId(id));
	}

	@Test
	public void testCheckinWithNullKeepsNumber() throws Exception {
		Document doc = upload("a.txt");
		Document vo = new Document();
		vo.setCustomId(null);
		vo.setFileName("a.txt");
		DocumentManager.get().checkin(doc.getId(), new ByteArrayInputStream("v2".getBytes(StandardCharsets.UTF_8)),
				"a.txt", false, vo, fx.documentHistory(admin));
		awaitAsyncWork();
		assertEquals("1", customId(doc.getId()));
	}

	private void update(Document doc, String customId) throws Exception {
		Document vo = new Document(reload(doc));
		vo.setCustomId(customId);
		DocumentManager.get().update(reload(doc), vo, fx.documentHistory(admin));
		awaitAsyncWork();
	}

	private void assertVersionsHave(Document doc, String customId) {
		List<String> ids = jdbc.queryForList("select ld_customid from ld_version where ld_documentid = ?",
				String.class, doc.getId());
		assertTrue("no version rows", ids.size() >= 2);
		for (String id : ids)
			assertEquals(customId, id);
	}

	@Test
	public void testUpdateCannotChangeNumber() throws Exception {
		Document doc = upload("a.txt");
		update(doc, "77");
		assertEquals("1", customId(doc.getId()));
		assertEquals("1", reload(doc).getCustomId());
		assertVersionsHave(doc, "1");
	}

	@Test
	public void testUpdateWithAnotherDocumentsNumberChangesNothing() throws Exception {
		Document doc = upload("a.txt");
		Document other = upload("b.txt");
		update(doc, "2");
		assertEquals("1", customId(doc.getId()));
		assertEquals("2", customId(other.getId()));
		assertVersionsHave(doc, "1");
		for (String id : jdbc.queryForList("select ld_customid from ld_version where ld_documentid = ?",
				String.class, other.getId()))
			assertEquals("2", id);
	}

	@Test
	public void testCheckinWithAnUnusedNumberKeepsNumberInVersions() throws Exception {
		Document doc = upload("a.txt");
		Document vo = new Document();
		vo.setCustomId("77");
		vo.setFileName("a.txt");
		DocumentManager.get().checkin(doc.getId(), new ByteArrayInputStream("v2".getBytes(StandardCharsets.UTF_8)),
				"a.txt", false, vo, fx.documentHistory(admin));
		awaitAsyncWork();
		assertEquals("1", customId(doc.getId()));
		assertVersionsHave(doc, "1");
	}

	/**
	 * Characterises a known gap: check-in has no Healdan hook, so a value object
	 * carrying another document's doc # (SOAP/REST checkin and the GUI can send
	 * one) is refused by CE's own uniqueness check, not ignored. Nothing changes.
	 */
	@Test
	public void testCheckinWithAnotherDocumentsNumberIsRefusedByCe() throws Exception {
		Document doc = upload("a.txt");
		Document other = upload("b.txt");
		Session session = SessionManager.get().newSession("admin", "admin", (com.logicaldoc.core.security.Client) null);
		try {
			SoapDocumentService soap = new ChannelHarness().soapDocuments();
			soap.checkout(session.getSid(), doc.getId());
			WSDocument vo = new WSDocument();
			vo.setCustomId("2");
			vo.setFolderId(documents.getId());
			vo.setFileName("a.txt");
			Exception e = assertThrows(Exception.class, () -> soap.checkin(session.getSid(), doc.getId(), "c",
					"a.txt", false, vo, new ByteArrayInputStream("v2".getBytes(StandardCharsets.UTF_8))));
			assertTrue(e.getMessage(), e.getMessage().contains("Duplicated CustomID"));
			awaitAsyncWork();
		} finally {
			SessionManager.get().kill(session.getSid());
		}
		assertEquals("1", customId(doc.getId()));
		assertEquals("2", customId(other.getId()));
	}

	@Test
	public void testStoredNullStaysNull() throws Exception {
		Document doc = upload("a.txt");
		DocNumberListener listener = new DocNumberListener();
		jdbc.update("update ld_document set ld_customid = null where ld_id = ?", doc.getId());
		Document incoming = new Document();
		incoming.setId(doc.getId());
		incoming.setCustomId("55");
		listener.beforeStore(incoming, null, new java.util.HashMap<>());
		assertNull(incoming.getCustomId());
		assertEquals(2L, Context.get(DocNumberCounter.class).peekNext());
	}

	@Test
	public void testPresetIdWithoutRowTakesANumber() throws Exception {
		Document incoming = new Document();
		incoming.setId(987654L);
		incoming.setCustomId("55");
		new DocNumberListener().beforeStore(incoming, null, new java.util.HashMap<>());
		assertEquals("1", incoming.getCustomId());
	}

	@Test
	public void testDeleteAndRestoreKeepPlainNumber() throws Exception {
		Document doc = upload("a.txt");
		documentDao.delete(doc.getId(), fx.documentHistory(admin));
		assertEquals("1", customId(doc.getId()));
		documentDao.restore(doc.getId(), documents.getId(), fx.documentHistory(admin));
		assertEquals("1", customId(doc.getId()));
	}

	@Test
	public void testVersionsCarryNumber() throws Exception {
		Document doc = upload("a.txt");
		Document vo = new Document();
		vo.setFileName("a.txt");
		DocumentManager.get().checkin(doc.getId(), new ByteArrayInputStream("v2".getBytes(StandardCharsets.UTF_8)),
				"a.txt", false, vo, fx.documentHistory(admin));
		documentDao.delete(doc.getId(), fx.documentHistory(admin));
		documentDao.restore(doc.getId(), documents.getId(), fx.documentHistory(admin));
		awaitAsyncWork();
		List<String> ids = jdbc.queryForList("select ld_customid from ld_version where ld_documentid = ?",
				String.class, doc.getId());
		assertEquals(true, ids.size() >= 2);
		for (String id : ids)
			assertEquals("1", id);
	}

	@Test
	public void testNeverReused() throws Exception {
		Document first = upload("a.txt");
		documentDao.delete(first.getId(), fx.documentHistory(admin));
		assertEquals("2", customId(upload("b.txt").getId()));
	}

	@Test
	public void testRaiseOnlyUp() throws Exception {
		DocNumberCounter counter = Context.get(DocNumberCounter.class);
		counter.raiseNext(1000);
		assertEquals("1000", customId(upload("a.txt").getId()));
		assertEquals(1001L, counter.peekNext());
		counter.raiseNext(1001);
		HealdanRuleException e = assertThrows(HealdanRuleException.class, () -> counter.raiseNext(999));
		assertEquals("The next doc # can only be raised.", e.getMessage());
	}
}
