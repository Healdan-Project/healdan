// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.logicaldoc.healdan.util.BasicPlane;

/**
 * A message's Message-ID as Healdan compares it (M5 spec §6.1): the header's
 * value without its angle brackets (every pair) or surrounding whitespace,
 * compared exactly (no case folding), at most {@value #MAX_LENGTH} characters,
 * the limit RFC 5322 puts on a line. A longer one counts as none. A character
 * MariaDB's tables can't hold becomes U+FFFD ({@link BasicPlane}), as in the
 * other email fields, so what is stored, hashed and looked up is the same
 * text. The index looks a message up by the {@linkplain #hash hash}, a
 * fixed-length key.
 */
public final class MessageIds {

	/** The longest Message-ID kept; the {@code hd_email} column is this wide */
	public static final int MAX_LENGTH = 998;

	private MessageIds() {
	}

	/**
	 * @param header a Message-ID header's value, with or without its angle
	 *        brackets
	 * @return the value without the brackets, every pair of them round it
	 *         (a sender's program can add its own round an id that has them),
	 *         trimmed; null when blank, unreadable or over {@link #MAX_LENGTH}.
	 *         Normalising it again gives it unchanged, so a client that sends
	 *         a normalised id, which the server normalises again, asks for what
	 *         was stored.
	 */
	public static String normalize(String header) {
		if (header == null)
			return null;
		String id = BasicPlane.replaceOthers(header).replace('\u0000', ' ').strip();
		while (id.startsWith("<") && id.endsWith(">"))
			id = id.substring(1, id.length() - 1).strip();
		return id.isEmpty() || id.length() > MAX_LENGTH ? null : id;
	}

	/**
	 * @param messageId a normalised Message-ID
	 * @return the SHA-256 of its UTF-8 bytes, in lower-case hex
	 */
	public static String hash(String messageId) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(messageId.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Every Java has SHA-256", e);
		}
	}
}
