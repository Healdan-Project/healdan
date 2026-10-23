package com.logicaldoc.healdan.parser;

import java.io.File;
import java.io.InputStream;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.parser.AbstractParser;
import com.logicaldoc.core.parser.ParseParameters;

/**
 * Reads the text of Word 2007+ documents ({@code docx}, {@code docm},
 * {@code dotx}), and their page count as Word saved it. A file it can't read
 * (encrypted, damaged, not Word) yields no text and no exception, and counts
 * 1 page.
 */
public class WordParser extends AbstractParser {

	private static final Logger logger = LoggerFactory.getLogger(WordParser.class);

	@Override
	protected void internalParse(InputStream is, ParseParameters parameters, StringBuilder output) {
		try (XWPFDocument document = new XWPFDocument(is); XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
			output.append(ParsedText.clean(extractor.getText()));
		} catch (Exception e) {
			Interrupts.keep(e);
			logger.warn("Cannot extract the text of {}: {}", parameters.getFileName(), e.toString());
		}
	}

	@Override
	public int countPages(InputStream input, String filename) {
		return Math.max(1, OfficePages.count(input, filename, OfficePages.SAVED_PAGES));
	}

	@Override
	public int countPages(File file, String filename) {
		return Math.max(1, OfficePages.count(file, filename, OfficePages.SAVED_PAGES));
	}
}
