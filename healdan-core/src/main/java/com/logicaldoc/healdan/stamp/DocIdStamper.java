package com.logicaldoc.healdan.stamp;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Stamps the doc ID into a Word or LibreOffice file (M3 spec sections 9.7 and
 * 10): in a file that carries the {@code Healdan Doc ID} property, the
 * property becomes the given value, and so does the shown text of each field
 * that displays it, so any app or preview shows the number without updating
 * fields. Nothing else changes, and a file without the property is never
 * touched.
 * <ul>
 * <li>Word ({@code .docx}, {@code .docm}, {@code .dotx}, {@code .dotm}): the
 * property in {@code docProps/custom.xml}, and the result of each
 * {@code DOCPROPERTY "Healdan Doc ID"} field, simple or complex, in the parts
 * under {@code word/}.</li>
 * <li>LibreOffice ({@code .odt}, {@code .ott}): the user-defined property in
 * {@code meta.xml}, and each {@code text:user-defined} field showing it in
 * {@code content.xml} and {@code styles.xml}.</li>
 * </ul>
 * The zip is rewritten entry by entry, each keeping its place, name, time
 * and compression; a stored entry keeps its CRC, so ODF's {@code mimetype}
 * stays first and uncompressed. Only the parts that hold the property or a
 * field are parsed, with the JDK's DOM and no DTD, and only those that change
 * are written again; the others are copied as they are.
 * <p>
 * Stamping never makes a store fail (M3 plan, task 7). A file that isn't a
 * readable zip is left alone. So is a zip whose parts it can't read (one that
 * isn't well-formed, has a DTD, is over 32 MB or isn't in an encoding it
 * writes back), that inflates past 100 times its size or whose copy can't be
 * written, with a warning.
 */
public final class DocIdStamper {

	private static final Logger log = LoggerFactory.getLogger(DocIdStamper.class);

	/** The property's name (M3 plan, global constraints) */
	public static final String PROPERTY = "Healdan Doc ID";

	private static final Set<String> WORD_EXTENSIONS = Set.of("docx", "docm", "dotx", "dotm");

	private static final Set<String> ODF_EXTENSIONS = Set.of("odt", "ott");

	private static final String CUSTOM = "docProps/custom.xml";

	private static final String META = "meta.xml";

	/** The ODF parts with fields: the body's, and the headers' and footers' */
	private static final Set<String> ODF_FIELD_PARTS = Set.of("content.xml", "styles.xml");

	/** WordprocessingML, transitional and strict */
	private static final Set<String> WORDML = Set.of("http://schemas.openxmlformats.org/wordprocessingml/2006/main",
			"http://purl.oclc.org/ooxml/wordprocessingml/main");

	private static final Set<String> CUSTOM_PROPERTIES = Set.of(
			"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties",
			"http://purl.oclc.org/ooxml/officeDocument/customProperties");

	private static final String VARIANT_TYPES_TRANSITIONAL =
			"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes";

	private static final Set<String> VARIANT_TYPES = Set.of(VARIANT_TYPES_TRANSITIONAL,
			"http://purl.oclc.org/ooxml/officeDocument/docPropsVTypes");

	private static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";

	private static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";

	private static final String ODF_META = "urn:oasis:names:tc:opendocument:xmlns:meta:1.0";

	/** Only a Word part with a field is parsed */
	private static final List<byte[]> WORD_MARKERS = List.of(ascii("instrText"), ascii("fldSimple"));

	private static final List<byte[]> ODF_MARKERS = List.of(ascii("user-defined"));

	/** The largest part parsed */
	private static final int MAX_PART = 32 * 1024 * 1024;

	/** The most the copy may inflate to, as a multiple of the file's size (as {@code MarkupPreparer}) */
	private static final long MAX_RATIO = 100;

	private static final byte[] UTF8_BOM = { (byte) 0xef, (byte) 0xbb, (byte) 0xbf };

