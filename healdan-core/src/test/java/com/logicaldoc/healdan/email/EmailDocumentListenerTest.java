package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.channels.ChannelHarness;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.webservice.model.WSDocument;
import com.logicaldoc.webservice.soap.endpoint.SoapDocumentService;

/**
 * Spec \u00a76.8: an email filed by any route gets its From, To, CC, Subject, Sent and
 * Received, and is named after its subject; every new document's Date filed is now.
 */
public class EmailDocumentListenerTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private Folder documents;

	@Override
	protected List<String> getDatabaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "sql/logicaldoc-webservice.sql", "data.sql");
	}

	@Before
	public void createLayout() throws Exception {
		admin = fx.admin();
		Folder store = fx.store("Main");
		documents = FolderDAO.get().findByName(
				FolderDAO.get().findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId()),
				"Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private Document file(String resource, String asName) throws Exception {
		File copy = File.createTempFile("email", "." + asName.substring(asName.lastIndexOf('.') + 1));
		try {
			Files.copy(new File("src/test/resources/email/" + resource).toPath(), copy.toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			Document vo = new Document();
			vo.setFileName(asName);
			vo.setFolder(documents);
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

	private static Date utc(String text) throws Exception {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.parse(text);
	}

	@Test
	public void testEmlFields() throws Exception {
		Document doc = file("healdan-cc.eml", "upload.eml");
		assertEquals("Ann Smith <ann@example.com>", doc.getValue(HealdanConstants.ATTR_EMAILFROM));
		assertEquals("Bob Jones <bob@example.com>, carol@example.com", doc.getValue(HealdanConstants.ATTR_EMAILTO));
		assertEquals("Ren\u00e9e Dupont <renee@example.com>", doc.getValue(HealdanConstants.ATTR_EMAILCC));
		assertEquals("Re: Demo plan \u2013 caf\u00e9", doc.getValue(HealdanConstants.ATTR_EMAILSUBJECT));
		assertEquals(utc("2025-10-14 07:29:00"), doc.getValue(HealdanConstants.ATTR_EMAILSENT));
		assertEquals(utc("2025-10-14 07:30:05"), doc.getValue(HealdanConstants.ATTR_EMAILRECEIVED));
	}

	@Test
	public void testMsgFields() throws Exception {
		Document doc = file("test.msg", "upload.msg");
		assertNotNull(doc.getValue(HealdanConstants.ATTR_EMAILFROM));
		assertNotNull(doc.getValue(HealdanConstants.ATTR_EMAILSUBJECT));
		assertNotNull(doc.getValue(HealdanConstants.ATTR_EMAILSENT));
		assertTrue(doc.getFileName().endsWith(".msg"));
		assertEquals(FileNames.fromSubject(doc.getValue(HealdanConstants.ATTR_EMAILSUBJECT).toString(), "msg", "x"),
				doc.getFileName());
	}

	@Test
	public void testNamedAfterSubject() throws Exception {
		Document doc = file("healdan-cc.eml", "upload.eml");
		assertEquals("Re_ Demo plan \u2013 caf\u00e9.eml", doc.getFileName());
		assertEquals("eml", doc.getType());
	}

	@Test
	public void testDateFiledIsNowEvenIfSupplied() throws Exception {
		SoapDocumentService soap = new ChannelHarness().soapDocuments();
		WSDocument ws = new WSDocument();
		ws.setFolderId(documents.getId());
		ws.setFileName("rest.txt");
		ws.setCreation("2001-01-01T00:00:00.000+0000");
		long before = System.currentTimeMillis();
		long id = soap.create("", ws, new java.io.ByteArrayInputStream("rest".getBytes())).getId();
		awaitAsyncWork();
		Document doc = DocumentDAO.get().findById(id, true);
		assertTrue(doc.getCreation().getTime() >= before - 2000L);
		assertTrue(doc.getCreation().getTime() <= System.currentTimeMillis() + 2000L);
	}

	@Test
	public void testDateFiledOfAnUploadIsNow() throws Exception {
		long before = System.currentTimeMillis();
		Document doc = fx.upload(documents, "a.txt", "text", admin);
		awaitAsyncWork();
		Document stored = DocumentDAO.get().findById(doc.getId(), true);
		assertTrue(stored.getCreation().getTime() >= before - 2000L);
	}

	@Test
	public void testNoExtraVersion() throws Exception {
		Document doc = file("healdan-cc.eml", "upload.eml");
		List<Version> versions = VersionDAO.get().findByDocId(doc.getId());
		assertEquals(1, versions.size());
		assertEquals("1.0", versions.get(0).getVersion());
		Version version = VersionDAO.get().findById(versions.get(0).getId(), true);
		assertEquals(doc.getValue(HealdanConstants.ATTR_EMAILSUBJECT),
				version.getValue(HealdanConstants.ATTR_EMAILSUBJECT));
		assertEquals(doc.getFileName(), versions.get(0).getFileName());
	}

	@Test
	public void testMessyEmailsNeverBlockUpload() throws Exception {
		Document noDate = file("healdan-nodate.eml", "nodate.eml");
		assertNull(noDate.getValue(HealdanConstants.ATTR_EMAILSENT));
		assertNull(noDate.getValue(HealdanConstants.ATTR_EMAILRECEIVED));
		assertEquals("No date here.eml", noDate.getFileName());

		Document bad = file("healdan-badsubject.eml", "bad.eml");
		assertTrue(bad.getFileName().length() <= 255);
		assertTrue(bad.getFileName().endsWith(".eml"));
		assertTrue(bad.getFileName().startsWith("Quarterly_report_______ xxx"));
		assertTrue(bad.getFileName().matches("[^/\\\\:*?\"<>|\\p{Cntrl}]+"));

		Document corrupt = file("corrupt.msg", "corrupt.msg");
		assertEquals("corrupt.msg", corrupt.getFileName());
		assertNull(corrupt.getValue(HealdanConstants.ATTR_EMAILFROM));
		assertNull(corrupt.getValue(HealdanConstants.ATTR_EMAILSUBJECT));
		assertNull(corrupt.getValue(HealdanConstants.ATTR_EMAILSENT));
	}

	@Test
	public void testNonEmailKeepsItsName() throws Exception {
		Document doc = file("healdan-cc.eml", "notes.txt");
		assertEquals("notes.txt", doc.getFileName());
		assertNull(doc.getValue(HealdanConstants.ATTR_EMAILSUBJECT));
	}
}
