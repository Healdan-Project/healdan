package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import com.logicaldoc.healdan.preview.OfficeFormats.Kind;

public class OfficeFormatsTest {

	@Test
	public void testKinds() {
		assertEquals(Optional.of(Kind.TEXT), OfficeFormats.kindOf("a.docx"));
		assertEquals("writer_pdf_Export", Kind.TEXT.pdfFilter());
		assertEquals(Optional.of(Kind.SPREADSHEET), OfficeFormats.kindOf("b.XLSX"));
		assertEquals("calc_pdf_Export", Kind.SPREADSHEET.pdfFilter());
		assertEquals(Optional.of(Kind.PRESENTATION), OfficeFormats.kindOf("c.Ppsx"));
		assertEquals("impress_pdf_Export", Kind.PRESENTATION.pdfFilter());
		assertEquals(Optional.of(Kind.SPREADSHEET), OfficeFormats.kindOf("d.ods"));
		assertEquals(Optional.of(Kind.SPREADSHEET), OfficeFormats.kindOf("e.csv"));
	}

	@Test
	public void testNotOffice() {
		for (String name : new String[] { "a.pdf", "a.txt", "a.eml", "docx", null }) {
			assertEquals(name, Optional.empty(), OfficeFormats.kindOf(name));
			assertFalse(OfficeFormats.isOffice(name));
		}
		assertTrue(OfficeFormats.isOffice("x.odp"));
	}

	@Test
	public void testExtensions() {
		assertEquals(Set.of("doc", "docx", "docm", "dot", "dotx", "dotm", "rtf", "odt", "ott", "xls", "xlsx",
				"xlsm", "xlt", "xltx", "xltm", "csv", "ods", "ots", "ppt", "pptx", "pptm", "pps", "ppsx", "pot",
				"potx", "odp", "otp"), OfficeFormats.extensions());
	}

	@Test
	public void testHideableMarkup() {
		assertTrue(OfficeFormats.hasHideableMarkup("X.DOCX"));
		assertTrue(OfficeFormats.hasHideableMarkup("a.dotm"));
		assertFalse(OfficeFormats.hasHideableMarkup("a.doc"));
		assertFalse(OfficeFormats.hasHideableMarkup("a.odt"));
		assertFalse(OfficeFormats.hasHideableMarkup("a.rtf"));
	}
}