	/**
	 * A field's instruction: {@code DOCPROPERTY}, the property's name, quoted
	 * or not, and any switches, such as {@code \* MERGEFORMAT}
	 */
	private static final Pattern DOCPROPERTY = Pattern.compile(
			"\\s*DOCPROPERTY\\s+(?:\"([^\"]*)\"|([^\"\\\\]+?))\\s*(?:\\\\.*)?",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private DocIdStamper() {
	}

	/**
	 * @param docNumber the doc #
	 * @param fileVersion the version the file is stored as
	 * @return the doc ID, in the title's form: {@code #1234 v2.1}
	 */
	public static String value(long docNumber, String fileVersion) {
		return "#%d v%s".formatted(docNumber, fileVersion);
	}

	/**
	 * @param file a file
	 * @param extension the extension of the name it is stored under
	 * @return whether it is a Word or LibreOffice file that carries the
	 *         property
	 */
	public static boolean carriesProperty(Path file, String extension) {
		Format format = format(extension);
		if (format == null)
			return false;
		try (ZipFile zip = open(file)) {
			return zip != null && property(zip, format) != null;
		} catch (Unstampable | IOException | RuntimeException e) {
			log.debug("Cannot read the properties of {}: {}", file.getFileName(), e.getMessage());
			return false;
		}
	}

	/**
	 * Stamps the file in place, if it carries the property. Never refuses:
	 * a file it can't stamp is left as it was.
	 *
	 * @param file the file, which is replaced
	 * @param extension the extension of the name it is stored under
	 * @param value the doc ID ({@link #value(long, String)})
	 * @return true if it changed the file; false if the file is of another
	 *         format, doesn't carry the property, already shows the value or
	 *         can't be stamped
	 */
	public static boolean stamp(Path file, String extension, String value) {
		Objects.requireNonNull(value);
		Format format = format(extension);
		if (format == null)
			return false;
		ZipFile zip = open(file);
		if (zip == null)
			return false;

		Path copy = null;
		try {
			try (ZipFile in = zip) {
				Map<String, byte[]> replaced = stamped(in, format, value);
				if (replaced.isEmpty())
					return false;
				copy = Files.createTempFile(file.toAbsolutePath().getParent(), "healdan-stamp-", ".tmp");
				write(in, replaced, copy, MAX_RATIO * Math.max(1L, Files.size(file)));
			}
			replace(copy, file);
			log.debug("Stamped {} into {}", value, file.getFileName());
			return true;
		} catch (Unstampable e) {
			log.warn("Cannot stamp the doc ID {} into {}, storing it as sent: {}", value, file.getFileName(),
					e.getMessage());
			return false;
		} catch (IOException | RuntimeException e) {
			log.warn("Cannot stamp the doc ID {} into {}, storing it as sent", value, file.getFileName(), e);
			return false;
		} finally {
			deleteQuietly(copy);
		}
	}

	private static Format format(String extension) {
		String lower = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
		if (WORD_EXTENSIONS.contains(lower))
			return Format.WORD;
		if (ODF_EXTENSIONS.contains(lower))
			return Format.ODF;
		return null;
	}

	/**
	 * @return the file as a zip, null if it isn't a readable one
	 */
	private static ZipFile open(Path file) {
		try {
			return new ZipFile(file.toFile());
		} catch (IOException e) {
			// Then it carries no property
			log.debug("Not stamping {}, which isn't a readable zip: {}", file.getFileName(), e.getMessage());
			return null;
		}
	}

	/**
	 * @return the parts to replace, with their stamped content; none if the
	 *         file doesn't carry the property or already shows the value
	 */
	private static Map<String, byte[]> stamped(ZipFile zip, Format format, String value)
			throws Unstampable, IOException {
		Property property = property(zip, format);
		if (property == null)
			return Map.of();

		Map<String, byte[]> replaced = new LinkedHashMap<>();
		if (format.setProperty(property.element(), value))
			replaced.put(format.propertyPart, serialize(property.element().getOwnerDocument(), property.content()));

		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			if (entry.isDirectory() || !format.hasFields(entry.getName()))
				continue;
			byte[] content = read(zip, entry);
			if (format.markers.stream().noneMatch(marker -> contains(content, marker)))
				continue;
			Document part = parse(content, entry.getName());
			if (format.setFields(part, value))
				replaced.put(entry.getName(), serialize(part, content));
		}
		return replaced;
	}

	/** The property's element, and the content of the part it's in */
	private record Property(Element element, byte[] content) {
	}

