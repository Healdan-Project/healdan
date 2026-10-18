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
	public void testLongSubjectNeverCutsASurrogatePair() {
		String result = name("x".repeat(250) + "\u1f600\u1f600");
		assertTrue(result.length() <= 255);
		assertTrue(result.endsWith(".eml"));
		for (int i = 0; i < result.length(); i++)
			if (Character.isHighSurrogate(result.charAt(i)))
				assertTrue(Character.isLowSurrogate(result.charAt(i + 1)));
	}
}
