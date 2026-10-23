package com.logicaldoc.healdan.parser;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ooxml.util.DocumentHelper;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.openxmlformats.schemas.officeDocument.x2006.extendedProperties.CTProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.logicaldoc.util.io.FileUtil;

/**
 * Counts the pages of Office 2007+ files from what the file itself records,
 * without laying it out: the page count Word saved, every slide of a
 * presentation (hidden ones too, as PowerPoint counts them) and the visible
 * sheets of a workbook (as CE counts the sheets of an OpenDocument
 * spreadsheet). A file that records no count, or can't be read, counts 0.
 */
public final class OfficePages {

	private static final Logger log = LoggerFactory.getLogger(OfficePages.class);

	/** Reads one kind of count from an open file */
	@FunctionalInterface
	interface Counter {

		/**
		 * @return the count, 0 if the file records none
		 */
		int count(OPCPackage pkg) throws Exception;
	}

	/** Word: the page count Word saved in the file's properties */
	static final Counter SAVED_PAGES = OfficePages::savedPages;

	/** PowerPoint: the slides */
	static final Counter SLIDES = OfficePages::slides;

	/** Excel: the sheets that aren't hidden */
	static final Counter VISIBLE_SHEETS = OfficePages::visibleSheets;

	private static final Map<String, Counter> COUNTERS = Map.of("docx", SAVED_PAGES, "docm", SAVED_PAGES, "dotx",
			SAVED_PAGES, "pptx", SLIDES, "ppsx", SLIDES, "xlsx", VISIBLE_SHEETS, "xlsm", VISIBLE_SHEETS);

	private OfficePages() {
	}

	/**
	 * @return the extensions whose pages Healdan counts, in lower case
	 */
	public static Set<String> extensions() {
		return COUNTERS.keySet();
	}

	/**
	 * @param fileName the file name
	 * @return true for the Word formats, whose count is the one Word saved
	 */
	public static boolean isWord(String fileName) {
		return counterOf(fileName) == SAVED_PAGES;
	}

	/**
	 * @param fileName the file name
	 * @return true if it has one of the {@link #extensions()}
	 */
	public static boolean counts(String fileName) {
		return counterOf(fileName) != null;
	}

	private static Counter counterOf(String fileName) {
		String extension = FilenameUtils.getExtension(fileName);
		return extension != null ? COUNTERS.get(extension.toLowerCase(Locale.ROOT)) : null;
	}

	/**
	 * @param in the file's content
	 * @param fileName the file name, whose extension says how to count
	 * @return the count, 0 if the file records none, can't be read or has
	 *         none of the {@link #extensions()}
	 */
	public static int count(InputStream in, String fileName) {
		Counter counter = counterOf(fileName);
		return counter != null ? count(in, fileName, counter) : 0;
	}

	/**
	 * Counts from a copy of the stream in a temporary file, which POI reads
	 * without loading the whole file in memory.
	 */
	static int count(InputStream in, String fileName, Counter counter) {
		File tmp = null;
		try {
			tmp = FileUtil.createTempFile("countpages", ".zip");
			Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return count(tmp, fileName, counter);
		} catch (Exception e) {
			Interrupts.keep(e);
			log.warn("Cannot count the pages of {}: {}", fileName, e.toString());
			return 0;
		} finally {
			FileUtil.delete(tmp);
		}
	}

	static int count(File file, String fileName, Counter counter) {
		OPCPackage pkg = null;
		try {
			pkg = OPCPackage.open(file, PackageAccess.READ);
			return Math.max(0, counter.count(pkg));
		} catch (Exception e) {
			Interrupts.keep(e);
			log.warn("Cannot count the pages of {}: {}", fileName, e.toString());
			return 0;
		} finally {
			// Closing a read-only package logs a warning; reverting closes it quietly
			if (pkg != null)
				pkg.revert();
		}
	}

	private static int savedPages(OPCPackage pkg) throws Exception {
		CTProperties properties = new POIXMLProperties(pkg).getExtendedProperties().getUnderlyingProperties();
		return properties.isSetPages() ? properties.getPages() : 0;
	}

	private static int slides(OPCPackage pkg) throws Exception {
		// Only the presentation's own list: PowerPoint 2010+ lists every slide again under its sections
		int slides = 0;
		for (Element list : children(mainPart(pkg), "sldIdLst"))
			slides += children(list, "sldId").size();
		return slides;
	}

	private static int visibleSheets(OPCPackage pkg) throws Exception {
		int visible = 0;
		for (Element list : children(mainPart(pkg), "sheets"))
			for (Element sheet : children(list, "sheet")) {
				String state = sheet.getAttribute("state");
				if (!"hidden".equals(state) && !"veryHidden".equals(state))
					visible++;
			}
		return visible;
	}

	/**
	 * @return the parent's child elements of this local name, in any namespace
	 *         (the transitional or the strict one)
	 */
	private static List<Element> children(Element parent, String localName) {
		List<Element> children = new ArrayList<>();
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
			if (node instanceof Element child && localName.equals(child.getLocalName()))
				children.add(child);
		return children;
	}

	/**
	 * @return the package's main part: the presentation or the workbook
	 */
	private static Element mainPart(OPCPackage pkg) throws Exception {
		List<PackagePart> parts = pkg.getPartsByRelationshipType(PackageRelationshipTypes.CORE_DOCUMENT);
		if (parts.isEmpty())
			parts = pkg.getPartsByRelationshipType(PackageRelationshipTypes.STRICT_CORE_DOCUMENT);
		if (parts.isEmpty())
			throw new IllegalArgumentException("no main part");
		try (InputStream in = parts.get(0).getInputStream()) {
			return DocumentHelper.readDocument(in).getDocumentElement();
		}
	}
}
