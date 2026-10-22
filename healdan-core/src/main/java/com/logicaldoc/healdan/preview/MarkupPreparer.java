package com.logicaldoc.healdan.preview;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes tracked changes visible to LibreOffice. A Word file saved with its
 * markup hidden carries a {@code w:revisionView} element in
 * {@code word/settings.xml}, and LibreOffice then converts it without the
 * deletions. The preview works on a copy with that element removed; the stored
 * file is never touched.
 */
public final class MarkupPreparer {

	private static final Logger log = LoggerFactory.getLogger(MarkupPreparer.class);

	private static final String SETTINGS = "word/settings.xml";

	private static final Pattern REVISION_VIEW = Pattern.compile("<w:revisionView\\b[^>]*/>");

	private MarkupPreparer() {
	}

	/**
	 * @param src the file to convert
	 * @param workDir where to put the copy
	 * @return a copy of the file, with the same name, in {@code workDir} when the
	 *         format can hide markup and the file is a readable zip; otherwise
	 *         {@code src}
	 * @throws IOException if the copy can't be written
	 */
	public static File prepare(File src, File workDir) throws IOException {
		if (!OfficeFormats.hasHideableMarkup(src.getName()))
			return src;

		File copy = new File(workDir, src.getName());
		try (ZipFile in = new ZipFile(src)) {
			try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(copy.toPath()))) {
				Enumeration<? extends ZipEntry> entries = in.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					ZipEntry copied = new ZipEntry(entry.getName());
					copied.setTime(entry.getTime());
					out.putNextEntry(copied);
					try (InputStream data = in.getInputStream(entry)) {
						if (SETTINGS.equals(entry.getName()))
							out.write(unhide(data.readAllBytes()));
						else
							data.transferTo(out);
					}
					out.closeEntry();
				}
			}
			return copy;
		} catch (IOException e) {
			// Not a readable zip: let LibreOffice have its say about it
			log.debug("Cannot prepare markup of {}: {}", src.getName(), e.getMessage());
			Files.deleteIfExists(copy.toPath());
			return src;
		}
	}

	private static byte[] unhide(byte[] settings) {
		String xml = new String(settings, StandardCharsets.UTF_8);
		return REVISION_VIEW.matcher(xml).replaceAll("").getBytes(StandardCharsets.UTF_8);
	}
}
