package com.logicaldoc.healdan.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * Builds Office files whose page, slide or sheet counts the tests know.
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
}
