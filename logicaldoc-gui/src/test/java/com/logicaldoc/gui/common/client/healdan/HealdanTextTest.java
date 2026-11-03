// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.common.client.healdan;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The escaping {@link HealdanText#escape} gives the group and user names that
 * LogicalDOC's formatters write into HTML.
 */
public class HealdanTextTest {

	@Test
	public void testEscapesMarkup() {
		assertEquals("&lt;b&gt;Partners&lt;/b&gt;", HealdanText.escape("<b>Partners</b>"));
	}

	@Test
	public void testEscapesAmpersandsAndQuotes() {
		assertEquals("R&amp;D", HealdanText.escape("R&D"));
		assertEquals("&amp;lt;", HealdanText.escape("&lt;"));
		assertEquals("O&#39;Brien &quot;Litigation&quot;", HealdanText.escape("O'Brien \"Litigation\""));
	}

	@Test
	public void testLeavesPlainTextAlone() {
		assertEquals("Conveyancing team", HealdanText.escape("Conveyancing team"));
		assertEquals("42", HealdanText.escape(42L));
	}

	@Test
	public void testNullIsEmpty() {
		assertEquals("", HealdanText.escape(null));
	}
}
