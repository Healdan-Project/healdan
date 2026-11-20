// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FileNamesTest {

	private static String name(String subject) {
		return FileNames.fromSubject(subject, "eml", "orig.eml");
	}

	@Test
	public void testTable() {
		String[][] table = { { "Re: Demo plan", "Re_ Demo plan.eml" }, { "  padded  ", "padded.eml" },
				{ "a/b\\c:d*e?f\"g<h>i|j", "a_b_c_d_e_f_g_h_i_j.eml" }, { "tab\tand\nnewline\u0000nul", "tab_and_newline_nul.eml" },
				{ "Fwd: caf\u00e9 \u2013 plan", "Fwd_ caf\u00e9 \u2013 plan.eml" }, { "ends with dots...", "ends with dots.eml" },
				{ "", "orig.eml" }, { "   ", "orig.eml" }, { null, "orig.eml" }, { "...", "orig.eml" } };
		for (String[] row : table)
			assertEquals(row[0], row[1], name(row[0]));
	}

	@Test
	public void testMsgExtension() {
		assertEquals("Hello.msg", FileNames.fromSubject("Hello", "msg", "x.msg"));
	}

	@Test
	public void testLongSubjectFitsIn255() {
		String result = name("x".repeat(1000));
		assertEquals(255, result.length());
		assertTrue(result.endsWith(".eml"));
	}

	@Test
	public void testCharactersBeyondTheBasicPlaneAreKept() {
		// An emoji is kept (PostgreSQL spec section 7.5); a lone surrogate isn't a character, and becomes U+FFFD
		assertEquals("Re_ \ud83c\udf89 Sale.eml", name("Re: " + new String(Character.toChars(0x1F389)) + " Sale"));
		assertEquals("a\ufffdb.eml", name("a\uD83Cb"));
		assertEquals("\ud83d\ude00.eml", name(new String(Character.toChars(0x1F600))));
	}

	@Test
	public void testALongSubjectIsNeverCutInsideAnEmoji() {
		String emoji = new String(Character.toChars(0x1F600));
		// 255 - ".eml" = 251: two chars for the emoji, which fits whole after 249 others
		assertEquals("x".repeat(249) + emoji + ".eml", name("x".repeat(249) + emoji + "yy"));
		// After 250 the cut would fall inside it, so it goes whole, and no half of it is left
		assertEquals("x".repeat(250) + ".eml", name("x".repeat(250) + emoji + "yy"));
	}

	@Test
	public void testTheFallbackIsKeptToo() {
		// A blank subject keeps the name the email came with, emoji and all
		String party = "Party " + new String(Character.toChars(0x1F389)) + ".eml";
		assertEquals(party, FileNames.fromSubject(null, "eml", party));
		assertEquals(party, FileNames.fromSubject("   ", "eml", party));
		assertEquals(party, FileNames.fromSubject("...", "eml", party));
		// but for what Healdan can't keep
		assertEquals("Half \ufffd.eml", FileNames.fromSubject(null, "eml", "Half \uD83C.eml"));
	}

	@Test
	public void testExtensionKeepsItsCase() {
		assertEquals("Hello.EML", FileNames.fromSubject("Hello", "EML", "x.EML"));
	}

	@Test
	public void testAnAttachmentsName() {
		String[][] table = { { "Contract.pdf", "Contract.pdf" }, { "  padded.pdf  ", "padded.pdf" },
				{ "x:y?.pdf", "x_y_.pdf" }, { "a/b\\c.pdf", "a_b_c.pdf" }, { "tab\tand\u0000nul.txt", "tab_and_nul.txt" },
				{ "Caf\u00e9 \u2013 menu.docx", "Caf\u00e9 \u2013 menu.docx" }, { "ends with dots...", "ends with dots" },
				{ "README", "README" }, { ".gitignore", ".gitignore" }, { "", "fallback.bin" }, { "   ", "fallback.bin" },
				{ "...", "fallback.bin" }, { null, "fallback.bin" } };
		for (String[] row : table)
			assertEquals(row[0], row[1], FileNames.fromFileName(row[0], "fallback.bin"));
	}

	@Test
	public void testAnAttachmentsNameKeepsItsExtensionWhenShortened() {
		String result = FileNames.fromFileName("x".repeat(400) + ".docx", "f");
		assertEquals(255, result.length());
		assertTrue(result.endsWith("x.docx"));
		// No extension, or one too long to be one: the whole name is cut
		assertEquals(255, FileNames.fromFileName("y".repeat(400), "f").length());
		String odd = FileNames.fromFileName("z".repeat(10) + "." + "e".repeat(300), "f");
		assertEquals(255, odd.length());
		assertTrue(odd.startsWith("zzzzzzzzzz.eee"));
		// Cut at a dot, it doesn't end in one
		assertEquals("b".repeat(254), FileNames.fromFileName("b".repeat(254) + "." + "c".repeat(300), "f"));
	}

	@Test
	public void testAnAttachmentsNameKeepsItsEmoji() {
		String party = new String(Character.toChars(0x1F389));
		assertEquals("Party " + party + ".pdf", FileNames.fromFileName("Party " + party + ".pdf", "f"));
		assertEquals("Party " + party + ".eml", FileNames.fromFileName(null, "Party " + party + ".eml"));
		assertEquals("Half \ufffd.pdf", FileNames.fromFileName("Half \uD83C.pdf", "f"));
		// Shortened, it loses an emoji the cut would split whole: 255 - ".docx" leaves 250 for the stem
		assertEquals("x".repeat(249) + ".docx", FileNames.fromFileName("x".repeat(249) + party + "y.docx", "f"));
		assertEquals("x".repeat(248) + party + ".docx",
				FileNames.fromFileName("x".repeat(248) + party + "y.docx", "f"));
	}
}
