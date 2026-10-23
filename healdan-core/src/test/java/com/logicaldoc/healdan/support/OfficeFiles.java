package com.logicaldoc.healdan.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
