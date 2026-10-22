package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.Before;
import org.junit.Test;

public class MarkupPreparerTest {

	private static final String SETTINGS = "<w:settings><w:zoom w:percent=\"100\"/>"
			+ "<w:revisionView w:markup=\"false\" w:insDel=\"false\"/></w:settings>";

	private File dir;

	@Before
	public void setUp() throws IOException {
		new File("target").mkdirs();
		dir = Files.createTempDirectory(new File("target").toPath(), "markup").toFile();
	}

	private File zip(String name, Map<String, String> entries) throws IOException {
		File f = new File(dir, name);
		try (OutputStream os = Files.newOutputStream(f.toPath()); ZipOutputStream zip = new ZipOutputStream(os)) {
			for (Map.Entry<String, String> e : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey()));
				zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
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

	private File work() {
		File w = new File(dir, "work");
		w.mkdirs();
		return w;
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
		String settings = new String(read(copy, "word/settings.xml"), StandardCharsets.UTF_8);
		assertTrue(settings.contains("<w:zoom w:percent=\"100\"/>"));
		assertFalse(settings.contains("revisionView"));
		assertArrayEquals(read(src, "word/document.xml"), read(copy, "word/document.xml"));
		assertArrayEquals(read(src, "[Content_Types].xml"), read(copy, "[Content_Types].xml"));
		// The stored file keeps its markup setting
		assertTrue(new String(read(src, "word/settings.xml"), StandardCharsets.UTF_8).contains("revisionView"));
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

		File copy = MarkupPreparer.prepare(src, work());

		try (ZipFile a = new ZipFile(src); ZipFile b = new ZipFile(copy)) {
			assertEquals(a.size(), b.size());
		}
		for (String name : entries.keySet())
			assertArrayEquals(read(src, name), read(copy, name));
	}
}
