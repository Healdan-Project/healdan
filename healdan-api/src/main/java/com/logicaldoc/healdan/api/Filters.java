// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;

import com.logicaldoc.healdan.listing.Facets;
import com.logicaldoc.healdan.listing.FileKinds;
import com.logicaldoc.healdan.listing.ListingQuery;
import com.logicaldoc.healdan.listing.ListingQuery.Edited;
import com.logicaldoc.healdan.listing.ListingQuery.Sort;

/**
 * A list's {@code sort}, {@code order}, filters and {@code facets} query
 * parameters (M6 spec sections 6.2 and 9.1), as a {@link ListingQuery}.
 * Repeating a filter's parameter gives it several values, any of which
 * passes: {@code type=pdf&type=word}.
 *
 * @param query the order and the filters, offset 0 and limit 0: the caller
 *        sets the page
 * @param foldersOnly whether {@code type=folder} asked for subfolders alone
 * @param facets the facets asked for, in {@link Facets#NAMES}' order
 */
record Filters(ListingQuery query, boolean foldersOnly, Set<String> facets) {

	/** The {@code sort} parameter's values */
	static final Map<String, Sort> SORTS = Map.of("title", Sort.TITLE, "docNumber", Sort.DOC_NUMBER, "version",
			Sort.VERSION, "author", Sort.AUTHOR, "edited", Sort.EDITED);

	/** The {@code edited} parameter's values */
	static final List<String> EDITED = List.of("today", "7d", "30d", "year");

	/** A client number, as the Client property holds it */
	private static final Pattern CLIENT = Pattern.compile("[0-9]{1,18}");

	/** A client's number and a matter's, as the Matter filter takes them */
	private static final Pattern MATTER = Pattern.compile("[0-9]{1,18}\\.[0-9]{1,18}");

	/**
	 * @param type {@code folder} for the subfolders alone, or the file kinds to
	 *        keep
	 * @param sort {@code title}, {@code docNumber}, {@code version},
	 *        {@code author} or {@code edited}; none for the list's own order
	 * @param order {@code asc} (the default) or {@code desc}
	 * @param author the Authors' usernames
	 * @param docClass the classes
	 * @param client the client numbers
	 * @param matter the matters, such as {@code 998.1}
	 * @param edited {@code today}, {@code 7d}, {@code 30d} or {@code year}
	 * @param from the first day of an edit-date range, such as
	 *        {@code 2026-06-01}
	 * @param to the last day of the range
	 * @param facets the counts to take, as a comma list of {@code type},
	 *        {@code author}, {@code class}, {@code client} and {@code matter}
	 * @param clock what today is, for {@code edited}
	 * @return the filters
	 * @throws ApiProblem {@code 400 bad-request} for a value it doesn't take
	 */
	static Filters of(List<String> type, String sort, String order, List<String> author, List<String> docClass,
			List<String> client, List<String> matter, String edited, String from, String to, String facets,
			Clock clock) {
		Set<String> types = values(type, "type");
		boolean foldersOnly = types.remove("folder");
		for (String kind : types)
			if (!FileKinds.isKind(kind))
				throw ApiProblem.badRequest("The type can be folder, or one of %s, not %s."
						.formatted(String.join(", ", FileKinds.KINDS), kind));

		Sort sorting = null;
		if (StringUtils.isNotEmpty(sort)) {
			sorting = SORTS.get(sort);
			if (sorting == null)
				throw ApiProblem.badRequest(
						"The sort can be title, docNumber, version, author or edited, not %s.".formatted(sort));
		}
		if (order != null && !"asc".equals(order) && !"desc".equals(order))
			throw ApiProblem.badRequest("The order can be asc or desc, not %s.".formatted(order));

		Set<String> clients = values(client, "client");
		for (String number : clients)
			if (!CLIENT.matcher(number).matches())
				throw ApiProblem.badRequest("A client is its number, such as 998, not %s.".formatted(number));
		Set<String> matters = values(matter, "matter");
		for (String number : matters)
			if (!MATTER.matcher(number).matches())
				throw ApiProblem.badRequest(
						"A matter is its client's number and its own, such as 998.1, not %s.".formatted(number));

		ListingQuery query = new ListingQuery(sorting, "desc".equals(order), types, values(author, "author"),
				values(docClass, "class"), clients, matters, edited(edited, from, to, clock), 0, 0);

		Set<String> counts = facets(facets);
		if (foldersOnly && (!types.isEmpty() || query.filtered() || !counts.isEmpty()))
			throw ApiProblem.badRequest("type=folder lists the subfolders alone, which take no filters or facets.");
		return new Filters(query, foldersOnly, counts);
	}

	/**
	 * @return the parameter's values, trimmed, without repeats
	 * @throws ApiProblem {@code 400} for an empty one
	 */
	private static Set<String> values(List<String> given, String name) {
		Set<String> values = new LinkedHashSet<>();
		if (given == null)
			return values;
		for (String value : given) {
			String trimmed = StringUtils.trimToEmpty(value);
			if (trimmed.isEmpty())
				throw ApiProblem.badRequest("The %s can't be empty.".formatted(name));
			values.add(trimmed);
		}
		return values;
	}

	private static Edited edited(String preset, String from, String to, Clock clock) {
		boolean range = StringUtils.isNotEmpty(from) || StringUtils.isNotEmpty(to);
		if (StringUtils.isNotEmpty(preset)) {
			if (range)
				throw ApiProblem.badRequest("Give edited, or from and to, not both.");
			if (!EDITED.contains(preset))
				throw ApiProblem.badRequest(
						"The edited filter can be today, 7d, 30d or year, not %s; or give from and to.".formatted(preset));
			return Edited.preset(preset, clock);
		}
		if (!range)
			return null;
		LocalDate first = day(from, "from");
		LocalDate last = day(to, "to");
		if (first != null && last != null && first.isAfter(last))
			throw ApiProblem.badRequest("The edit dates can't end before they start.");
		return new Edited(first, last);
	}

	private static LocalDate day(String value, String name) {
		if (StringUtils.isEmpty(value))
			return null;
		try {
			return LocalDate.parse(value);
		} catch (DateTimeParseException e) {
			throw ApiProblem.badRequest("The %s date is a day, such as 2026-06-01, not %s.".formatted(name, value));
		}
	}

	private static Set<String> facets(String facets) {
		Set<String> asked = new LinkedHashSet<>();
		if (StringUtils.isBlank(facets))
			return asked;
		for (String name : facets.split(",", -1)) {
			String trimmed = name.trim();
			if (trimmed.isEmpty())
				continue;
			if (!Facets.NAMES.contains(trimmed))
				throw ApiProblem.badRequest("The facets can be %s, not %s."
						.formatted(String.join(", ", Facets.NAMES), trimmed));
			asked.add(trimmed);
		}
		Set<String> ordered = new LinkedHashSet<>();
		for (String name : Facets.NAMES)
			if (asked.contains(name))
				ordered.add(name);
		return Collections.unmodifiableSet(ordered);
	}
}
