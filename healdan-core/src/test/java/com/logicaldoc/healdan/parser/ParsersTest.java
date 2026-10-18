package com.logicaldoc.healdan.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;

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

import com.logicaldoc.core.parser.Parser;
import com.logicaldoc.core.parser.ParserFactory;
import com.logicaldoc.healdan.AbstractHealdanTestCase;

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
		byte[] encrypted;
		try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
			Encryptor encryptor = info.getEncryptor();
			encryptor.confirmPassword("pw");
			try (java.io.OutputStream os = encryptor.getDataStream(fs)) {
				os.write(plain);
			}
			fs.writeFilesystem(out);
			encrypted = out.toByteArray();
		}
		assertEquals("", parse("enc.docx", encrypted));

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
}
