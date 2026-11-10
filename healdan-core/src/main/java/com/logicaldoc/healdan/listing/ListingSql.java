// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

/**
 * The pieces of listing SQL that MariaDB, HSQLDB and PostgreSQL each write
 * their own way, written once in a form all three take (the S3 plan's Global
 * Constraints): no {@code ::} casts, no {@code IFNULL} or {@code LIMIT a, b},
 * and no cast to {@code bigint}, which MariaDB refuses (11.4, 2026-11-10).
 * <p>
 * {@code AND} and {@code OR} may run their sides in any order, so a value that
 * could fail a cast or a substring is only reached through {@code CASE}.
 */
public final class ListingSql {

	/** Paging, after the {@code order by}, with {@code :limit} and {@code :offset} */
	public static final String PAGE = " limit :limit offset :offset";

	/** The longest doc # the API takes, as {@code ApiViews.hasDocNumber} decides */
	static final int DOC_NUMBER_DIGITS = 18;

	/** The longest part of a version that sorts as a number */
	private static final int VERSION_DIGITS = 9;

	private ListingSql() {
	}

	/**
	 * @param alias the alias of {@code ld_document}
	 * @return the doc # as a number, to sort by. Use it only on rows that
	 *         pass {@link #isDocNumber}, as other text fails the cast.
	 */
	public static String docNumberOrder(String alias) {
		return "cast(%s.ld_customid as decimal(%d,0))".formatted(alias, DOC_NUMBER_DIGITS);
	}

	/**
	 * @param alias the alias of {@code ld_document}
	 * @return a condition that holds when the document has a doc # the API
	 *         shows: 1 to 18 digits and nothing else, as
	 *         {@code ApiViews.hasDocNumber} decides
	 */
	public static String isDocNumber(String alias) {
		return digits(alias + ".ld_customid", DOC_NUMBER_DIGITS);
	}

	/**
	 * @param alias the alias of {@code ld_document}
	 * @return the major number of CE's version ({@code 10} of {@code 10.2}),
	 *         or null if it isn't a number
	 */
	public static String versionMajor(String alias) {
		String version = alias + ".ld_version";
		return versionPart(version, "substring(%1$s from 1 for position('.' in %1$s) - 1)".formatted(version));
	}

	/**
	 * @param alias the alias of {@code ld_document}
	 * @return the minor number of CE's version ({@code 2} of {@code 10.2}),
	 *         or null if it isn't a number
	 */
	public static String versionMinor(String alias) {
		String version = alias + ".ld_version";
		return versionPart(version, "substring(%1$s from position('.' in %1$s) + 1)".formatted(version));
	}

	private static String versionPart(String version, String part) {
		// The outer case keeps a version without a dot from reaching the substring, which would be given a
		// negative length
		return "case when position('.' in %s) > 1 then case when %s then cast(%s as decimal(%d,0)) end end"
				.formatted(version, digits(part, VERSION_DIGITS), part, VERSION_DIGITS);
	}

	/**
	 * @param expression a value
	 * @return 1 when it's null and 0 otherwise, to sort by first so that
	 *         nulls come last, where MariaDB and PostgreSQL would put them at
	 *         opposite ends
	 */
	public static String nullsLast(String expression) {
		return "case when %s is null then 1 else 0 end".formatted(expression);
	}

	/**
	 * @return a condition that holds when the text is 1 to {@code max} digits
	 *         and nothing else. It measures what is left once the digits are
	 *         taken out, as MariaDB's comparison of text ignores trailing
	 *         spaces.
	 */
	static String digits(String expression, int max) {
		String stripped = expression;
		for (char digit = '0'; digit <= '9'; digit++)
			stripped = "replace(%s, '%c', '')".formatted(stripped, digit);
		return "(length(%s) between 1 and %d and length(%s) = 0)".formatted(expression, max, stripped);
	}
}
