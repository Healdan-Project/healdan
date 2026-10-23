package com.logicaldoc.healdan.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * Builds Office files whose page, slide or sheet counts the tests know, and
 * PDFs like the ones LibreOffice converts them to.
 */
public final class OfficeFiles {

	private OfficeFiles() {
	}

	/**
	 * @param savedPages the page count saved in the file, as Word saves it;
	 *        null saves none, as POI does
	 * @return a Word document
	 * @throws IOException if the file can't be written
	 */
	public static byte[] docx(Integer savedPages) throws IOException {
		try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			doc.createParagraph().createRun().setText("Pages");
			if (savedPages != null)
				doc.getProperties().getExtendedProperties().getUnderlyingProperties().setPages(savedPages);
			doc.write(out);
			return out.toByteArray();
		}
	}

	/**
	 * Zips a Word document by hand rather than with POI: only the parts a
	 * count needs, the extended properties as Word writes them, and no core
	 * properties.
	 *
	 * @param savedPages the page count saved in the file
	 * @return a Word document
	 * @throws IOException if the file can't be written
	 */
	public static byte[] handMadeDocx(int savedPages) throws IOException {
		Map<String, String> parts = new LinkedHashMap<>();
		parts.put("[Content_Types].xml", """
				<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
				<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
				<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
				<Default Extension="xml" ContentType="application/xml"/>
				<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
				<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
				</Types>""");
		parts.put("_rels/.rels", """
				<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
				<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
				<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
				<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
				</Relationships>""");
		parts.put("word/document.xml", """
				<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
				<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
				<w:body><w:p><w:r><w:t>Pages</w:t></w:r></w:p></w:body></w:document>""");
		parts.put("docProps/app.xml", """
				<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
				<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" \
				xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
				<Template>Normal.dotm</Template><TotalTime>3</TotalTime><Pages>%d</Pages><Words>1</Words>
				<Application>Microsoft Office Word</Application><DocSecurity>0</DocSecurity></Properties>"""
				.formatted(savedPages));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, String> part : parts.entrySet()) {
				zip.putNextEntry(new ZipEntry(part.getKey()));
				zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

	/**
	 * @param slides how many slides
	 * @param hidden how many of them are hidden
	 * @return a PowerPoint presentation
	 * @throws IOException if the file can't be written
	 */
	public static byte[] pptx(int slides, int hidden) throws IOException {
		try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (int i = 0; i < slides; i++) {
				XSLFSlide slide = show.createSlide();
				slide.createTextBox().setText("Slide " + (i + 1));
				if (i < hidden)
					slide.getXmlObject().setShow(false);
			}
			show.write(out);
			return out.toByteArray();
		}
	}

	/**
	 * A presentation with sections, as PowerPoint 2010+ saves it: its section
	 * list names every slide a second time ({@code p14:sldId}).
	 *
	 * @param slides how many slides
	 * @return a PowerPoint presentation
	 * @throws IOException if the file can't be written
	 */
	public static byte[] pptxWithSections(int slides) throws IOException {
		StringBuilder ids = new StringBuilder();
		for (int i = 0; i < slides; i++)
			ids.append("<p14:sldId id=\"%d\"/>".formatted(256 + i));
		String sections = """
				<extLst xmlns="http://schemas.openxmlformats.org/presentationml/2006/main">\
				<ext uri="{521415D9-36F7-43E2-AB2F-B90AF26B5E84}">\
				<p14:sectionLst xmlns:p14="http://schemas.microsoft.com/office/powerpoint/2010/main">\
				<p14:section name="Default Section" id="{6F1E7A12-3C4B-4D5E-8F90-A1B2C3D4E5F6}">\
				<p14:sldIdLst>%s</p14:sldIdLst></p14:section></p14:sectionLst></ext></extLst>""".formatted(ids);
		return replacePart(pptx(slides, 0), "ppt/presentation.xml", xml -> {
			int end = xml.lastIndexOf("</");
			return xml.substring(0, end) + sections + xml.substring(end);
		});
	}

	/**
	 * @return the zip with one entry's text changed
	 */
	private static byte[] replacePart(byte[] zip, String name, UnaryOperator<String> edit) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
				ZipOutputStream copy = new ZipOutputStream(out)) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				byte[] content = in.readAllBytes();
				if (entry.getName().equals(name))
					content = edit.apply(new String(content, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
				copy.putNextEntry(new ZipEntry(entry.getName()));
				copy.write(content);
				copy.closeEntry();
			}
		}
		return out.toByteArray();
	}

	/**
	 * @param visible how many visible sheets
	 * @param hidden how many hidden sheets
	 * @param veryHidden how many very hidden sheets, which only a macro can show
	 * @return an Excel workbook
	 * @throws IOException if the file can't be written
	 */
	public static byte[] xlsx(int visible, int hidden, int veryHidden) throws IOException {
		try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			int index = 0;
			for (int i = 0; i < visible; i++)
				book.createSheet("Visible " + (++index)).createRow(0).createCell(0).setCellValue("x");
			for (int i = 0; i < hidden; i++) {
				book.createSheet("Hidden " + (++index));
				book.setSheetVisibility(index - 1, SheetVisibility.HIDDEN);
			}
			for (int i = 0; i < veryHidden; i++) {
				book.createSheet("Very hidden " + (++index));
				book.setSheetVisibility(index - 1, SheetVisibility.VERY_HIDDEN);
			}
			book.write(out);
			return out.toByteArray();
		}
	}

	/**
	 * @param pages how many pages
	 * @return a PDF of blank pages
	 * @throws IOException if the file can't be written
	 */
	public static byte[] pdf(int pages) throws IOException {
		try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (int i = 0; i < pages; i++)
				doc.addPage(new PDPage());
			doc.save(out);
			return out.toByteArray();
		}
	}
}
