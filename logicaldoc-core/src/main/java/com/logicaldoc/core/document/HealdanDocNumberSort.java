package com.logicaldoc.core.document;

import java.util.Locale;

/**
 * Healdan: a doc # is a whole number kept as text in {@code customId} (M1 plan,
 * Task 27), and a text sort puts 10 before 2. {@link DocumentComparator} sorts
 * custom ids with this, so every listing that sorts on {@code customId} (the
 * documents grid's servlet, SOAP and REST) sorts the ids that are numbers by
 * value and every other id after them, compared without case. A descending
 * sort reverses the whole order.
 *
 * The documents grid sorts the same way on its own side
 * ({@code DocumentsListGrid.numericKey}), so the two must keep the same
 * definition of a number: 1 to 18 ASCII digits, which always fits a long.
 */
final class HealdanDocNumberSort {

	static final int MAX_DIGITS = 18;

	private HealdanDocNumberSort() {
	}

	static int compare(String customId1, String customId2) {
		boolean number1 = isNumber(customId1);
		boolean number2 = isNumber(customId2);
		if (number1 && number2)
			return Long.compare(Long.parseLong(customId1), Long.parseLong(customId2));
		else if (number1)
			return -1;
		else if (number2)
			return 1;
		else
			return text(customId1).compareTo(text(customId2));
	}

	static boolean isNumber(String customId) {
		if (customId == null || customId.isEmpty() || customId.length() > MAX_DIGITS)
			return false;
		for (int i = 0; i < customId.length(); i++) {
			char c = customId.charAt(i);
			if (c < '0' || c > '9')
				return false;
		}
		return true;
	}

	private static String text(String customId) {
		return customId == null ? "" : customId.toLowerCase(Locale.ROOT);
	}
}
