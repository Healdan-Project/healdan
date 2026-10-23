package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.Before;
import org.junit.Test;

public class MarkupPreparerTest {

	private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

	private static final String XMLNS = "xmlns:w=\"" + W + "\"";

	private static final String SETTINGS = "<w:settings " + XMLNS + "><w:zoom w:percent=\"100\"/>"
			+ "<w:revisionView w:markup=\"false\" w:insDel=\"false\"/></w:settings>";

	private File dir;

	@Before
	public void setUp() throws IOException {
		new File("target").mkdirs();
		dir = Files.createTempDirectory(new File("target").toPath(), "markup").toFile();
	}

	private File zip(String name, Map<String, String> entries) throws IOException {
		Map<String, byte[]> bytes = new LinkedHashMap<>();
		entries.forEach((k, v) -> bytes.put(k, v.getBytes(StandardCharsets.UTF_8)));
		return zipBytes(name, bytes);
	}

	private File zipBytes(String name, Map<String, byte[]> entries) throws IOException {
		File f = new File(dir, name);
		try (OutputStream os = Files.newOutputStream(f.toPath()); ZipOutputStream zip = new ZipOutputStream(os)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey()));
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return f;
	}

	private static byte[] read(File zip, String entry) throws IOException {
		try (ZipFile z = new ZipFile(zip)) {
			return z.getInputStream(z.getEntry(entry)).readAllBytes();
		}
	}

	private static String settingsOf(File zip) throws IOException {
		return new String(read(zip, "word/settings.xml"), StandardCharsets.UTF_8);
	}

	private File work() {
		File w = new File(dir, "work");
		w.mkdirs();
		return w;
	}

	/** Prepares a file whose settings part is the given XML and expects the element gone */
	private void assertRemoved(String name, String settings) throws IOException {
		File src = zip(name, Map.of("word/settings.xml", settings));

		File copy = MarkupPreparer.prepare(src, work());

		assertNotSame(name, src, copy);
		assertFalse(name + ": " + settingsOf(copy), settingsOf(copy).contains("revisionView"));
	}

	@Test
	public void testRevisionViewRemoved() throws IOException {
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("[Content_Types].xml", "<Types/>");
		entries.put("word/document.xml", "<w:document><w:del>gone</w:del></w:document>");
		entries.put("word/settings.xml", SETTINGS);
		File src = zip("a.docx", entries);

		File copy = MarkupPreparer.prepare(src, work());

		assertNotSame(src, copy);
		assertEquals("a.docx", copy.getName());
		assertEquals(work(), copy.getParentFile());
		String settings = settingsOf(copy);
		assertTrue(settings, settings.contains("<w:zoom w:percent=\"100\""));
		assertFalse(settings.contains("revisionView"));
		assertArrayEquals(read(src, "word/document.xml"), read(copy, "word/document.xml"));
		assertArrayEquals(read(src, "[Content_Types].xml"), read(copy, "[Content_Types].xml"));
		// The stored file keeps its markup setting
		assertTrue(new String(read(src, "word/settings.xml"), StandardCharsets.UTF_8).contains("revisionView"));
	}

	@Test
	public void testFullWordRevisionViewRemoved() throws IOException {
		String view = "<w:revisionView w:markup=\"0\" w:comments=\"0\" w:insDel=\"0\" w:formatting=\"0\""
				+ " w:inkAnnotations=\"0\"/>";
		File src = zip("f.docx", Map.of("word/settings.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
				+ "<w:settings " + XMLNS + "><w:zoom w:percent=\"100\"/>" + view + "<w:trackRevisions/></w:settings>"));

		File copy = MarkupPreparer.prepare(src, work());

		assertNotSame(src, copy);
		String settings = settingsOf(copy);
		assertFalse(settings, settings.contains("revisionView"));
		assertTrue(settings, settings.contains("<w:zoom w:percent=\"100\""));
		assertTrue(settings, settings.contains("<w:trackRevisions"));
		assertTrue(settings, settings.contains("standalone=\"yes\""));
	}

	@Test
	public void testAnyPrefixRemoved() throws IOException {
		assertRemoved("p.docx", "<x:settings xmlns:x=\"" + W + "\"><x:revisionView x:insDel=\"0\"/></x:settings>");
		assertRemoved("d.docx", "<settings xmlns=\"" + W + "\"><revisionView insDel=\"0\"/></settings>");
	}

	@Test
	public void testStrictNamespaceRemoved() throws IOException {
		assertRemoved("s.docx", "<w:settings xmlns:w=\"http://purl.oclc.org/ooxml/wordprocessingml/main\">"
				+ "<w:revisionView w:insDel=\"false\"/></w:settings>");
	}

	@Test
	public void testAnyTagFormRemoved() throws IOException {
		assertRemoved("o.docx", "<w:settings " + XMLNS + "><w:revisionView w:insDel=\"0\"></w:revisionView></w:settings>");
		assertRemoved("n.docx", "<w:settings " + XMLNS + "><w:revisionView\n  w:insDel=\"0\" >\n</w:revisionView></w:settings>");
		// ">" is allowed unescaped in an attribute value
		assertRemoved("g.docx", "<w:settings " + XMLNS + "><w:revisionView w:insDel=\"0\" w:x=\">\"/></w:settings>");
	}

	@Test
	public void testUtf16SettingsRemoved() throws IOException {
		String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><w:settings " + XMLNS
				+ "><w:revisionView w:insDel=\"0\"/></w:settings>";
		ByteArrayOutputStream utf16 = new ByteArrayOutputStream();
		utf16.writeBytes(xml.getBytes(Charset.forName("UTF-16")));
		File src = zipBytes("u.docx", Map.of("word/settings.xml", utf16.toByteArray()));

		File copy = MarkupPreparer.prepare(src, work());

		assertNotSame(src, copy);
		String settings = settingsOf(copy);
		assertFalse(settings, settings.contains("revisionView"));
		assertTrue(settings, settings.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\""));
	}

	@Test
	public void testOtherNamespaceKept() throws IOException {
		File src = zip("k.docx", Map.of("word/settings.xml",
				"<w:settings " + XMLNS + " xmlns:o=\"urn:other\"><o:revisionView/></w:settings>"));

		assertSame(src, MarkupPreparer.prepare(src, work()));
	}

	@Test
	public void testSettingsFoundThroughTheRelationships() throws IOException {
		String rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
				+ "<Relationship Id=\"rId1\" Type=\"" + rel + "officeDocument\" Target=\"word/main.xml\"/></Relationships>");
		entries.put("word/_rels/main.xml.rels",
				"<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
						+ "<Relationship Id=\"rId1\" Type=\"" + rel + "settings\" Target=\"conf/prefs.xml\"/>"
						+ "<Relationship Id=\"rId2\" Type=\"" + rel + "settings\" Target=\"http://example.com/s.xml\""
						+ " TargetMode=\"External\"/></Relationships>");
		entries.put("word/main.xml", "<w:document " + XMLNS + "/>");
		entries.put("word/conf/prefs.xml", SETTINGS);
		// The usual name holds nothing LibreOffice would read
		entries.put("word/settings.xml", SETTINGS);
		File src = zip("r.docx", entries);

		File copy = MarkupPreparer.prepare(src, work());

		assertNotSame(src, copy);
		assertFalse(new String(read(copy, "word/conf/prefs.xml"), StandardCharsets.UTF_8).contains("revisionView"));
		assertArrayEquals(read(src, "word/settings.xml"), read(copy, "word/settings.xml"));
	}

	@Test
	public void testMalformedSettingsReplaced() throws IOException {
		// Unclosed, and with a DTD the parser won't process
		for (String settings : new String[] { "<w:settings " + XMLNS + "><w:revisionView w:insDel=\"0\"/>",
				"<!DOCTYPE w:settings [<!ENTITY e \"x\">]><w:settings " + XMLNS
						+ "><w:revisionView w:insDel=\"0\"/>&e;</w:settings>" })
			assertRemoved("m.docx", settings);
	}

	@Test(timeout = 5000)
	public void testPathologicalSettingsIsFast() throws IOException {
		// Many openings and no ">": a scan from each opening to the end would be quadratic
		String opening = "<w:revisionView ";
		StringBuilder sb = new StringBuilder("<w:settings " + XMLNS + ">");
		while (sb.length() < 4 * 1024 * 1024 - 100)
			sb.append(opening);
		assertTrue(sb.length() < 4 * 1024 * 1024);
		File src = zip("g.docx", Map.of("word/settings.xml", sb.toString()));

		File result = MarkupPreparer.prepare(src, work());

		assertTrue(result.exists());
		assertFalse(settingsOf(result).contains("revisionView"));
	}

	@Test
	public void testOtherFormatsUntouched() throws IOException {
		for (String name : new String[] { "a.doc", "a.odt", "a.xlsx" }) {
			File src = zip(name, Map.of("word/settings.xml", SETTINGS));
			assertSame(name, src, MarkupPreparer.prepare(src, work()));
		}
	}

	@Test
	public void testUnreadableZipUntouched() throws IOException {
		byte[] junk = new byte[512];
		new Random(7).nextBytes(junk);
		File src = new File(dir, "bad.docx");
		Files.write(src.toPath(), junk);

		assertSame(src, MarkupPreparer.prepare(src, work()));
		assertFalse(new File(work(), "bad.docx").exists());
	}

	@Test
	public void testNoSettingsPart() throws IOException {
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("[Content_Types].xml", "<Types/>");
		entries.put("word/document.xml", "<w:document/>");
		File src = zip("b.docx", entries);

		assertSame(src, MarkupPreparer.prepare(src, work()));
		assertEquals(0, work().list().length);
	}

	@Test
	public void testNoRevisionViewNotCopied() throws IOException {
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("[Content_Types].xml", "<Types/>");
		entries.put("word/document.xml", "<w:document/>");
		entries.put("word/settings.xml", "<w:settings " + XMLNS + "><w:zoom w:percent=\"100\"/></w:settings>");
		File src = zip("c.docx", entries);

		assertSame(src, MarkupPreparer.prepare(src, work()));
		assertEquals(0, work().list().length);
	}

	@Test
	public void testOversizedSettingsReplaced() throws IOException {
		// Random hex barely compresses, so only the size of the settings part can stop the parse
		byte[] noise = new byte[2 * 1024 * 1024];
		new Random(7).nextBytes(noise);
		String settings = "<w:settings " + XMLNS + "><w:revisionView w:markup=\"false\"/><!--"
				+ HexFormat.of().formatHex(noise) + "--></w:settings>";
		assertTrue(settings.length() > 4 * 1024 * 1024);
		assertTrue(settings.length() < 4 * 1024 * 1024 + 200);
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("word/document.xml", "<w:document/>");
		entries.put("word/settings.xml", settings);
		File src = zip("d.docx", entries);

		File copy = MarkupPreparer.prepare(src, work());

		assertNotSame(src, copy);
		String replaced = settingsOf(copy);
		assertFalse(replaced.contains("revisionView"));
		assertTrue(replaced, replaced.endsWith("<w:settings " + XMLNS + "/>"));
		assertArrayEquals(read(src, "word/document.xml"), read(copy, "word/document.xml"));
	}

	@Test
	public void testInflatingPastTheRatioUntouched() throws IOException {
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("word/settings.xml", SETTINGS);
		entries.put("word/document.xml", "<w:document>" + "0".repeat(5_000_000) + "</w:document>");
		File src = zip("e.docx", entries);
		assertTrue(src.length() * 100 < 5_000_000);

		assertSame(src, MarkupPreparer.prepare(src, work()));
		assertEquals(0, work().list().length);
	}
}
