// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What makes two errors one fault, the Errors screen's row (Admin app spec
 * section 9.4), and the documents a message names.
 */
public final class ErrorFingerprint {

	/**
	 * A name in single quotes: the opening quote doesn't follow a letter or a
	 * digit, and the closing one isn't followed by one, so the apostrophe in
	 * {@code can't} quotes nothing while the one in {@code 'John's papers'}
	 * stays inside the name
	 */
	private static final Pattern SINGLE_QUOTED = Pattern.compile("(?<![\\p{L}\\p{N}])'.*?'(?![\\p{L}\\p{N}])");

	private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"[^\"]*\"");

	private static final Pattern NUMBER = Pattern.compile("\\d+");

	/**
	 * A document's id as log lines name it: {@code doc 10412},
	 * {@code document 10412}, {@code document id 10412}, {@code docId=10412},
	 * {@code doc_id: 10412}, and the id beside a doc # in
	 * {@code document #998 (id 10412)}. A doc # on its own isn't an id, and
	 * {@code documents} names no single one.
	 */
	private static final Pattern DOCUMENT = Pattern.compile("(?i)\\bdoc(?:ument)?(?:"
			+ "\\s*#\\s*\\d+\\s*\\(\\s*id\\s*[=:]?\\s*(\\d+)\\s*\\)" + "|(?:[ _]?id)?(?:\\s*[=:]\\s*|\\s+)(\\d+)\\b)");

	private ErrorFingerprint() {
	}

	/**
	 * The fingerprint of an error: SHA-256, in 64 hexadecimal digits, of the
	 * exception's class, where it was logged (class and line, or the logger's
	 * name when the configuration doesn't record where) and the message with
	 * its numbers and quoted names taken out. The level and the time don't
	 * count.
	 *
	 * @param error the error
	 * @return its fingerprint
	 */
	public static String of(CapturedError error) {
		String where = error.location() != null ? error.location() : error.logger();
		String text = (error.exception() != null ? error.exception() : "") + "\n" + where + "\n"
				+ normalised(error.message());
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Every Java has SHA-256", e);
		}
	}

	/**
	 * @param message a log message
	 * @return it with each quoted name and each number in it replaced by the
	 *         same text
	 */
	static String normalised(String message) {
		String text = SINGLE_QUOTED.matcher(message).replaceAll("''");
		text = DOUBLE_QUOTED.matcher(text).replaceAll("\"\"");
		return NUMBER.matcher(text).replaceAll("0");
	}

	/**
	 * @param message a log message, or null
	 * @return the ids of the documents it names, each once, in the order it
	 *         names them
	 */
	public static List<Long> documents(String message) {
		if (message == null)
			return List.of();
		Set<Long> ids = new LinkedHashSet<>();
		Matcher matcher = DOCUMENT.matcher(message);
		while (matcher.find()) {
			String digits = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
			try {
				ids.add(Long.parseLong(digits));
			} catch (NumberFormatException e) {
				// More digits than an id has: not one
			}
		}
		return List.copyOf(ids);
	}
}