	/**
	 * @return the property, null if the file doesn't carry it
	 */
	private static Property property(ZipFile zip, Format format) throws Unstampable, IOException {
		ZipEntry entry = zip.getEntry(format.propertyPart);
		if (entry == null || entry.isDirectory())
			return null;
		byte[] content = read(zip, entry);
		Element element = format.findProperty(parse(content, entry.getName()));
		return element != null ? new Property(element, content) : null;
	}

	private static byte[] read(ZipFile zip, ZipEntry entry) throws Unstampable, IOException {
		try (InputStream in = zip.getInputStream(entry)) {
			byte[] content = in.readNBytes(MAX_PART + 1);
			if (content.length > MAX_PART)
				throw new Unstampable("%s is larger than %d bytes".formatted(entry.getName(), MAX_PART));
			return content;
		}
	}

	/** The formats, and where each keeps the property and its fields */
	private enum Format {

		WORD(CUSTOM, WORD_MARKERS) {

			@Override
			boolean hasFields(String part) {
				return part.startsWith("word/") && part.endsWith(".xml");
			}

			@Override
			Element findProperty(Document custom) {
				Element root = custom.getDocumentElement();
				if (!CUSTOM_PROPERTIES.contains(root.getNamespaceURI()))
					return null;
				for (Element property : children(root))
					if ("property".equals(property.getLocalName()) && PROPERTY.equals(property.getAttribute("name")))
						return property;
				return null;
			}

			@Override
			boolean setProperty(Element property, String value) {
				List<Element> values = children(property);
				Element current = values.isEmpty() ? null : values.get(0);
				if (values.size() == 1 && VARIANT_TYPES.contains(current.getNamespaceURI())
						&& "lpwstr".equals(current.getLocalName())) {
					if (value.equals(current.getTextContent()))
						return false;
					current.setTextContent(value);
					return true;
				}

				// Another type, or none: the value becomes text, as Word and LibreOffice write it
				String namespace = current != null && VARIANT_TYPES.contains(current.getNamespaceURI())
						? current.getNamespaceURI()
						: VARIANT_TYPES_TRANSITIONAL;
				String prefix = current != null && namespace.equals(current.getNamespaceURI()) ? current.getPrefix()
						: property.lookupPrefix(namespace);
				if (prefix == null && !namespace.equals(property.lookupNamespaceURI(null))) {
					prefix = "vt";
					property.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:vt", namespace);
				}
				Element text = property.getOwnerDocument().createElementNS(namespace, qualified(prefix, "lpwstr"));
				text.setTextContent(value);
				while (property.getFirstChild() != null)
					property.removeChild(property.getFirstChild());
				property.appendChild(text);
				return true;
			}

			@Override
			boolean setFields(Document part, String value) throws Unstampable {
				return setWordFields(part, value);
			}
		},

		ODF(META, ODF_MARKERS) {

			@Override
			boolean hasFields(String part) {
				return ODF_FIELD_PARTS.contains(part);
			}

			@Override
			Element findProperty(Document meta) {
				NodeList properties = meta.getElementsByTagNameNS(ODF_META, "user-defined");
				for (int i = 0; i < properties.getLength(); i++) {
					Element property = (Element) properties.item(i);
					if (PROPERTY.equals(property.getAttributeNS(ODF_META, "name")))
						return property;
				}
				return null;
			}

			@Override
			boolean setProperty(Element property, String value) {
				boolean changed = setText(property, value);
				Attr type = property.getAttributeNodeNS(ODF_META, "value-type");
				if (type != null && !"string".equals(type.getValue())) {
					type.setValue("string");
					changed = true;
				}
				return changed;
			}

			@Override
			boolean setFields(Document part, String value) {
				boolean changed = false;
				NodeList fields = part.getElementsByTagNameNS(TEXT, "user-defined");
				for (int i = 0; i < fields.getLength(); i++) {
					Element field = (Element) fields.item(i);
					if (!PROPERTY.equals(field.getAttributeNS(TEXT, "name")))
						continue;
					changed |= setText(field, value);
					Attr shown = field.getAttributeNodeNS(OFFICE, "string-value");
					if (shown != null && !value.equals(shown.getValue())) {
						shown.setValue(value);
						changed = true;
					}
				}
				return changed;
			}
		};

		/** The part that holds the property */
		final String propertyPart;

		/** What a part with a field holds */
		final List<byte[]> markers;

