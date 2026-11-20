// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import java.time.Duration;
import java.util.Objects;

/**
 * A page of the Errors screen's list (Admin app spec section 8.8): the faults
 * seen in a window, newest first, with its filters.
 *
 * @param window how far back the list goes
 * @param text text the latest message or the logger's name contains, ignoring
 *        case; null or blank for any
 * @param area one of {@link ErrorAreas#ALL}; null or blank for any
 * @param unresolvedOnly whether to leave out the resolved faults
 * @param offset how many faults come before the page
 * @param limit the most faults the page holds
 */
public record ErrorQuery(Window window, String text, String area, boolean unresolvedOnly, int offset, int limit) {

	/** The Errors screen's windows */
	public enum Window {
		HOUR(Duration.ofHours(1)), DAY(Duration.ofHours(24)), WEEK(Duration.ofDays(7)), MONTH(Duration.ofDays(30));

		private final Duration length;

		Window(Duration length) {
			this.length = length;
		}

		/** @return how far back it goes */
		public Duration length() {
			return length;
		}
	}

	/**
	 * Takes the spaces off the text and the area, which are compared as given,
	 * and a blank one for none.
	 *
	 * @throws IllegalArgumentException for a negative offset or a limit under
	 *         one
	 */
	public ErrorQuery {
		Objects.requireNonNull(window, "window");
		text = trimmed(text);
		area = trimmed(area);
		if (offset < 0)
			throw new IllegalArgumentException("The offset can't be negative");
		if (limit < 1)
			throw new IllegalArgumentException("The limit must be at least 1");
	}

	private static String trimmed(String value) {
		if (value == null)
			return null;
		String trimmed = value.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
