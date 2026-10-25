package com.logicaldoc.healdan.stamp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Builds, by hand, the files the stamping tests use: a Word document and a
 * LibreOffice one that carry the {@code Healdan Doc ID} property and fields
 * showing it, as Word and LibreOffice save them, and copies without the
 * property. Reads back what a stamp wrote.
 * <p>
 * The Word document's footer has the field in both of Word's forms: a simple
 * field, and a complex one whose instruction and result are each split over
 * two runs, as Word splits them after an edit. It also shows another
 * property, Client, which a stamp must leave alone, and text beyond the
 * Basic Multilingual Plane, which must survive the rewrite.
 */
public final class StampFixtures {

	/** The property's name (M3 plan, global constraints) */
	public static final String PROPERTY = "Healdan Doc ID";

	/** The footer's part, in the Word document */
	public static final String FOOTER = "word/footer1.xml";

	/** The custom properties' part, in the Word document */
	public static final String CUSTOM = "docProps/custom.xml";

	/** Text in the Word footer, with an emoji (U+1F4CE) and accented letters */
	public static final String FOOTER_TEXT = "Draft \uD83D\uDCCE \u2013 caf\u00e9";

	/** What the Client field shows */
	public static final String CLIENT = "Healdan Ltd";

	private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

	private static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";

	private static final String META = "urn:oasis:names:tc:opendocument:xmlns:meta:1.0";

	private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n";

