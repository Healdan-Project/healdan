package com.logicaldoc.web.data;

import java.util.Comparator;
import java.util.regex.Pattern;

import com.logicaldoc.core.document.AbstractDocument;

/**
 * Healdan: a doc # is a whole number kept as text in {@code customId} (M1 plan,
 * Task 27), and CE's text sort puts 10 before 2. This sorts the custom ids that
 * are numbers by value and every other custom id after them, compared without
 * case as CE compares text. A descending sort reverses the whole order, as the
 * documents grid does when it sorts on its own side
 * ({@code DocumentsListGrid.numericKey}).
 */
final class HealdanDocNumberSort {

	static final String FIELD = "customId";

	private static final Pattern DIGITS = Pattern.compile("\\d{1,19}");

	private static final Comparator<AbstractDocument> ASCENDING = Comparator.comparing(AbstractDocument::getCustomId,
			HealdanDocNumberSort::compare);

	private HealdanDocNumberSort() {
	}

	static Comparator<AbstractDocument> comparator(boolean ascending) {
		return ascending ? ASCENDING : ASCENDING.reversed();
	}

	static int compare(String customId1, String customId2) {
		Long number1 = number(customId1);
		Long number2 = number(customId2);
		if (number1 != null && number2 != null)
			return number1.compareTo(number2);
		else if (number1 != null)
			return -1;
		else if (number2 != null)
			return 1;
		else
			return text(customId1).compareTo(text(customId2));
	}

	private static Long number(String customId) {
		if (customId == null || !DIGITS.matcher(customId).matches())
			return null;
		try {
			return Long.parseLong(customId);
		} catch (NumberFormatException e) {
			// Nineteen digits above Long.MAX_VALUE
			return null;
		}
	}

	private static String text(String customId) {
		return customId == null ? "" : customId.toLowerCase();
	}
}
