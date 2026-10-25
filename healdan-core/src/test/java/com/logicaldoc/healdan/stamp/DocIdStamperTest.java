package com.logicaldoc.healdan.stamp;

import static com.logicaldoc.healdan.stamp.StampFixtures.CLIENT;
import static com.logicaldoc.healdan.stamp.StampFixtures.CUSTOM;
import static com.logicaldoc.healdan.stamp.StampFixtures.FOOTER;
import static com.logicaldoc.healdan.stamp.StampFixtures.FOOTER_TEXT;
import static com.logicaldoc.healdan.stamp.StampFixtures.PROPERTY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.healdan.stamp.StampFixtures.Entry;

/**
 * Stamping the doc ID (M3 spec sections 9.7 and 10): only the property and
 * the shown text of the fields showing it change, only in files that carry
 * the property, and the zip keeps its entries' order and compression.
 */
public class DocIdStamperTest {

	private static final String VALUE = "#12 v1.0";

	private File dir;

	@Before
	public void setUp() throws IOException {
		new File("target").mkdirs();
		dir = Files.createTempDirectory(new File("target").toPath(), "stamp").toFile();
	}

	private Path write(String name, byte[] content) throws IOException {
		Path file = new File(dir, name).toPath();
		Files.write(file, content);
		return file;
	}