	/** A thumbnail's bytes, stored uncompressed as LibreOffice stores its own */
	private static final byte[] THUMBNAIL = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0 };

	private StampFixtures() {
	}

	/**
	 * @param value the property's value, which the fields show too
	 * @return a Word document that carries the property
	 */
	public static byte[] docx(String value) {
		return zip(docxParts(value, true), Set.of());
	}

	/**
	 * @return a Word document with the fields but no property
	 */
	public static byte[] docxWithoutProperty() {
		return zip(docxParts("#999 v9.9", false), Set.of());
	}

	/**
	 * @param value the property's value, which the fields show too
	 * @param withProperty whether it has the custom properties part
	 * @return the Word document's parts, in order, to change and then
	 *         {@link #zip}
	 */
	public static Map<String, byte[]> docxParts(String value, boolean withProperty) {
		Map<String, byte[]> parts = new LinkedHashMap<>();
		parts.put("[Content_Types].xml", utf8(DECLARATION + """
				<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
				<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
				<Default Extension="xml" ContentType="application/xml"/>\
				<Override PartName="/word/document.xml" \
				ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>\
				<Override PartName="/word/footer1.xml" \
				ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>\
				<Override PartName="/docProps/custom.xml" \
				ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/>\
				</Types>"""));
		parts.put("_rels/.rels", utf8(DECLARATION + """
				<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
				<Relationship Id="rId1" \
				Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" \
				Target="word/document.xml"/>\
				<Relationship Id="rId2" \
				Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties" \
				Target="docProps/custom.xml"/>\
				</Relationships>"""));
		parts.put("word/document.xml", utf8(DECLARATION + """
				<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" \
				xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
				<w:body><w:p><w:r><w:t>Lease</w:t></w:r></w:p>\
				<w:sectPr><w:footerReference w:type="default" r:id="rId9"/></w:sectPr></w:body></w:document>"""));
		parts.put("word/_rels/document.xml.rels", utf8(DECLARATION + """
				<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
				<Relationship Id="rId9" \
				Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" \
				Target="footer1.xml"/>\
				</Relationships>"""));
		// The value's halves, as two runs
		int half = value.indexOf(' ') > 0 ? value.indexOf(' ') : value.length();
		parts.put(FOOTER, utf8(DECLARATION + """
				<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" \
				xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" mc:Ignorable="">\
				<w:p><w:r><w:t xml:space="preserve">%s </w:t></w:r>\
				<w:fldSimple w:instr=" DOCPROPERTY &quot;Healdan Doc ID&quot; \\* MERGEFORMAT ">\
				<w:r><w:rPr><w:b/></w:rPr><w:t>%s</w:t></w:r></w:fldSimple></w:p>\
				<w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r>\
				<w:r><w:instrText xml:space="preserve"> DOCPROPERTY  "Healdan </w:instrText></w:r>\
				<w:r><w:instrText xml:space="preserve">Doc ID" </w:instrText></w:r>\
				<w:r><w:fldChar w:fldCharType="separate"/></w:r>\
				<w:r><w:t>%s</w:t></w:r><w:r><w:t xml:space="preserve">%s</w:t></w:r>\
				<w:r><w:fldChar w:fldCharType="end"/></w:r>\
				<w:r><w:t xml:space="preserve"> for </w:t></w:r>\
				<w:fldSimple w:instr=" DOCPROPERTY &quot;Client&quot; "><w:r><w:t>%s</w:t></w:r></w:fldSimple></w:p>\
				</w:ftr>""".formatted(FOOTER_TEXT, value, value.substring(0, half), value.substring(half), CLIENT)));
		if (withProperty)
			parts.put(CUSTOM, utf8(DECLARATION + """
					<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" \
					xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">\
					<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="2" name="Client">\
					<vt:lpwstr>%s</vt:lpwstr></property>\
					<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="3" name="Healdan Doc ID">\
					<vt:lpwstr>%s</vt:lpwstr></property></Properties>""".formatted(CLIENT, value)));
		return parts;
	}

	/**
	 * @param value the property's value, which the fields show too
	 * @return a LibreOffice document that carries the property
	 */
	public static byte[] odt(String value) {
		return zip(odtParts(value, true), Set.of("mimetype", "Thumbnails/thumbnail.png"));
	}

	/**
	 * @return a LibreOffice document with the fields but no property
	 */
	public static byte[] odtWithoutProperty() {
		return zip(odtParts("#999 v9.9", false), Set.of("mimetype", "Thumbnails/thumbnail.png"));
	}

	/**
	 * @return the LibreOffice document's parts, in order: the {@code mimetype}
	 *         first, and the field in the footer, in {@code styles.xml}, and
	 *         in the body
	 */
	public static Map<String, byte[]> odtParts(String value, boolean withProperty) {
		String odf = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
				+ "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" "
				+ "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" "
				+ "xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\" office:version=\"1.3\"";
		String field = "<text:user-defined text:name=\"Healdan Doc ID\">%s</text:user-defined>".formatted(value);
		Map<String, byte[]> parts = new LinkedHashMap<>();
		parts.put("mimetype", utf8("application/vnd.oasis.opendocument.text"));
		parts.put("Thumbnails/thumbnail.png", THUMBNAIL);
		parts.put("content.xml", utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<office:document-content %s><office:body><office:text><text:p>Lease, %s</text:p>".formatted(odf,
						field)
				+ "</office:text></office:body></office:document-content>"));
		parts.put("styles.xml", utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<office:document-styles %s><office:master-styles>".formatted(odf)
				+ "<style:master-page style:name=\"Standard\"><style:footer><text:p>%s %s</text:p>".formatted(
						FOOTER_TEXT, field)
				+ "</style:footer></style:master-page></office:master-styles></office:document-styles>"));
		parts.put("meta.xml", utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<office:document-meta %s><office:meta><meta:generator>LibreOffice</meta:generator>".formatted(odf)
				+ (withProperty
						? "<meta:user-defined meta:name=\"Client\">%s</meta:user-defined>".formatted(CLIENT)
								+ "<meta:user-defined meta:name=\"Healdan Doc ID\">%s</meta:user-defined>"
										.formatted(value)
						: "")
				+ "</office:meta></office:document-meta>"));
		parts.put("META-INF/manifest.xml", utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" "
				+ "manifest:version=\"1.3\"><manifest:file-entry manifest:full-path=\"/\" manifest:version=\"1.3\" "
				+ "manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>"
				+ "<manifest:file-entry manifest:full-path=\"Thumbnails/thumbnail.png\" manifest:media-type=\"image/png\"/>"
				+ "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>"
				+ "<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>"
				+ "<manifest:file-entry manifest:full-path=\"meta.xml\" manifest:media-type=\"text/xml\"/>"
				+ "</manifest:manifest>"));
		return parts;
	}

	/**
	 * @param parts the entries' names and contents, in order
	 * @param stored the entries to store uncompressed; the others are
	 *        deflated
	 * @return the zip
	 */
	public static byte[] zip(Map<String, byte[]> parts, Set<String> stored) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, byte[]> part : parts.entrySet()) {
				ZipEntry entry = new ZipEntry(part.getKey());
				if (stored.contains(part.getKey())) {
					CRC32 crc = new CRC32();
					crc.update(part.getValue());
					entry.setMethod(ZipEntry.STORED);
					entry.setSize(part.getValue().length);
					entry.setCompressedSize(part.getValue().length);
					entry.setCrc(crc.getValue());
				}
				zip.putNextEntry(entry);
				zip.write(part.getValue());
				zip.closeEntry();
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out.toByteArray();
	}

	/**
	 * @param zip a zip
	 * @return its entries' names and contents, in the order they're in
	 */
	public static Map<String, byte[]> entries(byte[] zip) {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		for (Entry entry : read(zip))
			entries.put(entry.name(), entry.content());
		return entries;
	}

	/** An entry as a zip holds it */
	public record Entry(String name, int method, long crc, byte[] content) {
	}

	/**
	 * @param zip a zip
	 * @return its entries, in the order they're in
	 */
	public static List<Entry> read(byte[] zip) {
		List<Entry> entries = new ArrayList<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				byte[] content = in.readAllBytes();
				entries.add(new Entry(entry.getName(), entry.getMethod(), entry.getCrc(), content));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return entries;
	}

	/**
	 * @param docx a Word document
	 * @param name a custom property's name
	 * @return its value, null if it has none
	 */
	public static String docxProperty(byte[] docx, String name) {
		byte[] custom = entries(docx).get(CUSTOM);
		if (custom == null)
			return null;
		NodeList properties = parse(custom).getDocumentElement().getChildNodes();
		for (int i = 0; i < properties.getLength(); i++)
			if (properties.item(i) instanceof Element property && name.equals(property.getAttribute("name")))
				return property.getTextContent();
		return null;
	}

	/**
	 * @param docx a Word document
	 * @param name a property's name
	 * @return the text each field in the footer that shows the property shows,
	 *         in order
	 */
	public static List<String> docxFieldTexts(byte[] docx, String name) {
		Document footer = parse(entries(docx).get(FOOTER));
		List<String> texts = new ArrayList<>();
		NodeList all = footer.getElementsByTagNameNS(W, "*");
		StringBuilder instruction = null;
		StringBuilder result = null;
		for (int i = 0; i < all.getLength(); i++) {
			Element e = (Element) all.item(i);
			switch (e.getLocalName()) {
				case "fldSimple" -> {
					if (shows(e.getAttributeNS(W, "instr"), name))
						texts.add(e.getTextContent());
				}
				case "fldChar" -> {
					String type = e.getAttributeNS(W, "fldCharType");
					if ("begin".equals(type)) {
						instruction = new StringBuilder();
						result = null;
					} else if ("separate".equals(type)) {
						result = new StringBuilder();
					} else if ("end".equals(type) && instruction != null) {
						if (shows(instruction.toString(), name))
							texts.add(result != null ? result.toString() : "");
						instruction = null;
						result = null;
					}
				}
				case "instrText" -> {
					if (instruction != null && result == null)
						instruction.append(e.getTextContent());
				}
				case "t" -> {
					if (result != null)
						result.append(e.getTextContent());
				}
				default -> {
					// Not part of a field
				}
			}
		}
		return texts;
	}

	private static boolean shows(String instruction, String name) {
		return instruction.replaceAll("\\s+", " ").strip().startsWith("DOCPROPERTY \"" + name + "\"");
	}

	/**
	 * @param zip a Word or LibreOffice document
	 * @param part one of its parts
	 * @return the part's text
	 */
	public static String text(byte[] zip, String part) {
		return parse(entries(zip).get(part)).getDocumentElement().getTextContent();
	}

	/**
	 * @param odt a LibreOffice document
	 * @param name a user-defined property's name
	 * @return its value, null if it has none
	 */
	public static String odtProperty(byte[] odt, String name) {
		NodeList properties = parse(entries(odt).get("meta.xml")).getElementsByTagNameNS(META, "user-defined");
		for (int i = 0; i < properties.getLength(); i++) {
			Element property = (Element) properties.item(i);
			if (name.equals(property.getAttributeNS(META, "name")))
				return property.getTextContent();
		}
		return null;
	}

	/**
	 * @param odt a LibreOffice document
	 * @return the text each field showing the property shows: the footer's,
	 *         then the body's
	 */
	public static List<String> odtFieldTexts(byte[] odt) {
		List<String> texts = new ArrayList<>();
		for (String part : List.of("styles.xml", "content.xml")) {
			NodeList fields = parse(entries(odt).get(part)).getElementsByTagNameNS(TEXT, "user-defined");
			for (int i = 0; i < fields.getLength(); i++) {
				Element field = (Element) fields.item(i);
				if (PROPERTY.equals(field.getAttributeNS(TEXT, "name")))
					texts.add(field.getTextContent());
			}
		}
		return texts;
	}

	private static Document parse(byte[] xml) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
			factory.setNamespaceAware(true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
		} catch (Exception e) {
			throw new IllegalStateException("Not well-formed: " + new String(xml, StandardCharsets.UTF_8), e);
		}
	}

	private static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}
