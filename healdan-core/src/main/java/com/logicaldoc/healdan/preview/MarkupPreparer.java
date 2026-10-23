package com.logicaldoc.healdan.preview;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.StartDocument;
import javax.xml.stream.events.XMLEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes tracked changes visible to LibreOffice. A Word file saved with its
 * markup hidden carries a {@code revisionView} element in its settings part,
 * and LibreOffice then converts it without the deletions. The preview works
 * on a copy with that element removed; the stored file is never touched.
 * <p>
 * It reads the file as LibreOffice does: it finds the settings part through
 * the package's relationships, and the element by its namespace, whatever
 * its prefix, its encoding or the form of its tags. A settings part it can't
 * read, because it is too large or isn't well-formed XML, is replaced with an
 * empty one, so that no hidden-markup setting can get past it.
 * <p>
 * It runs before LibreOffice's timeout applies, so it is bounded: it reads at
 * most 4 MB of each part it parses, parses in linear time without a DTD,
 * copies only a file whose settings it changes, and gives up on a copy that
 * inflates past 100 times the file's size (the ratio POI's
 * {@code ZipSecureFile} allows by default).
 */
public final class MarkupPreparer {

	private static final Logger log = LoggerFactory.getLogger(MarkupPreparer.class);

	/** The settings part's usual name, used when the relationships name none */
	private static final String SETTINGS = "word/settings.xml";

	/** WordprocessingML, transitional and strict */
	private static final Set<String> WORDML = Set.of("http://schemas.openxmlformats.org/wordprocessingml/2006/main",
			"http://purl.oclc.org/ooxml/wordprocessingml/main");

	private static final String REVISION_VIEW = "revisionView";