		Format(String propertyPart, List<byte[]> markers) {
			this.propertyPart = propertyPart;
			this.markers = markers;
		}

		/** @return whether the part may hold fields */
		abstract boolean hasFields(String part);

		/** @return the property's element, null if the part has none */
		abstract Element findProperty(Document propertyPart);

		/** @return whether the property changed */
		abstract boolean setProperty(Element property, String value);

		/** @return whether any field showing the property changed */
		abstract boolean setFields(Document part, String value) throws Unstampable;
	}

	/** A complex field, from its {@code begin} to its {@code end} */
	private static final class Field {

		final StringBuilder instruction = new StringBuilder();

		Element separate;

		final List<Element> texts = new ArrayList<>();
	}

	/**
	 * Sets the result of each {@code DOCPROPERTY "Healdan Doc ID"} field:
	 * simple ones ({@code w:fldSimple}), and complex ones, whose
	 * {@code w:fldChar}s mark the start, the result and the end, possibly
	 * nested and over several runs. The first text of the result becomes the
	 * value and any others become empty; a result with no text gets a run.
	 *
	 * @return whether any field changed
	 */
	private static boolean setWordFields(Document part, String value) throws Unstampable {
		List<Element> all = new ArrayList<>();
		NodeList elements = part.getElementsByTagNameNS("*", "*");
		for (int i = 0; i < elements.getLength(); i++)
			all.add((Element) elements.item(i));

		boolean changed = false;
		Deque<Field> open = new ArrayDeque<>();
		for (Element e : all) {
			if (!WORDML.contains(e.getNamespaceURI()))
				continue;
			switch (e.getLocalName()) {
				case "fldSimple" -> {
					if (showsProperty(e.getAttributeNS(e.getNamespaceURI(), "instr")))
						changed |= setSimpleResult(e, value);
				}
				case "fldChar" -> {
					String type = e.getAttributeNS(e.getNamespaceURI(), "fldCharType");
					if ("begin".equals(type)) {
						open.push(new Field());
					} else if ("separate".equals(type) && !open.isEmpty()) {
						open.peek().separate = e;
					} else if ("end".equals(type) && !open.isEmpty()) {
						Field field = open.pop();
						if (showsProperty(field.instruction.toString()))
							changed |= setComplexResult(field, e, value);
					}
				}
				case "instrText" -> {
					if (!open.isEmpty() && open.peek().separate == null)
						open.peek().instruction.append(e.getTextContent());
				}
				case "t" -> {
					if (!open.isEmpty() && open.peek().separate != null)
						open.peek().texts.add(e);
				}
				default -> {
					// Nothing a field shows
				}
			}
		}
		return changed;
	}

	private static boolean showsProperty(String instruction) {
		Matcher matcher = DOCPROPERTY.matcher(instruction);
		if (!matcher.matches())
			return false;
		String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
		// Word finds the property whatever the case
		return PROPERTY.equalsIgnoreCase(name.strip());
	}

	private static boolean setSimpleResult(Element field, String value) {
		NodeList found = field.getElementsByTagNameNS(field.getNamespaceURI(), "t");
		List<Element> texts = new ArrayList<>();
		for (int i = 0; i < found.getLength(); i++)
			texts.add((Element) found.item(i));
		if (!texts.isEmpty())
			return setTexts(texts, value);
		field.appendChild(textRun(field, null, value));
		return true;
	}

	private static boolean setComplexResult(Field field, Element end, String value) throws Unstampable {
		if (!field.texts.isEmpty())
			return setTexts(field.texts, value);

		// No text shown yet: a run before the end's, after a separator if there's none
		Node endRun = end.getParentNode();
		if (!(endRun instanceof Element run) || !"r".equals(run.getLocalName()) || run.getParentNode() == null)
			throw new Unstampable("a field's end isn't in a run");
		Element properties = null;
		for (Element child : children(run))
			if ("rPr".equals(child.getLocalName()))
				properties = child;
		if (field.separate == null) {
			Element separator = element(end, "r");
			Element mark = element(end, "fldChar");
			mark.setAttributeNS(end.getNamespaceURI(), qualified(end.getPrefix(), "fldCharType"), "separate");
			separator.appendChild(mark);
			run.getParentNode().insertBefore(separator, run);
		}
		run.getParentNode().insertBefore(textRun(end, properties, value), run);
		return true;
	}

