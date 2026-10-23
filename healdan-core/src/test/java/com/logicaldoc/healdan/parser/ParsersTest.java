package com.logicaldoc.healdan.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.parser.Parser;
import com.logicaldoc.core.parser.ParserFactory;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.OfficeFiles;
import com.logicaldoc.util.spring.Context;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

public class ParsersTest extends AbstractHealdanTestCase {

	@Before
	public void initParsers() {
		// The factory may have been filled before this test's plugins were loaded
		ParserFactory.init();
	}

	private String parse(String filename, byte[] content) throws Exception {
		Parser parser = ParserFactory.getParser(filename);
		return parser.parse(new ByteArrayInputStream(content), filename, "UTF-8", Locale.ENGLISH, "default");
	}

	/**
	 * Counts the pages both ways CE asks: from a stream (the Properties tab's
	 * recount) and from a file (upload and check-in), checking they agree.
	 */
	private int countPages(String filename, byte[] content) throws Exception {
		Parser parser = ParserFactory.getParser(filename);
		int fromStream = parser.countPages(new ByteArrayInputStream(content), filename);
		File file = File.createTempFile("pages", "-" + filename, new File("target"));
		try {
			Files.write(file.toPath(), content);
			assertEquals(filename, fromStream, parser.countPages(file, filename));
		} finally {
			Files.delete(file.toPath());
		}
		return fromStream;
	}

	private static byte[] encrypt(byte[] plain) throws Exception {
		try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
			Encryptor encryptor = info.getEncryptor();
			encryptor.confirmPassword("pw");
			try (java.io.OutputStream os = encryptor.getDataStream(fs)) {
				os.write(plain);
			}
			fs.writeFilesystem(out);
			return out.toByteArray();
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

	private byte[] docx(String text) throws Exception {
		try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			doc.createParagraph().createRun().setText(text);
			doc.write(out);
			return out.toByteArray();
		}
	}

