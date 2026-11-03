// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.parser;

/**
 * Cleans the text a parser hands to the index.
 */
final class ParsedText {

	private ParsedText() {
	}

	/**
	 * @param text what an extractor returned
	 * @return the text with control characters (other than line breaks and
	 *         tabs) replaced by spaces; empty for null
	 */
	static String clean(String text) {
		return text == null ? "" : text.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", " ");
	}
}