	/**
	 * The first text shows the value, and the others nothing; a result that
	 * already shows the value, in however many runs, is left as it is
	 */
	private static boolean setTexts(List<Element> texts, String value) {
		StringBuilder shown = new StringBuilder();
		for (Element text : texts)
			shown.append(text.getTextContent());
		if (value.contentEquals(shown))
			return false;
		boolean changed = false;
		for (int i = 0; i < texts.size(); i++)
			changed |= setText(texts.get(i), i == 0 ? value : "");
		return changed;
	}

	private static boolean setText(Element element, String text) {
		Node only = element.getFirstChild();
		boolean same = text.isEmpty() ? only == null
				: only != null && only.getNextSibling() == null && only.getNodeType() == Node.TEXT_NODE
						&& text.equals(only.getNodeValue());
		if (same)
			return false;
		element.setTextContent(text);
		return true;
	}

	/**
	 * @return {@code <w:r><w:t>value</w:t></w:r>}, in the namespace and with
	 *         the prefix of the given element, with a copy of the run
	 *         properties if any
	 */
	private static Element textRun(Element like, Element properties, String value) {
		Element run = element(like, "r");
		if (properties != null)
			run.appendChild(properties.cloneNode(true));
		Element text = element(like, "t");
		text.setTextContent(value);
		run.appendChild(text);
		return run;
	}

	private static Element element(Element like, String localName) {
		return like.getOwnerDocument().createElementNS(like.getNamespaceURI(), qualified(like.getPrefix(), localName));
	}

	private static String qualified(String prefix, String localName) {
		return prefix == null || prefix.isEmpty() ? localName : prefix + ":" + localName;
	}

