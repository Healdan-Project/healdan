// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.Test;

/**
 * A log's positions (ruling P5-R27): a file's identity and an offset, as opaque text, and only the text a position
 * gives taken back.
 */
public class LogPositionTest {

	private static final String IDENTITY = "0123456789abcdef0123456789abcdef";

	@Test
	public void testRoundTrip() {
		for (LogPosition position : List.of(new LogPosition(IDENTITY, 0), new LogPosition(IDENTITY, 5_242_880),
				new LogPosition(IDENTITY, Long.MAX_VALUE), LogPosition.currentStart())) {
			String text = position.text();
			assertTrue(text, text.matches("[A-Za-z0-9_-]+"));
			assertEquals(position, LogPosition.parse(text));
		}
		assertTrue(LogPosition.currentStart().isCurrentStart());
		assertFalse(new LogPosition(IDENTITY, 0).isCurrentStart());
		// The text holds no file's name or path
		assertEquals(IDENTITY + ".12", new String(Base64.getUrlDecoder().decode(new LogPosition(IDENTITY, 12).text()),
				StandardCharsets.US_ASCII));
	}

	@Test
	public void testTextThatIsntAPositionIsRefused() {
		for (String text : List.of("", "x", "!!", "a".repeat(81), encode(IDENTITY + ".-1"), encode(IDENTITY + ".01"),
				encode(IDENTITY + ".99999999999999999999"), encode(IDENTITY + ".9223372036854775808"),
				encode(IDENTITY.toUpperCase() + ".1"), encode(IDENTITY.substring(1) + ".1"), encode("current.1"),
				encode("current"), encode("../dms.log.0"), encode(IDENTITY + ".1") + "=",
				Base64.getUrlEncoder().encodeToString((IDENTITY + ".12").getBytes(StandardCharsets.US_ASCII)),
				Base64.getEncoder().encodeToString((IDENTITY + ".1?").getBytes(StandardCharsets.US_ASCII))))
			assertThrows(text, IllegalArgumentException.class, () -> LogPosition.parse(text));
		assertThrows(IllegalArgumentException.class, () -> LogPosition.parse(null));
		assertThrows(IllegalArgumentException.class, () -> new LogPosition(null, 1));
		assertThrows(IllegalArgumentException.class, () -> new LogPosition("dms.log", 1));
		assertThrows(IllegalArgumentException.class, () -> new LogPosition(IDENTITY, -1));
	}

	private static String encode(String decoded) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.getBytes(StandardCharsets.US_ASCII));
	}
}
