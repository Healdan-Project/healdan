// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/**
 * How to list documents (M6 spec sections 6.2 and 9.2): the order, the filters
 * and the page. An empty filter doesn't filter; values within one filter are
 * alternatives, and the filters all apply together.
 *
 * @param sort what to sort by; null keeps the order of a list of ids given
 *        (reversed when descending), and lists a folder by title
 * @param descending whether the order is reversed; documents with no value
 *        to sort by come last either way, and ties go by id, the same way
 * @param kinds the {@link FileKinds} to keep
 * @param authors the usernames of the authors to keep
 * @param classes the classes to keep
 * @param clients the client numbers to keep, such as {@code 998}
 * @param matters the matters to keep, by client and matter number, such as
 *        {@code 998.1}
 * @param edited the edit dates to keep, or null for any
 * @param offset how many documents to skip
 * @param limit the most documents to return; 0 asks for the total alone
 */
public record ListingQuery(Sort sort, boolean descending, Set<String> kinds, Set<String> authors,
		Set<String> classes, Set<String> clients, Set<String> matters, Edited edited, int offset, int limit) {

	/**
	 * What a listing sorts by
	 */
	public enum Sort {
		/** The file name, ignoring case */
		TITLE,
		/** The doc #, as a number */
		DOC_NUMBER,
		/** The file's version, as a list shows it, by its major then its minor number */
		VERSION,
		/** The Author's name, as it shows */
		AUTHOR,
		/** When the document last changed */
		EDITED
	}

	/**
	 * The days a document's last change falls on, both included, each from its
	 * midnight to the next in the time zone given (M6 R90: the person's own,
	 * which the app sends).
	 *
	 * @param from the first day, or null for no start
	 * @param to the last day, or null for no end
	 * @param zone the time zone the days are in; null for the server's
	 */
	public record Edited(LocalDate from, LocalDate to, ZoneId zone) {

		public Edited {
			if (from != null && to != null && from.isAfter(to))
				throw new IllegalArgumentException("The edit dates end before they start");
		}

		/**
		 * The days in the server's time zone
		 */
		public Edited(LocalDate from, LocalDate to) {
			this(from, to, null);
		}

		/**
		 * @return the time zone the days are in: the one given, else the
		 *         server's
		 */
		public ZoneId zoneOrServers() {
			return zone != null ? zone : ZoneId.systemDefault();
		}

		/**
		 * @param name {@code today}, {@code 7d} (today and the six days
		 *        before), {@code 30d} (today and the 29 before) or {@code year}
		 *        (from the 1st of January)
		 * @param clock what today is, and in which time zone, which the days
		 *        are then in too
		 * @return the days, up to today
		 * @throws IllegalArgumentException for another name
		 */
		public static Edited preset(String name, Clock clock) {
			LocalDate today = LocalDate.now(clock);
			ZoneId zone = clock.getZone();
			return switch (name == null ? "" : name) {
			case "today" -> new Edited(today, today, zone);
			case "7d" -> new Edited(today.minusDays(6), today, zone);
			case "30d" -> new Edited(today.minusDays(29), today, zone);
			case "year" -> new Edited(today.withDayOfYear(1), today, zone);
			default -> throw new IllegalArgumentException("No edit date preset " + name);
			};
		}
	}

	public ListingQuery {
		kinds = copy(kinds);
		authors = copy(authors);
		classes = copy(classes);
		clients = copy(clients);
		matters = copy(matters);
		for (String kind : kinds)
			if (!FileKinds.isKind(kind))
				throw new IllegalArgumentException("No file kind " + kind);
		if (offset < 0 || limit < 0)
			throw new IllegalArgumentException("The offset and the limit can't be negative");
	}

	private static Set<String> copy(Set<String> values) {
		return values == null ? Set.of() : Set.copyOf(values);
	}

	/**
	 * @param sort what to sort by, or null
	 * @param descending whether the order is reversed
	 * @param offset how many documents to skip
	 * @param limit the most documents to return
	 * @return the query, with no filters
	 */
	public static ListingQuery of(Sort sort, boolean descending, int offset, int limit) {
		return new ListingQuery(sort, descending, null, null, null, null, null, null, offset, limit);
	}

	/**
	 * @return the same query for another page
	 */
	public ListingQuery page(int newOffset, int newLimit) {
		return new ListingQuery(sort, descending, kinds, authors, classes, clients, matters, edited, newOffset,
				newLimit);
	}

	public ListingQuery withKinds(Set<String> newKinds) {
		return new ListingQuery(sort, descending, newKinds, authors, classes, clients, matters, edited, offset,
				limit);
	}

	public ListingQuery withAuthors(Set<String> newAuthors) {
		return new ListingQuery(sort, descending, kinds, newAuthors, classes, clients, matters, edited, offset,
				limit);
	}

	public ListingQuery withClasses(Set<String> newClasses) {
		return new ListingQuery(sort, descending, kinds, authors, newClasses, clients, matters, edited, offset,
				limit);
	}

	public ListingQuery withClients(Set<String> newClients) {
		return new ListingQuery(sort, descending, kinds, authors, classes, newClients, matters, edited, offset,
				limit);
	}

	public ListingQuery withMatters(Set<String> newMatters) {
		return new ListingQuery(sort, descending, kinds, authors, classes, clients, newMatters, edited, offset,
				limit);
	}

	public ListingQuery withEdited(Edited newEdited) {
		return new ListingQuery(sort, descending, kinds, authors, classes, clients, matters, newEdited, offset,
				limit);
	}

	/**
	 * @return whether any filter is set
	 */
	public boolean filtered() {
		return !kinds.isEmpty() || !authors.isEmpty() || !classes.isEmpty() || !clients.isEmpty()
				|| !matters.isEmpty() || edited != null && (edited.from() != null || edited.to() != null);
	}

	/**
	 * @param facet a facet's name ({@link Facets#NAMES})
	 * @return the same query without the facet's own filter, as its counts
	 *         are taken
	 */
	ListingQuery without(String facet) {
		return switch (facet) {
		case Facets.TYPE -> withKinds(null);
		case Facets.AUTHOR -> withAuthors(null);
		case Facets.CLASS -> withClasses(null);
		case Facets.CLIENT -> withClients(null);
		case Facets.MATTER -> withMatters(null);
		default -> throw new IllegalArgumentException("No facet " + facet);
		};
	}
}
