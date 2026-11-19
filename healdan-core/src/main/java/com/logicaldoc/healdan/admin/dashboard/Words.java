// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The Dashboard's lines in US English, as the Admin app writes them: counts with grouping, sizes as the app's
 * {@code formatSize} gives them (1,024 bytes a KB, a decimal under 10), lists joined with "and", and lengths of time
 * with no clock time, which would be the server's time zone and not the reader's.
 */
final class Words {

	/** The most names a list gives before "and N more" */
	static final int LISTED = 3;

	private static final String[] SIZE_UNITS = { "B", "KB", "MB", "GB", "TB", "PB" };

	private Words() {
	}

	/** @return the number with US grouping, as {@code 1,284} */
	static String number(long n) {
		return String.format(Locale.US, "%,d", n);
	}

	/** @return the number and the noun, as {@code 1 file} or {@code 3 files} */
	static String count(long n, String one, String many) {
		return number(n) + " " + (n == 1 ? one : many);
	}

	/** @return the bytes as the Admin app's {@code formatSize} writes them, as {@code 1.4 MB} or {@code 195 GB} */
	static String size(long bytes) {
		BigDecimal value = BigDecimal.valueOf(Math.max(0, bytes));
		BigDecimal step = BigDecimal.valueOf(1024);
		int unit = 0;
		while (value.compareTo(step) >= 0 && unit < SIZE_UNITS.length - 1) {
			value = value.divide(step);
			unit++;
		}
		int digits = unit == 0 || value.compareTo(BigDecimal.TEN) >= 0 ? 0 : 1;
		// Intl.NumberFormat rounds half away from zero; 1,024 is a power of two, so each division is exact
		value = value.setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros();
		return String.format(Locale.US, "%,." + Math.max(0, value.scale()) + "f", value) + " " + SIZE_UNITS[unit];
	}

	/** @return the used and the total, as {@code 195 GB of 500 GB used} */
	static String usedOf(long used, long total) {
		return size(used) + " of " + size(total) + " used";
	}

	/** @return {@code A}, {@code A and B}, {@code A, B and C}, or {@code A, B, C and 2 more} */
	static String list(List<String> items) {
		if (items.isEmpty())
			return "";
		if (items.size() > LISTED)
			return String.join(", ", items.subList(0, LISTED)) + " and " + (items.size() - LISTED) + " more";
		if (items.size() == 1)
			return items.get(0);
		return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
	}

	/** @return how long, in its largest whole unit: {@code 6 days}, {@code 1 hour}, or {@code less than a minute} */
	static String duration(Duration length) {
		if (length.toDays() > 0)
			return count(length.toDays(), "day", "days");
		if (length.toHours() > 0)
			return count(length.toHours(), "hour", "hours");
		if (length.toMinutes() > 0)
			return count(length.toMinutes(), "minute", "minutes");
		return "less than a minute";
	}

	/** @return how long before now it was, as {@code 12 minutes ago}, or {@code just now} */
	static String ago(Instant when, Instant now) {
		Duration since = Duration.between(when, now);
		return since.toMinutes() < 1 ? "just now" : duration(since) + " ago";
	}

	/** @return the text with its first letter in capitals */
	static String capitalised(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}
}
