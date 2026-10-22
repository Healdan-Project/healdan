package com.logicaldoc.healdan.preview;

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
 * <p>
 * It runs before LibreOffice's timeout applies, so it is bounded: it reads at
 * most 4 MB of settings, copies only a file that hides its markup, and gives
 * up on a copy that inflates past 100 times the file's size (the ratio POI's
 * {@code ZipSecureFile} allows by default).
 */
public final class MarkupPreparer {

	private static final Logger log = LoggerFactory.getLogger(MarkupPreparer.class);

	private static final String SETTINGS = "word/settings.xml";

	private static final Pattern REVISION_VIEW = Pattern.compile("<w:revisionView\\b[^>]*/>");

	/** The largest settings part read; a real one is a few kilobytes */
	private static final int MAX_SETTINGS = 4 * 1024 * 1024;

	/** The most the copy may inflate to, as a multiple of the file's size */
	private static final long MAX_RATIO = 100;

	private MarkupPreparer() {
	}

	/**
	 * @param src the file to convert
	 * @param workDir where to put the copy
	 * @return a copy of the file, with the same name, in {@code workDir} when the
	 *         format can hide markup and the file is a readable zip that hides
	 *         it; otherwise {@code src}
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
			ZipEntry entry = in.getEntry(SETTINGS);
			if (entry == null)
				return src;
			String settings = new String(readSettings(in, entry), StandardCharsets.UTF_8);
			if (!REVISION_VIEW.matcher(settings).find())
				return src;

			byte[] unhidden = REVISION_VIEW.matcher(settings).replaceAll("").getBytes(StandardCharsets.UTF_8);
			copy(in, unhidden, copy, MAX_RATIO * src.length());
			return copy;
		} catch (IOException e) {
			log.warn("Cannot show the tracked changes of {}, converting it as it is: {}", src.getName(),
					e.getMessage());
			Files.deleteIfExists(copy.toPath());
			return src;
		}
	}

	private static byte[] readSettings(ZipFile in, ZipEntry entry) throws IOException {
		try (InputStream data = in.getInputStream(entry)) {
			byte[] settings = data.readNBytes(MAX_SETTINGS + 1);
			if (settings.length > MAX_SETTINGS)
				throw new IOException(SETTINGS + " is larger than " + MAX_SETTINGS + " bytes");
			return settings;
		}
	}

	/**
	 * Copies every entry, with the settings part replaced, failing once more
	 * than {@code budget} bytes have been written.
	 */
	private static void copy(ZipFile in, byte[] settings, File copy, long budget) throws IOException {
		long written = 0;
		byte[] buffer = new byte[8192];
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(copy.toPath()))) {
			Enumeration<? extends ZipEntry> entries = in.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				ZipEntry copied = new ZipEntry(entry.getName());
				copied.setTime(entry.getTime());
				out.putNextEntry(copied);
				if (SETTINGS.equals(entry.getName())) {
					written = count(written, settings.length, budget);
					out.write(settings);
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
