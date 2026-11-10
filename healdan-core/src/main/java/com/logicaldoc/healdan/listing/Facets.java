// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How many documents each filter's option would leave (M6 spec sections 6.2
 * and 9.2), counted over the documents the person can read.
 *
 * @param counts by facet name, each option's count, most first, then by
 *        option; an option with no documents isn't there:
 *        <ul>
 *        <li>{@link #TYPE}: the {@link FileKinds} kind, such as {@code word}</li>
 *        <li>{@link #AUTHOR}: the Author's username</li>
 *        <li>{@link #CLASS}: the Class</li>
 *        <li>{@link #CLIENT}: the client number, such as {@code 998}</li>
 *        <li>{@link #MATTER}: the client and matter numbers, such as
 *        {@code 998.1}</li>
 *        </ul>
 */
public record Facets(Map<String, Map<String, Long>> counts) {

	public static final String TYPE = "type";

	public static final String AUTHOR = "author";

	public static final String CLASS = "class";

	public static final String CLIENT = "client";

	public static final String MATTER = "matter";

	/** Every facet, in the order the filters show them */
	public static final List<String> NAMES = List.of(TYPE, AUTHOR, CLASS, CLIENT, MATTER);

	public Facets {
		Map<String, Map<String, Long>> copy = new LinkedHashMap<>();
		counts.forEach((name, options) -> copy.put(name, sorted(options)));
		counts = Collections.unmodifiableMap(copy);
	}

	/**
	 * @param names facet names
	 * @throws IllegalArgumentException if one isn't a facet
	 */
	static void check(Set<String> names) {
		for (String name : names)
			if (!NAMES.contains(name))
				throw new IllegalArgumentException("No facet " + name);
	}

	private static Map<String, Long> sorted(Map<String, Long> options) {
		Map<String, Long> sorted = new LinkedHashMap<>();
		options.entrySet().stream().filter(option -> option.getValue() > 0)
				.sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
						.thenComparing(Map.Entry::getKey))
				.forEach(option -> sorted.put(option.getKey(), option.getValue()));
		return Collections.unmodifiableMap(sorted);
	}
}