	private static List<Element> children(Element parent) {
		List<Element> children = new ArrayList<>();
		for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling())
			if (child instanceof Element element)
				children.add(element);
		return children;
	}

	/**
	 * @return the part's DOM: namespace aware, with no DTD and no external
	 *         entities
	 * @throws Unstampable if it isn't well-formed or has a DTD
	 */
	private static Document parse(byte[] content, String name) throws Unstampable {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
			factory.setNamespaceAware(true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			// Not to the console: a part that isn't well-formed is reported once, as a warning
			builder.setErrorHandler(QUIET);
			return builder.parse(new ByteArrayInputStream(content));
		} catch (SAXException | IOException e) {
			throw new Unstampable("%s isn't well-formed XML without a DTD: %s".formatted(name, e.getMessage()));
		} catch (ParserConfigurationException e) {
			throw new IllegalStateException("The JDK's XML parser can't be set up safely", e);
		}
	}

	private static final ErrorHandler QUIET = new ErrorHandler() {

		@Override
		public void warning(SAXParseException exception) {
			// Ignored
		}

		@Override
		public void error(SAXParseException exception) throws SAXException {
			throw exception;
		}

		@Override
		public void fatalError(SAXParseException exception) throws SAXException {
			throw exception;
		}
	};

	/**
	 * Writes the part back in the encoding it declares, with its own XML
	 * declaration, byte for byte, and the whitespace after it
	 */
	private static byte[] serialize(Document part, byte[] original) throws Unstampable {
		byte[] prolog = prolog(original);
		try {
			TransformerFactory factory = TransformerFactory.newDefaultInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
			Transformer transformer = factory.newTransformer();
			transformer.setOutputProperty(OutputKeys.METHOD, "xml");
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
			transformer.setOutputProperty(OutputKeys.INDENT, "no");
			transformer.setOutputProperty(OutputKeys.ENCODING,
					part.getXmlEncoding() != null ? part.getXmlEncoding() : StandardCharsets.UTF_8.name());
			ByteArrayOutputStream out = new ByteArrayOutputStream(original.length + 256);
			out.writeBytes(prolog);
			transformer.transform(new DOMSource(part), new StreamResult(out));
			return out.toByteArray();
		} catch (TransformerException e) {
			throw new Unstampable("can't write a part back: " + e.getMessage());
		}
	}

	/**
	 * @return the part's byte order mark, XML declaration and the whitespace
	 *         after it, as they are; nothing for a part without them
	 * @throws Unstampable for a part that isn't in UTF-8 or another encoding
	 *         that writes {@code <} as one byte, such as UTF-16
	 */
	private static byte[] prolog(byte[] content) throws Unstampable {
		int start = startsWith(content, 0, UTF8_BOM) ? UTF8_BOM.length : 0;
		if (startsWith(content, start, ascii("<?xml"))) {
			int end = indexOf(content, ascii("?>"), start);
			if (end < 0)
				throw new Unstampable("a part's XML declaration has no end");
			end += 2;
			while (end < content.length && isWhitespace(content[end]))
				end++;
			return Arrays.copyOf(content, end);
		}
		if (start < content.length && (content[start] == '<' || isWhitespace(content[start])))
			return Arrays.copyOf(content, start);
		throw new Unstampable("a part isn't in an encoding the stamp writes back");
	}

	private static boolean isWhitespace(byte b) {
		return b == ' ' || b == '\t' || b == '\r' || b == '\n';
	}

	/**
	 * Copies every entry in its order, with the given parts replaced, failing
	 * once more than {@code budget} bytes have been written
	 */
	private static void write(ZipFile in, Map<String, byte[]> replaced, Path target, long budget) throws IOException {
		long written = 0;
		byte[] buffer = new byte[8192];
		try (OutputStream file = Files.newOutputStream(target);
				ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(file))) {
			if (in.getComment() != null)
				out.setComment(in.getComment());
			Enumeration<? extends ZipEntry> entries = in.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				byte[] replacement = replaced.get(entry.getName());
				out.putNextEntry(copy(entry, replacement));
				if (replacement != null) {
					written = count(written, replacement.length, budget);
					out.write(replacement);
				} else {
					try (InputStream data = in.getInputStream(entry)) {
						int read;
						while ((read = data.read(buffer)) > 0) {
							written = count(written, read, budget);
							out.write(buffer, 0, read);
						}
					}
				}
				out.closeEntry();
			}
		}
	}

	/**
	 * @return the entry to write: the same name, time, comment and
	 *         compression; a stored one with its size and CRC, the original's
	 *         unless its content is replaced
	 */
	private static ZipEntry copy(ZipEntry entry, byte[] replacement) {
		ZipEntry copy = new ZipEntry(entry.getName());
		if (entry.getTime() != -1)
			copy.setTime(entry.getTime());
		if (entry.getComment() != null)
			copy.setComment(entry.getComment());
		copy.setMethod(entry.getMethod());
		if (entry.getMethod() == ZipEntry.STORED) {
			long crc = entry.getCrc();
			long size = entry.getSize();
			if (replacement != null) {
				CRC32 replaced = new CRC32();
				replaced.update(replacement);
				crc = replaced.getValue();
				size = replacement.length;
			}
			copy.setSize(size);
			copy.setCompressedSize(size);
			copy.setCrc(crc);
		}
		return copy;
	}

	private static long count(long written, int more, long budget) throws IOException {
		long total = written + more;
		if (total > budget)
			throw new IOException("it inflates to more than " + MAX_RATIO + " times its size");
		return total;
	}

	/** Puts the stamped copy in the file's place, at once where the file system can */
	private static void replace(Path copy, Path file) throws IOException {
		try {
			Files.move(copy, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(copy, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void deleteQuietly(Path path) {
		if (path == null)
			return;
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			log.warn("Cannot delete {}: {}", path, e.getMessage());
		}
	}

	private static boolean startsWith(byte[] content, int offset, byte[] prefix) {
		if (content.length - offset < prefix.length)
			return false;
		for (int i = 0; i < prefix.length; i++)
			if (content[offset + i] != prefix[i])
				return false;
		return true;
	}

	private static int indexOf(byte[] content, byte[] what, int from) {
		for (int i = from; i <= content.length - what.length; i++)
			if (startsWith(content, i, what))
				return i;
		return -1;
	}

	private static boolean contains(byte[] content, byte[] what) {
		return indexOf(content, what, 0) >= 0;
	}

	private static byte[] ascii(String text) {
		return text.getBytes(StandardCharsets.US_ASCII);
	}

	/** The file isn't what a stamp expects; it is stored as sent */
	private static final class Unstampable extends Exception {

		private static final long serialVersionUID = 1L;

		Unstampable(String message) {
			super(message);
		}
	}
}