	/** What replaces a settings part that can't be read */
	private static final byte[] EMPTY_SETTINGS = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
			+ "<w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>")
			.getBytes(StandardCharsets.UTF_8);

	/** The largest part parsed; a real settings part is a few kilobytes */
	private static final int MAX_PART = 4 * 1024 * 1024;

	/** The most the copy may inflate to, as a multiple of the file's size */
	private static final long MAX_RATIO = 100;

	private static final XMLEventFactory EVENTS = XMLEventFactory.newDefaultFactory();

	private MarkupPreparer() {
	}

	/**
	 * @param src the file to convert
	 * @param workDir where to put the copy
	 * @return a copy of the file, with the same name, in {@code workDir} when the
	 *         format can hide markup and the file is a readable zip that hides
	 *         it or has settings that can't be read; otherwise {@code src}
	 * @throws IOException if a partial copy can't be deleted
	 */
	public static File prepare(File src, File workDir) throws IOException {
		if (!OfficeFormats.hasHideableMarkup(src.getName()))
			return src;

		ZipFile zip;
		try {
			zip = new ZipFile(src);
		} catch (IOException e) {
			// Not a readable zip: let LibreOffice have its say about it
			log.debug("Cannot prepare markup of {}: {}", src.getName(), e.getMessage());
			return src;
		}

		File copy = new File(workDir, src.getName());
		try (ZipFile in = zip) {
			Map<String, byte[]> replaced = new LinkedHashMap<>();
			for (String part : settingsParts(in)) {
				byte[] unhidden = unhidden(in, in.getEntry(part), src.getName());
				if (unhidden != null)
					replaced.put(part, unhidden);
			}
			if (replaced.isEmpty())
				return src;

			copy(in, replaced, copy, MAX_RATIO * src.length());
			return copy;
		} catch (IOException e) {
			log.warn("Cannot show the tracked changes of {}, converting it as it is: {}", src.getName(),
					e.getMessage());
			Files.deleteIfExists(copy.toPath());
			return src;
		}
	}

	/**
	 * @return the settings part without its {@code revisionView} elements, an
	 *         empty settings part if it can't be read, or null if it hides
	 *         nothing
	 */
	private static byte[] unhidden(ZipFile zip, ZipEntry part, String fileName) throws IOException {
		byte[] settings = read(zip, part);
		if (settings == null) {
			log.warn("The settings of {} are larger than {} bytes; converting it without them", fileName, MAX_PART);
			return EMPTY_SETTINGS;
		}
		try {
			return withoutRevisionView(settings);
		} catch (XMLStreamException e) {
			log.warn("The settings of {} aren't well-formed; converting it without them: {}", fileName,
					e.getMessage());
			return EMPTY_SETTINGS;
		}
	}

	/**
	 * @return the part's content, null if it is larger than {@link #MAX_PART}
	 */
	private static byte[] read(ZipFile zip, ZipEntry part) throws IOException {
		try (InputStream data = zip.getInputStream(part)) {
			byte[] content = data.readNBytes(MAX_PART + 1);
			return content.length > MAX_PART ? null : content;
		}
	}

	private static XMLInputFactory inputFactory() {
		XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		return factory;
	}

	/**
	 * @param settings a settings part, in the encoding it declares
	 * @return the part, in UTF-8, without its WordprocessingML
	 *         {@code revisionView} elements; null if it has none
	 * @throws XMLStreamException if it isn't well-formed XML
	 */
	private static byte[] withoutRevisionView(byte[] settings) throws XMLStreamException {
		XMLEventReader reader = inputFactory().createXMLEventReader(new ByteArrayInputStream(settings));
		ByteArrayOutputStream out = new ByteArrayOutputStream(settings.length);
		XMLEventWriter writer = XMLOutputFactory.newDefaultFactory().createXMLEventWriter(out, "UTF-8");
		boolean removed = false;
		int skipping = 0;
		try {
			while (reader.hasNext()) {
				XMLEvent event = reader.nextEvent();
				if (skipping > 0) {
					if (event.isStartElement())
						skipping++;
					else if (event.isEndElement())
						skipping--;
				} else if (event.isStartElement() && isRevisionView(event.asStartElement().getName())) {
					removed = true;
					skipping = 1;
				} else {
					writer.add(event.isStartDocument() ? inUtf8((StartDocument) event) : event);
				}
			}
			writer.close();
		} finally {
			reader.close();
		}
		return removed ? out.toByteArray() : null;
	}

	private static boolean isRevisionView(QName name) {
		return REVISION_VIEW.equals(name.getLocalPart()) && WORDML.contains(name.getNamespaceURI());
	}

	/** The copy is written in UTF-8, whatever the original declared */
	private static StartDocument inUtf8(StartDocument start) {
		return start.standaloneSet() ? EVENTS.createStartDocument("UTF-8", start.getVersion(), start.isStandalone())
				: EVENTS.createStartDocument("UTF-8", start.getVersion());
	}

	/**
	 * Finds the settings parts as LibreOffice does: the package's
	 * relationships name the main document, and the main document's name its
	 * settings.
	 *
	 * @return the names of the entries that hold settings
	 */
	private static List<String> settingsParts(ZipFile zip) throws IOException {
		List<String> parts = new ArrayList<>();
		for (String main : targets(zip, "", "/officeDocument"))
			for (String part : targets(zip, main, "/settings"))
				if (zip.getEntry(part) != null && !parts.contains(part))
					parts.add(part);
		if (parts.isEmpty() && zip.getEntry(SETTINGS) != null)
			parts.add(SETTINGS);
		return parts;
	}

	/**
	 * @param source the part whose relationships are read, "" for the package
	 * @param type the end of the relationship type
	 * @return the entry names the part's internal relationships of that type
	 *         point to; none if the relationships can't be read
	 */
	private static List<String> targets(ZipFile zip, String source, String type) throws IOException {
		int slash = source.lastIndexOf('/');
		String rels = source.substring(0, slash + 1) + "_rels/" + source.substring(slash + 1) + ".rels";
		ZipEntry entry = zip.getEntry(rels);
		if (entry == null)
			return List.of();
		byte[] content = read(zip, entry);
		if (content == null)
			return List.of();

		List<String> targets = new ArrayList<>();
		try {
			XMLStreamReader reader = inputFactory().createXMLStreamReader(new ByteArrayInputStream(content));
			try {
				while (reader.hasNext()) {
					if (reader.next() == XMLStreamReader.START_ELEMENT
							&& "Relationship".equals(reader.getLocalName())
							&& String.valueOf(reader.getAttributeValue(null, "Type")).endsWith(type)
							&& !"External".equals(reader.getAttributeValue(null, "TargetMode"))
							&& reader.getAttributeValue(null, "Target") != null)
						targets.add(resolve(source, reader.getAttributeValue(null, "Target")));
				}
			} finally {
				reader.close();
			}
		} catch (XMLStreamException | URISyntaxException | IllegalArgumentException e) {
			log.debug("Cannot read the relationships in {}: {}", rels, e.getMessage());
		}
		return targets;
	}

	/** @return the entry name of a relationship's target, which is relative to its source part */
	private static String resolve(String source, String target) throws URISyntaxException {
		String path = new URI(null, null, "/" + source, null).resolve(new URI(null, null, target, null)).getPath();
		return path.startsWith("/") ? path.substring(1) : path;
	}

	/**
	 * Copies every entry, with the given parts replaced, failing once more
	 * than {@code budget} bytes have been written.
	 */
	private static void copy(ZipFile in, Map<String, byte[]> replaced, File copy, long budget) throws IOException {
		long written = 0;
		byte[] buffer = new byte[8192];
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(copy.toPath()))) {
			Enumeration<? extends ZipEntry> entries = in.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				ZipEntry copied = new ZipEntry(entry.getName());
				copied.setTime(entry.getTime());
				out.putNextEntry(copied);
				byte[] replacement = replaced.get(entry.getName());
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

	private static long count(long written, int more, long budget) throws IOException {
		long total = written + more;
		if (total > budget)
			throw new IOException("it inflates to more than " + MAX_RATIO + " times its size");
		return total;
	}
}
