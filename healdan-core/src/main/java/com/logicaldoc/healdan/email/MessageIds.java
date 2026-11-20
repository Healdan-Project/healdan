// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.logicaldoc.healdan.util.StorableText;

/**
 * A message's Message-ID as Healdan compares it (M5 spec \u00a76.1): the header's
 * value without its angle brackets (every pair) or surrounding whitespace,
 * compared exactly (no case folding), at most {@value #MAX_LENGTH} characters,
 * the limit RFC 5322 puts on a line. A longer one counts as none. A character
 * beyond the Basic Multilingual Plane, and a lone surrogate, becomes U+FFFD,
 * and a NUL a space, so what is stored, hashed and looked up is the same text.
 * The index looks a message up by the {@linkplain #hash hash}, a fixed-length
 * key.
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
		String id = basicPlane(header).replace('\u0000', ' ').strip();
		while (id.startsWith("<") && id.endsWith(">"))
			id = id.substring(1, id.length() - 1).strip();
		return id.isEmpty() || id.length() > MAX_LENGTH ? null : id;
	}

	/**
	 * U+FFFD for each character beyond the Basic Multilingual Plane, though
	 * PostgreSQL stores them, and for each lone surrogate: the Helper's
	 * {@code MessageIds.cs} does the same to the character, and {@code hd_email}
	 * is indexed by the result, so the two have to agree (PostgreSQL spec
	 * section 7.5)
	 */
	private static String basicPlane(String text) {
		if (text.chars().noneMatch(c -> Character.isSurrogate((char) c)))
			return text;
		StringBuilder clean = new StringBuilder(text.length());
		text.codePoints().forEach(c -> clean.append(
				c > Character.MAX_VALUE || Character.isSurrogate((char) c) ? StorableText.REPLACEMENT : (char) c));
		return clean.toString();
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
