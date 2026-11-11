// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.listing.ListingQuery.Sort;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.security.FolderReads;
import com.logicaldoc.util.spring.Context;

/**
 * A folder's documents paged, sorted, filtered and counted in the database
 * (M6 spec sections 9.2 and 9.5), so a page reads only its own rows.
 * <p>
 * It shows what {@link AccessChecks#readableDocuments} would, restated in
 * SQL: the folder's READ asked once; a document whose own ACL rows for the
 * user's groups grant anything is read if they grant READ, any other takes
 * its folder's answer, and an administrator reads all; it must be published
 * now, unless the user {@link AccessChecks#seesUnpublished sees unpublished
 * documents}; deleted documents never show. Besides, as the API's lists
 * decide, a document shows only with a doc # of 1 to 18 digits
 * ({@link ListingSql#isDocNumber}). The tests compare it with
 * {@code readableDocuments}: a change to one is a change to the other.
 * <p>
 * Author, Class, Client and Matter are the document's own properties
 * ({@code ld_document_ext}), and File Type its extension.
 */
@Component
public class FolderListing {

	/** The most ids {@link #page(List, ListingQuery)} and {@link #facets(List, ListingQuery, Set)} take */
	public static final int MAX_IDS = 1000;

	/** The most ids {@link #sortedPage} takes: a search's window, sorted in one query */
	public static final int MAX_SORTED_IDS = 10000;

	/** The documents' properties a listing sorts, filters or counts by, and the aliases they join as */
	private static final String AUTHOR = "ax";

	private static final String CLASS = "cx";

	private static final String CLIENT = "clx";

	private static final String MATTER = "mx";

	/** The Author's user */
	private static final String AUTHOR_USER = "au";

	/** An ACL row {@code a} is one of the user's groups' */
	private static final String USER_GROUPS = "a.ld_groupid in (select g.ld_groupid from ld_usergroup g where g.ld_userid = :user)";

	private final NamedParameterJdbcTemplate jdbc;

