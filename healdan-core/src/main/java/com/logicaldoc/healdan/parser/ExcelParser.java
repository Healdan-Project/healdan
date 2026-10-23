package com.logicaldoc.healdan.parser;

import java.io.File;
import java.io.InputStream;

import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.parser.AbstractParser;
import com.logicaldoc.core.parser.ParseParameters;

/**
 * Reads the cell text of Excel 2007+ workbooks ({@code xlsx}, {@code xlsm}).
 * Their pages are their visible sheets. A file it can't read yields no text
 * and no exception, and counts 1 page.
 */
public class ExcelParser extends AbstractParser {

	private static final Logger logger = LoggerFactory.getLogger(ExcelParser.class);

	@Override
	protected void internalParse(InputStream is, ParseParameters parameters, StringBuilder output) {
		try (XSSFWorkbook workbook = new XSSFWorkbook(is); XSSFExcelExtractor extractor = new XSSFExcelExtractor(workbook)) {
			output.append(ParsedText.clean(extractor.getText()));
		} catch (Exception e) {
			Interrupts.keep(e);
			logger.warn("Cannot extract the text of {}: {}", parameters.getFileName(), e.toString());
		}
	}

	@Override
	public int countPages(InputStream input, String filename) {
		return Math.max(1, OfficePages.count(input, filename, OfficePages.VISIBLE_SHEETS));
	}

	@Override
	public int countPages(File file, String filename) {
		return Math.max(1, OfficePages.count(file, filename, OfficePages.VISIBLE_SHEETS));
	}
}