	private byte[] xlsx(String text) throws Exception {
		try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			book.createSheet("Sheet1").createRow(0).createCell(0).setCellValue(text);
			book.write(out);
			return out.toByteArray();
		}
	}

	private byte[] pptx(String text) throws Exception {
		try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			XSLFTextBox box = show.createSlide().createTextBox();
			box.setText(text);
			show.write(out);
			return out.toByteArray();
		}
	}

	private byte[] eml(boolean html) throws Exception {
		MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom(new InternetAddress("ann@example.com", "Ann Smith"));
		message.setRecipients(MimeMessage.RecipientType.TO, "bob@example.com");
		message.setRecipients(MimeMessage.RecipientType.CC, "carol@example.com");
		message.setSubject("Quarterly zqxsubject");
		MimeBodyPart body = new MimeBodyPart();
		if (html)
			body.setContent("<html><body><p>Hello <b>zqxbody</b> world</p></body></html>", "text/html; charset=UTF-8");
		else
			body.setText("Hello zqxbody world", "UTF-8");
		MimeBodyPart attachment = new MimeBodyPart();
		attachment.setDataHandler(new DataHandler(new ByteArrayDataSource("secret zqxinside".getBytes(), "text/plain")));
		attachment.setFileName("zqxattached.txt");
		MimeMultipart multipart = new MimeMultipart();
		multipart.addBodyPart(body);
		multipart.addBodyPart(attachment);
		message.setContent(multipart);
		message.saveChanges();
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			message.writeTo(out);
			return out.toByteArray();
		}
	}

	@Test
	public void testExtensionsMapToHealdanParsers() {
		for (String ext : new String[] { "docx", "docm", "dotx" })
			assertEquals(ext, WordParser.class, ParserFactory.getParser("a." + ext).getClass());
		for (String ext : new String[] { "xlsx", "xlsm" })
			assertEquals(ext, ExcelParser.class, ParserFactory.getParser("a." + ext).getClass());
		for (String ext : new String[] { "pptx", "ppsx" })
			assertEquals(ext, PowerPointParser.class, ParserFactory.getParser("a." + ext).getClass());
		for (String ext : new String[] { "eml", "msg" })
			assertEquals(ext, EmailParser.class, ParserFactory.getParser("a." + ext).getClass());
	}

	@Test
	public void testOfficeTextIsExtracted() throws Exception {
		assertTrue(parse("a.docx", docx("zqxword token")).contains("zqxword"));
		assertTrue(parse("a.xlsx", xlsx("zqxexcel token")).contains("zqxexcel"));
		assertTrue(parse("a.pptx", pptx("zqxslide token")).contains("zqxslide"));
	}

	@Test
	public void testEmlHeadersBodyAndAttachmentName() throws Exception {
		String text = parse("a.eml", eml(false));
		assertTrue(text, text.contains("zqxsubject"));
		assertTrue(text, text.contains("zqxbody"));
		assertTrue(text, text.contains("zqxattached.txt"));
		assertTrue(text, text.contains("ann@example.com"));
		assertTrue(text, text.contains("bob@example.com"));
		assertTrue(text, text.contains("carol@example.com"));
		// Attachment contents are not indexed in M1
		assertTrue(text, !text.contains("zqxinside"));
	}

	@Test
	public void testEmlHtmlBodyIsStrippedToText() throws Exception {
		String text = parse("a.eml", eml(true));
		assertTrue(text, text.contains("zqxbody"));
		assertTrue(text, !text.contains("<b>"));
		assertTrue(text, !text.contains("<html"));
	}

	@Test
	public void testMsgHeadersBodyAndAttachmentName() throws Exception {
		try (InputStream is = getClass().getResourceAsStream("/email/test.msg")) {
			String text = parse("a.msg", is.readAllBytes());
			assertTrue(text, text.contains("Offer for Customizations on LogicalDOC"));
			assertTrue(text, text.contains("Please send me the invoice"));
			assertTrue(text, text.contains("Bestellu.pdf"));
			assertTrue(text, text.contains("erich.widmer@olig.ch"));
			assertTrue(text, !text.contains("rtf1"));
		}
	}

	@Test
	public void testEncryptedOrCorruptFilesYieldEmptyText() throws Exception {
		// A password-protected docx is an OLE container
		byte[] plain = docx("zqxsecret");
		assertEquals("", parse("enc.docx", encrypt(plain)));

		assertEquals("", parse("cut.docx", Arrays.copyOf(plain, plain.length / 2)));
		assertEquals("", parse("cut.xlsx", Arrays.copyOf(xlsx("x"), 100)));
		assertEquals("", parse("cut.pptx", Arrays.copyOf(pptx("x"), 100)));

		byte[] random = new byte[2048];
		new Random(7).nextBytes(random);
		for (String name : new String[] { "r.docx", "r.xlsx", "r.pptx", "r.eml", "r.msg" })
			assertEquals(name, "", parse(name, random).trim());
		for (String name : new String[] { "e.docx", "e.xlsx", "e.pptx", "e.eml", "e.msg" })
			assertEquals(name, "", parse(name, new byte[0]).trim());
	}

	@Test
	public void testWordCountIsTheOneWordSaved() throws Exception {
		assertEquals(12, countPages("a.docx", OfficeFiles.docx(12)));
		assertEquals(12, countPages("a.DOCM", OfficeFiles.docx(12)));
		assertEquals(12, countPages("a.dotx", OfficeFiles.docx(12)));
		// Laid out as Word writes it, not as POI does
		assertEquals(7, countPages("word.docx", OfficeFiles.handMadeDocx(7)));

		// A file that saved no count, or a count of 0, has CE's default of 1
		assertEquals(1, countPages("a.docx", OfficeFiles.docx(null)));
		assertEquals(1, countPages("a.docx", OfficeFiles.docx(0)));
	}

	@Test
	public void testPowerPointCountsEverySlide() throws Exception {
		// Hidden slides count, as in PowerPoint's own slide count
		assertEquals(3, countPages("a.pptx", OfficeFiles.pptx(3, 1)));
		assertEquals(3, countPages("a.ppsx", OfficeFiles.pptx(3, 0)));
		assertEquals(1, countPages("a.pptx", OfficeFiles.pptx(0, 0)));
	}

	@Test
	public void testExcelCountsVisibleSheets() throws Exception {
		assertEquals(2, countPages("a.xlsx", OfficeFiles.xlsx(2, 1, 1)));
		assertEquals(4, countPages("a.xlsm", OfficeFiles.xlsx(4, 0, 0)));
	}

	@Test
	public void testUnreadableFilesCountOnePage() throws Exception {
		assertEquals(1, countPages("enc.docx", encrypt(OfficeFiles.docx(12))));
		byte[] word = OfficeFiles.docx(12);
		assertEquals(1, countPages("cut.docx", Arrays.copyOf(word, word.length / 2)));
		assertEquals(1, countPages("cut.xlsx", Arrays.copyOf(OfficeFiles.xlsx(2, 0, 0), 100)));
		assertEquals(1, countPages("cut.pptx", Arrays.copyOf(OfficeFiles.pptx(3, 0), 100)));

		byte[] random = new byte[2048];
		new Random(7).nextBytes(random);
		for (String name : new String[] { "r.docx", "r.xlsx", "r.pptx" }) {
			assertEquals(name, 1, countPages(name, random));
			assertEquals(name, 1, countPages(name, new byte[0]));
		}
	}

	@Test
	public void testUploadAndCheckinCountPages() throws Exception {
		HealdanFixtures fx = new HealdanFixtures();
		Matter matter = fx.matter(fx.client(996, "Healdan"), 1, "Pages", fx.store("Main"), "Documents");
		Folder folder = FolderDAO.get()
				.findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents", Tenant.DEFAULT_ID, true).get(0);

		Document word = fx.upload(folder, "a.docx", OfficeFiles.docx(12), fx.admin());
		assertEquals(12, DocumentDAO.get().findById(word.getId()).getPages());
		assertEquals(3, fx.upload(folder, "b.pptx", OfficeFiles.pptx(3, 0), fx.admin()).getPages());
		assertEquals(2, fx.upload(folder, "c.xlsx", OfficeFiles.xlsx(2, 1, 0), fx.admin()).getPages());

		DocumentManager.get().checkout(word.getId(), fx.documentHistory(fx.admin()));
		DocumentManager.get().checkin(word.getId(), new ByteArrayInputStream(OfficeFiles.docx(15)), "a.docx", false,
				null, fx.documentHistory(fx.admin()));
		awaitAsyncWork();

		word = DocumentDAO.get().findById(word.getId());
		assertEquals(15, word.getPages());
		// The recount on the Properties tab reads the stored file
		assertEquals(15, DocumentManager.get().countPages(word));
		// Each version keeps the count of its own file
		List<String> versions = new JdbcTemplate(Context.get(DataSource.class)).queryForList(
				"select distinct ld_fileversion || ' ' || cast(ld_pages as varchar(10)) from ld_version "
						+ "where ld_documentid = ? and ld_deleted = 0 order by 1",
				String.class, word.getId());
		assertEquals(List.of("1.0 12", word.getFileVersion() + " 15"), versions);
	}
}