	@Autowired
	public FolderListing(DataSource dataSource) {
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static FolderListing get() {
		return Context.get(FolderListing.class);
	}

	/**
	 * @param folder the folder
	 * @param user who looks, with its groups
	 * @param query the order, the filters and the page; no sort is by title
	 * @return the page of the folder's documents the user may read, and how
	 *         many of them pass the filters
	 * @throws PersistenceException error at data layer
	 */
	public ListedPage page(Folder folder, User user, ListingQuery query) throws PersistenceException {
		Scope scope = readable(folder, user);
		Sort sort = query.sort() != null ? query.sort() : Sort.TITLE;

		List<Long> ids = List.of();
		if (query.limit() > 0) {
			Select select = new Select(scope, query);
			String order = order(select, sort, query.descending());
			select.params.addValue("limit", query.limit()).addValue("offset", query.offset());
			ids = jdbc.queryForList("select d.ld_id" + select.from() + order + ListingSql.PAGE, select.params,
					Long.class);
		}

		// A page that isn't full, and isn't past the end, says the total itself
		long total;
		if (query.limit() > 0 && ids.size() < query.limit() && (!ids.isEmpty() || query.offset() == 0))
			total = (long) query.offset() + ids.size();
		else {
			Select select = new Select(scope, query);
			total = jdbc.queryForObject("select count(*)" + select.from(), select.params, Long.class);
		}
		return new ListedPage(ids, total);
	}

	/**
	 * @param folder the folder
	 * @param user who looks, with its groups
	 * @param query the filters; the order and the page don't matter
	 * @param which the facets to count ({@link Facets#NAMES})
	 * @return the counts over the folder's documents the user may read; each
	 *         facet applies every filter but its own, so its options stay as
	 *         they were when one is ticked
	 * @throws PersistenceException error at data layer
	 * @throws IllegalArgumentException for an unknown facet
	 */
	public Facets facets(Folder folder, User user, ListingQuery query, Set<String> which)
			throws PersistenceException {
		Facets.check(which);
		return facets(readable(folder, user), query, which);
	}

	/**
	 * For documents already known to be readable, such as a search folder's.
	 * They are checked for nothing else but deletion and a doc #: for ids from
	 * anywhere else, {@link #page(List, User, ListingQuery)} applies the read
	 * rule.
	 *
	 * @param docIds the documents, in their order; at most {@link #MAX_IDS}
	 * @param query the order, the filters and the page; no sort keeps the
	 *        order given, reversed when descending
	 * @return the page of the documents that pass the filters, and how many
	 *         pass them
	 */
	public ListedPage page(List<Long> docIds, ListingQuery query) {
		if (docIds.isEmpty())
			return new ListedPage(List.of(), 0);
		return page(ids(docIds), docIds, query);
	}

	private ListedPage page(Scope scope, List<Long> docIds, ListingQuery query) {
		Select select = new Select(scope, query);
		String order = query.sort() == null ? "" : order(select, query.sort(), query.descending());
		List<Long> passed = jdbc.queryForList("select d.ld_id" + select.from() + order, select.params, Long.class);
		if (query.sort() == null) {
			Set<Long> kept = new HashSet<>(passed);
			passed = new ArrayList<>(docIds.stream().filter(kept::contains).distinct().toList());
			if (query.descending())
				Collections.reverse(passed);
		}
		int from = Math.min(query.offset(), passed.size());
		int to = (int) Math.min((long) query.offset() + query.limit(), passed.size());
		return new ListedPage(passed.subList(from, to), passed.size());
	}

	/**
	 * As {@link #facets(Folder, User, ListingQuery, Set)}, for documents
	 * already known to be readable; for ids from anywhere else,
	 * {@link #facets(List, User, ListingQuery, Set)} applies the read rule.
	 *
	 * @param docIds the documents; at most {@link #MAX_IDS}
	 * @param query the filters
	 * @param which the facets to count
	 * @return the counts
	 */
	public Facets facets(List<Long> docIds, ListingQuery query, Set<String> which) {
		Facets.check(which);
		if (docIds.isEmpty()) {
			Map<String, Map<String, Long>> none = new LinkedHashMap<>();
			for (String facet : Facets.NAMES)
				if (which.contains(facet))
					none.put(facet, Map.of());
			return new Facets(none);
		}
		return facets(ids(docIds), query, which);
	}

	/**
	 * As {@link #page(List, ListingQuery)}, for documents anywhere, applying
	 * the read rule again: a document the user can't read, or that is deleted
	 * or not published to the user, is left out, whoever passed it in.
	 *
	 * @param docIds the documents, in their order; at most {@link #MAX_IDS}
	 * @param user who looks, with its groups
	 * @param query the order, the filters and the page; no sort keeps the
	 *        order given, reversed when descending
	 * @return the page of the documents that the user may read and that pass
	 *         the filters, and how many do
	 * @throws PersistenceException error at data layer
	 */
	public ListedPage page(List<Long> docIds, User user, ListingQuery query) throws PersistenceException {
		return page(docIds, new FolderReads(user), query);
	}

	/**
	 * As {@link #page(List, User, ListingQuery)}, asking each folder's READ
	 * only if the caller's {@link FolderReads} doesn't know it yet
	 *
	 * @param docIds the documents, in their order; at most {@link #MAX_IDS}
	 * @param reads who looks, and the folders' READ known so far
	 * @param query the order, the filters and the page
	 * @return the page, and how many pass
	 * @throws PersistenceException error at data layer
	 */
	public ListedPage page(List<Long> docIds, FolderReads reads, ListingQuery query) throws PersistenceException {
		if (docIds.isEmpty())
			return new ListedPage(List.of(), 0);
		return page(readable(docIds, reads, MAX_IDS), docIds, query);
	}

	/**
	 * A page of documents anywhere, sorted, filtered and paged in one query,
	 * applying the read rule as {@link #page(List, User, ListingQuery)} does:
	 * a search's window sorted by a column.
	 *
	 * @param docIds the documents; at most {@link #MAX_SORTED_IDS}
	 * @param reads who looks, and the folders' READ known so far
	 * @param query the order, which must be set, the filters and the page
	 * @return the page of the documents the user may read that pass the
	 *         filters, in the order asked, and how many pass
	 * @throws PersistenceException error at data layer
	 * @throws IllegalArgumentException with no sort, or too many ids
	 */
	public ListedPage sortedPage(List<Long> docIds, FolderReads reads, ListingQuery query)
			throws PersistenceException {
		if (query.sort() == null)
			throw new IllegalArgumentException("A sorted page needs a sort");
		if (docIds.isEmpty())
			return new ListedPage(List.of(), 0);
		Scope scope = readable(docIds, reads, MAX_SORTED_IDS);

		List<Long> ids = List.of();
		if (query.limit() > 0) {
			Select select = new Select(scope, query);
			String order = order(select, query.sort(), query.descending());
			select.params.addValue("limit", query.limit()).addValue("offset", query.offset());
			ids = jdbc.queryForList("select d.ld_id" + select.from() + order + ListingSql.PAGE, select.params,
					Long.class);
		}
		long total;
		if (query.limit() > 0 && ids.size() < query.limit() && (!ids.isEmpty() || query.offset() == 0))
			total = (long) query.offset() + ids.size();
		else {
			Select select = new Select(scope, query);
			total = jdbc.queryForObject("select count(*)" + select.from(), select.params, Long.class);
		}
		return new ListedPage(ids, total);
	}

	/**
	 * As {@link #facets(List, ListingQuery, Set)}, for documents anywhere,
	 * applying the read rule again, as {@link #page(List, User, ListingQuery)}
	 * does.
	 *
	 * @param docIds the documents; at most {@link #MAX_IDS}
	 * @param user who looks, with its groups
	 * @param query the filters
	 * @param which the facets to count
	 * @return the counts over the documents the user may read
	 * @throws PersistenceException error at data layer
	 * @throws IllegalArgumentException for an unknown facet
	 */
	public Facets facets(List<Long> docIds, User user, ListingQuery query, Set<String> which)
			throws PersistenceException {
		return facets(docIds, new FolderReads(user), query, which);
	}

	/**
	 * As {@link #facets(List, User, ListingQuery, Set)}, asking each folder's
	 * READ only if the caller's {@link FolderReads} doesn't know it yet
	 *
	 * @param docIds the documents; at most {@link #MAX_IDS}
	 * @param reads who looks, and the folders' READ known so far
	 * @param query the filters
	 * @param which the facets to count
	 * @return the counts over the documents the user may read
	 * @throws PersistenceException error at data layer
	 */
	public Facets facets(List<Long> docIds, FolderReads reads, ListingQuery query, Set<String> which)
			throws PersistenceException {
		Facets.check(which);
		if (docIds.isEmpty())
			return facets(List.of(), query, which);
		return facets(readable(docIds, reads, MAX_IDS), query, which);
	}

	private Facets facets(Scope scope, ListingQuery query, Set<String> which) {
		Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
		for (String facet : Facets.NAMES)
			if (which.contains(facet))
				counts.put(facet, count(scope, query.without(facet), facet));
		return new Facets(counts);
	}

	private Map<String, Long> count(Scope scope, ListingQuery query, String facet) {
		Select select = new Select(scope, query);
		Map<String, Long> counts = new HashMap<>();
		if (Facets.TYPE.equals(facet)) {
			// Counted by extension, then added up by kind
			jdbc.query("select lower(d.ld_type), count(*)" + select.from() + " group by lower(d.ld_type)",
					select.params, rs -> {
						counts.merge(FileKinds.of(rs.getString(1)), rs.getLong(2), Long::sum);
					});
		} else if (Facets.MATTER.equals(facet)) {
			select.join(CLIENT, HealdanConstants.ATTR_CLIENT);
			select.join(MATTER, HealdanConstants.ATTR_MATTER);
			select.where.add(CLIENT + ".ld_stringvalue is not null and " + MATTER + ".ld_stringvalue is not null");
			jdbc.query("select %1$s.ld_stringvalue, %2$s.ld_stringvalue, count(*)%3$s group by %1$s.ld_stringvalue, %2$s.ld_stringvalue"
					.formatted(CLIENT, MATTER, select.from()), select.params, rs -> {
						String client = rs.getString(1);
						String matter = rs.getString(2);
						if (StringUtils.isNotBlank(client) && StringUtils.isNotBlank(matter))
							counts.merge(client + "." + matter, rs.getLong(3), Long::sum);
					});
		} else {
			String alias = switch (facet) {
			case Facets.AUTHOR -> select.join(AUTHOR, HealdanConstants.ATTR_AUTHOR);
			case Facets.CLASS -> select.join(CLASS, HealdanConstants.ATTR_DOCCLASS);
			default -> select.join(CLIENT, HealdanConstants.ATTR_CLIENT);
			};
			select.where.add(alias + ".ld_stringvalue is not null");
			jdbc.query("select %1$s.ld_stringvalue, count(*)%2$s group by %1$s.ld_stringvalue".formatted(alias,
					select.from()), select.params, rs -> {
						String value = rs.getString(1);
						if (StringUtils.isNotBlank(value))
							counts.merge(value, rs.getLong(2), Long::sum);
					});
		}
		return counts;
	}

	/**
	 * The documents of the folder the user may read, as
	 * {@link AccessChecks#readableDocuments} decides, with a doc #
	 */
	private static Scope readable(Folder folder, User user) throws PersistenceException {
		boolean admin = user.isAdmin();
		// Asked once, as readableDocuments asks it
		boolean folderRead = admin || FolderDAO.get().isReadAllowed(folder.getId(), user.getId());
		boolean seesUnpublished = AccessChecks.seesUnpublished(user);
		long folderId = folder.getId();
		long userId = user.getId();
		return select -> {
			select.where.add("d.ld_folderid = :folder");
			select.where.add("d.ld_deleted = 0");
			select.where.add(ListingSql.isDocNumber("d"));
			select.params.addValue("folder", folderId);
			if (!seesUnpublished)
				published(select);
			if (!admin) {
				// The user's own ACL rows that grant anything decide, by READ; with none, the folder does
				select.where.add(folderRead ? "(%s or not %s)".formatted(reading(), granting())
						: "(%s and %s)".formatted(granting(), reading()));
				select.params.addValue("user", userId);
			}
		};
	}

	/**
	 * Documents given by id, anywhere, that the user may read: the rule of
	 * {@link #readable(Folder, User)}, with each document's folder's READ asked
	 * once per folder. With {@code R} for "a row of the user's groups grants
	 * READ" and {@code G} for "a row of theirs grants anything", R implies G,
	 * so that rule, {@code R or not G} in a readable folder and {@code G and R}
	 * in another, is {@code R or (the folder is readable and not G)}.
	 */
	private Scope readable(List<Long> docIds, FolderReads reads, int max) throws PersistenceException {
		Scope listed = ids(docIds, max);
		User user = reads.user();
		boolean admin = user.isAdmin();
		boolean seesUnpublished = AccessChecks.seesUnpublished(user);
		List<Long> readFolders = new ArrayList<>();
		if (!admin) {
			MapSqlParameterSource params = new MapSqlParameterSource("ids", List.copyOf(new TreeSet<>(docIds)));
			for (Long folderId : jdbc.queryForList("select distinct ld_folderid from ld_document where ld_id in (:ids)",
					params, Long.class))
				if (reads.readable(folderId))
					readFolders.add(folderId);
		}
		long userId = user.getId();
		return select -> {
			listed.apply(select);
			if (!seesUnpublished)
				published(select);
			if (!admin) {
				select.where.add(readFolders.isEmpty() ? reading()
						: "(%s or (d.ld_folderid in (:readfolders) and not %s))".formatted(reading(), granting()));
				if (!readFolders.isEmpty())
					select.params.addValue("readfolders", readFolders);
				select.params.addValue("user", userId);
			}
		};
	}

	/** Document.isPublishing */
	private static void published(Select select) {
		select.where.add("""
				d.ld_published = 1
				and (d.ld_startpublishing is null or d.ld_startpublishing <= :now)
				and (d.ld_stoppublishing is null or d.ld_stoppublishing > :now)""");
		select.params.addValue("now", new Timestamp(System.currentTimeMillis()));
	}

	/** Some own ACL row of the user's groups grants anything */
	private static String granting() {
		return "exists (select 1 from ld_document_acl a where a.ld_docid = d.ld_id and %s and (%s) > 0)"
				.formatted(USER_GROUPS, AccessChecks.anyPermission("a."));
	}

	/** Some own ACL row of the user's groups grants READ */
	private static String reading() {
		return "exists (select 1 from ld_document_acl a where a.ld_docid = d.ld_id and %s and a.ld_read > 0)"
				.formatted(USER_GROUPS);
	}

	/**
	 * Documents given by id, at most {@link #MAX_IDS}
	 */
	private static Scope ids(List<Long> docIds) {
		return ids(docIds, MAX_IDS);
	}

	/**
	 * Documents given by id, at most {@code max}
	 */
	private static Scope ids(List<Long> docIds, int max) {
		if (docIds.size() > max)
			throw new IllegalArgumentException("At most %d documents at a time, not %d".formatted(max,
					docIds.size()));
		List<Long> ids = List.copyOf(new TreeSet<>(docIds));
		return select -> {
			select.where.add("d.ld_id in (:ids)");
			select.where.add("d.ld_deleted = 0");
			select.where.add(ListingSql.isDocNumber("d"));
			select.params.addValue("ids", ids);
		};
	}

	/**
	 * Adds the filters' conditions
	 */
	private static void filter(Select select, ListingQuery query) {
		if (!query.kinds().isEmpty()) {
			Set<String> extensions = new TreeSet<>();
			for (String kind : query.kinds())
				extensions.addAll(FileKinds.extensions(kind));
			List<String> any = new ArrayList<>();
			if (!extensions.isEmpty()) {
				any.add("lower(d.ld_type) in (:kinds)");
				select.params.addValue("kinds", extensions);
			}
			if (query.kinds().contains(FileKinds.OTHER)) {
				any.add("d.ld_type is null or lower(d.ld_type) not in (:known)");
				select.params.addValue("known", new TreeSet<>(FileKinds.known()));
			}
			select.where.add("(" + String.join(" or ", any) + ")");
		}
		values(select, AUTHOR, HealdanConstants.ATTR_AUTHOR, "authors", query.authors());
		values(select, CLASS, HealdanConstants.ATTR_DOCCLASS, "classes", query.classes());
		values(select, CLIENT, HealdanConstants.ATTR_CLIENT, "clients", query.clients());
		matters(select, query.matters());
		edited(select, query.edited());
	}

	private static void values(Select select, String alias, String attribute, String param, Set<String> values) {
		if (values.isEmpty())
			return;
		select.join(alias, attribute);
		select.where.add("%s.ld_stringvalue in (:%s)".formatted(alias, param));
		select.params.addValue(param, new TreeSet<>(values));
	}

	/**
	 * A matter is its client's number and its own, such as {@code 998.1}: the
	 * document's Client and Matter properties
	 */
	private static void matters(Select select, Set<String> matters) {
		if (matters.isEmpty())
			return;
		select.join(CLIENT, HealdanConstants.ATTR_CLIENT);
		select.join(MATTER, HealdanConstants.ATTR_MATTER);
		List<String> any = new ArrayList<>();
		int i = 0;
		for (String matter : new TreeSet<>(matters)) {
			int dot = matter.indexOf('.');
			if (dot <= 0 || dot == matter.length() - 1)
				continue;
			any.add("(%s.ld_stringvalue = :matterclient%d and %s.ld_stringvalue = :matter%d)".formatted(CLIENT, i,
					MATTER, i));
			select.params.addValue("matterclient" + i, matter.substring(0, dot));
			select.params.addValue("matter" + i, matter.substring(dot + 1));
			i++;
		}
		// None that names a matter keeps nothing
		select.where.add(any.isEmpty() ? "1 = 0" : "(" + String.join(" or ", any) + ")");
	}

	/**
	 * The days, in the server's time zone, the last day to its end
	 */
	private static void edited(Select select, ListingQuery.Edited edited) {
		if (edited == null)
			return;
		ZoneId zone = ZoneId.systemDefault();
		if (edited.from() != null) {
			select.where.add("d.ld_lastmodified >= :editedfrom");
			select.params.addValue("editedfrom", startOf(edited.from(), zone));
		}
		if (edited.to() != null) {
			select.where.add("d.ld_lastmodified < :editedto");
			select.params.addValue("editedto", startOf(edited.to().plusDays(1), zone));
		}
	}

	private static Timestamp startOf(LocalDate day, ZoneId zone) {
		return Timestamp.from(day.atStartOfDay(zone).toInstant());
	}

	/**
	 * @return the {@code order by}: the key, with nulls last both ways, then
	 *         the id, so that pages never overlap
	 */
	private static String order(Select select, Sort sort, boolean descending) {
		String direction = descending ? " desc" : "";
		List<String> keys = switch (sort) {
		case TITLE -> List.of("lower(d.ld_filename)");
		case DOC_NUMBER -> List.of(ListingSql.docNumberOrder("d"));
		case VERSION -> List.of(ListingSql.versionMajor("d"), ListingSql.versionMinor("d"));
		case AUTHOR -> List.of(authorName(select));
		case EDITED -> List.of("d.ld_lastmodified");
		};
		List<String> order = new ArrayList<>();
		for (String key : keys) {
			order.add(ListingSql.nullsLast(key));
			order.add(key + direction);
		}
		order.add("d.ld_id" + direction);
		return " order by " + String.join(", ", order);
	}

	/**
	 * @return the Author's name, as {@code User.getFullName} makes it, in
	 *         lower case; the username kept with the property for an Author
	 *         who is no longer a user, so the order is that of the names
	 *         {@link DocumentAuthors} shows
	 */
	private static String authorName(Select select) {
		select.join(AUTHOR, HealdanConstants.ATTR_AUTHOR);
		select.joinAuthorUser();
		String u = AUTHOR_USER;
		return """
				lower(case when %1$s.ld_id is null then %2$s.ld_stringvalue
				 when %1$s.ld_firstname is not null and %1$s.ld_name is not null
				  then concat(concat(%1$s.ld_firstname, ' '), %1$s.ld_name)
				 when %1$s.ld_name is not null then %1$s.ld_name
				 when %1$s.ld_firstname is not null then %1$s.ld_firstname
				 else %1$s.ld_username end)""".formatted(u, AUTHOR);
	}

	/**
	 * What a listing's documents are, before the filters
	 */
	@FunctionalInterface
	private interface Scope {
		void apply(Select select);
	}

	/**
	 * A query on {@code ld_document d}, with the scope and the filters applied
	 */
	private static final class Select {

		private final StringBuilder joins = new StringBuilder();

		private final Set<String> joined = new HashSet<>();

		private final List<String> where = new ArrayList<>();

		private final MapSqlParameterSource params = new MapSqlParameterSource();

		Select(Scope scope, ListingQuery query) {
			scope.apply(this);
			filter(this, query);
		}

		/**
		 * Joins the document's property, once; a document has one row by name
		 *
		 * @return the alias
		 */
		String join(String alias, String attribute) {
			if (joined.add(alias)) {
				joins.append(" left join ld_document_ext %1$s on %1$s.ld_docid = d.ld_id and %1$s.ld_name = :%1$sname"
						.formatted(alias));
				params.addValue(alias + "name", attribute);
			}
			return alias;
		}

		void joinAuthorUser() {
			if (joined.add(AUTHOR_USER))
				joins.append(" left join ld_user %1$s on %1$s.ld_id = %2$s.ld_intvalue and %1$s.ld_deleted = 0"
						.formatted(AUTHOR_USER, AUTHOR));
		}

		String from() {
			return " from ld_document d" + joins + " where " + String.join(" and ", where);
		}
	}
}
