package com.logicaldoc.healdan.parser;

import java.io.File;
import java.io.InputStream;

import org.apache.poi.xslf.extractor.XSLFPowerPointExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.parser.AbstractParser;
import com.logicaldoc.core.parser.ParseParameters;

/**
 * Reads the slide text of PowerPoint 2007+ presentations ({@code pptx},
 * {@code ppsx}). Their pages are their slides. A file it can't read yields no
 * text and no exception, and counts 1 page.
 */
public class PowerPointParser extends AbstractParser {

	private static final Logger logger = LoggerFactory.getLogger(PowerPointParser.class);

	@Override
	protected void internalParse(InputStream is, ParseParameters parameters, StringBuilder output) {
		try (XMLSlideShow show = new XMLSlideShow(is); XSLFPowerPointExtractor extractor = new XSLFPowerPointExtractor(show)) {
			output.append(ParsedText.clean(extractor.getText()));
		} catch (Exception e) {
			Interrupts.keep(e);
			logger.warn("Cannot extract the text of {}: {}", parameters.getFileName(), e.toString());
		}
	}

	@Override
	public int countPages(InputStream input, String filename) {
		return Math.max(1, OfficePages.count(input, filename, OfficePages.SLIDES));
	}

	@Override
	public int countPages(File file, String filename) {
		return Math.max(1, OfficePages.count(file, filename, OfficePages.SLIDES));
	}
}