	@Test
	public void testDocxPropertyAndFields() throws Exception {
		byte[] original = StampFixtures.docx("#999 v9.9");
		Path file = write("a.docx", original);
		assertTrue(DocIdStamper.carriesProperty(file, "docx"));

		assertTrue(DocIdStamper.stamp(file, "docx", VALUE));

		byte[] stamped = Files.readAllBytes(file);
		assertEquals(VALUE, StampFixtures.docxProperty(stamped, PROPERTY));
		assertEquals(List.of(VALUE, VALUE), StampFixtures.docxFieldTexts(stamped, PROPERTY));
		// Nothing else changes: the other property, the field showing it, the footer's text
		assertEquals(CLIENT, StampFixtures.docxProperty(stamped, "Client"));
		assertEquals(List.of(CLIENT), StampFixtures.docxFieldTexts(stamped, "Client"));
		assertTrue(StampFixtures.text(stamped, FOOTER), StampFixtures.text(stamped, FOOTER).startsWith(FOOTER_TEXT));
		assertTrue(new String(StampFixtures.entries(stamped).get(CUSTOM), StandardCharsets.UTF_8)
				.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n<Properties "));
		assertOthersUnchanged(original, stamped, Set.of(CUSTOM, FOOTER));
		assertTrue(DocIdStamper.carriesProperty(file, "docx"));
	}

	@Test
	public void testOdtPropertyAndField() throws Exception {
		byte[] original = StampFixtures.odt("#999 v9.9");
		Path file = write("a.odt", original);
		assertTrue(DocIdStamper.carriesProperty(file, "odt"));

		assertTrue(DocIdStamper.stamp(file, "odt", VALUE));

		byte[] stamped = Files.readAllBytes(file);
		assertEquals(VALUE, StampFixtures.odtProperty(stamped, PROPERTY));
		assertEquals(CLIENT, StampFixtures.odtProperty(stamped, "Client"));
		// The footer's, then the body's
		assertEquals(List.of(VALUE, VALUE), StampFixtures.odtFieldTexts(stamped));
		assertTrue(StampFixtures.text(stamped, "styles.xml").startsWith(FOOTER_TEXT));
		assertOthersUnchanged(original, stamped, Set.of("meta.xml", "styles.xml", "content.xml"));
		// ODF's rule: the mimetype first, uncompressed
		Entry first = StampFixtures.read(stamped).get(0);
		assertEquals("mimetype", first.name());
		assertEquals(ZipEntry.STORED, first.method());
	}

	/**
	 * Each entry the stamp didn't change has the same content, and every
	 * entry keeps its place and its compression; a stored one keeps its CRC
	 */
	private static void assertOthersUnchanged(byte[] original, byte[] stamped, Set<String> changed) {
		List<Entry> before = StampFixtures.read(original);
		List<Entry> after = StampFixtures.read(stamped);
		assertEquals(before.stream().map(Entry::name).toList(), after.stream().map(Entry::name).toList());
		for (int i = 0; i < before.size(); i++) {
			Entry was = before.get(i);
			Entry is = after.get(i);
			assertEquals(was.name(), was.method(), is.method());
			if (!changed.contains(was.name()))
				assertArrayEquals(was.name(), was.content(), is.content());
			if (was.method() == ZipEntry.STORED && !changed.contains(was.name()))
				assertEquals(was.name(), was.crc(), is.crc());
		}
	}

	@Test
	public void testWithoutPropertyUntouched() throws Exception {
		// Fields without the property are left as they are
		for (Map.Entry<String, byte[]> file : Map.of("a.docx", StampFixtures.docxWithoutProperty(), "a.odt",
				StampFixtures.odtWithoutProperty(), "plain.docx",
				"Lease draft".getBytes(StandardCharsets.UTF_8)).entrySet()) {
			Path path = write(file.getKey(), file.getValue());
			String extension = file.getKey().substring(file.getKey().indexOf('.') + 1);

			assertFalse(file.getKey(), DocIdStamper.carriesProperty(path, extension));
			assertFalse(file.getKey(), DocIdStamper.stamp(path, extension, VALUE));

			assertArrayEquals(file.getKey(), file.getValue(), Files.readAllBytes(path));
		}
	}

	@Test
	public void testSameValueUntouched() throws Exception {
		byte[] original = StampFixtures.docx(VALUE);
		Path file = write("a.docx", original);

		assertFalse(DocIdStamper.stamp(file, "docx", VALUE));

		assertArrayEquals(original, Files.readAllBytes(file));
	}

	@Test
	public void testOtherFormat() throws Exception {
		byte[] original = StampFixtures.docx("#999 v9.9");
		Path file = write("a.xlsx", original);

		assertFalse(DocIdStamper.carriesProperty(file, "xlsx"));
		assertFalse(DocIdStamper.stamp(file, "xlsx", VALUE));

		assertArrayEquals(original, Files.readAllBytes(file));
	}

	@Test
	public void testStampedFormats() throws Exception {
		for (String extension : List.of("docx", "DOCM", "dotx", "dotm", "odt", "OTT")) {
			boolean word = extension.toLowerCase().startsWith("do");
			Path file = write("a." + extension, word ? StampFixtures.docx("#1 v1.0") : StampFixtures.odt("#1 v1.0"));

			assertTrue(extension, DocIdStamper.stamp(file, extension, VALUE));

			byte[] stamped = Files.readAllBytes(file);
			assertEquals(extension, VALUE, word ? StampFixtures.docxProperty(stamped, PROPERTY)
					: StampFixtures.odtProperty(stamped, PROPERTY));
		}
	}

	/**
	 * A part it can't read leaves the whole file as it was (task 7's ruling):
	 * a footer that isn't well-formed, and a property part with a DTD, which
	 * is never read
	 */
	@Test
	public void testUnreadablePartLeavesTheFile() throws Exception {
		Map<String, byte[]> broken = StampFixtures.docxParts("#999 v9.9", true);
		broken.put(FOOTER, "<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
				.concat("<w:fldSimple w:instr=\" DOCPROPERTY &quot;Healdan Doc ID&quot; \"><w:r><w:t>x</w:r>")
				.getBytes(StandardCharsets.UTF_8));
		Map<String, byte[]> dtd = StampFixtures.docxParts("#999 v9.9", true);
		dtd.put(CUSTOM, new String(dtd.get(CUSTOM), StandardCharsets.UTF_8)
				.replace("<Properties ", "<!DOCTYPE Properties [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Properties ")
				.getBytes(StandardCharsets.UTF_8));

		for (Map<String, byte[]> parts : List.of(broken, dtd)) {
			byte[] original = StampFixtures.zip(parts, Set.of());
			Path file = write("a.docx", original);

			assertFalse(DocIdStamper.stamp(file, "docx", VALUE));

			assertArrayEquals(original, Files.readAllBytes(file));
		}
		// Nothing left behind beside the file
		assertEquals(List.of("a.docx"), List.of(dir.list()));
	}

	@Test
	public void testValue() {
		assertEquals("#1234 v2.1", DocIdStamper.value(1234L, "2.1"));
	}
}
