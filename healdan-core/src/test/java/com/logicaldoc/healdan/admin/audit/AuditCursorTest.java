// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.Test;

/**
 * The Audit Trail's cursor: each source's place, as text only it gives, and nothing else taken.
 */
public class AuditCursorTest {

	@Test
	public void testRoundTrip() {
		AuditCursor cursor = new AuditCursor(123, 0, Long.MAX_VALUE, 7, 150);

		assertEquals(cursor, AuditCursor.parse(cursor.text()));
		assertEquals(AuditCursor.START, AuditCursor.parse(AuditCursor.START.text()));
		assertTrue(AuditCursor.START.text().length() <= AuditCursor.MAX_TEXT);
		assertTrue(AuditCursor.START.text().matches("[A-Za-z0-9_-]+"));
	}

	@Test
	public void testAtEnd() {
		assertTrue(new AuditCursor(0, 0, 0, 0, 0).atEnd());
		assertFalse(new AuditCursor(0, 0, 0, 1, 0).atEnd());
		assertFalse(AuditCursor.START.atEnd());
	}

	@Test
	public void testNoOtherTextIsACursor() {
		for (String text : List.of("", " ", "abc", "d1.f1.u1.a1.s1", plain("d1.f1.u1.a1"), plain("d1.f1.u1.a1.s1.x2"),
				plain("d-1.f1.u1.a1.s1"), plain("d01.f1.u1.a1.s1"), plain("d1.f1.u1.a1.s9223372036854775808"),
				plain("d1.f1.u1.a1.s99999999999999999999"), plain("D1.F1.U1.A1.S1"), plain("d1 .f1.u1.a1.s1"),
				plain("d1.f1.u1.a1.s1\n"), plain("x' or '1'='1"), plain("d1.f1.u1.a1.s1") + "=",
				plain("d1.f1.u1.a1.s1") + "==", "%%%", "d1.f1.u1.a1.s1".repeat(20), "a".repeat(AuditCursor.MAX_TEXT + 1),
				"ZDEuZjEudTEuYTEuczE+", "ZDEuZjEudTEuYTEuczE/")) {
			assertThrows(text, IllegalArgumentException.class, () -> AuditCursor.parse(text));
		}
		assertThrows(IllegalArgumentException.class, () -> AuditCursor.parse(null));
	}

	@Test
	public void testOnlyTheTextItWritesIsTaken() {
		String text = new AuditCursor(1, 2, 3, 4, 5).text();
		// The same bytes spelled with other padding bits: the decoder takes them, the cursor doesn't
		char last = text.charAt(text.length() - 1);
		String other = text.substring(0, text.length() - 1) + (char) (last + 1);
		assertThrows(IllegalArgumentException.class, () -> AuditCursor.parse(other));
	}

	@Test
	public void testPlacesCantBeNegative() {
		assertThrows(IllegalArgumentException.class, () -> new AuditCursor(-1, 0, 0, 0, 0));
	}

	private static String plain(String text) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}
}
