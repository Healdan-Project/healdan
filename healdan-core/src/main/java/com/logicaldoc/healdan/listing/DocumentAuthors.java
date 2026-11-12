// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.util.spring.Context;

/**
 * The Authors of documents, read for a whole page at once, so a list shows
 * them without a query per row.
 * <p>
 * The Author is the document's own {@code author} property, a user field: the
 * user's id and, kept with it, the username. A user that still exists is
 * shown by name, as {@code User.getFullName} makes it; one deleted since is
 * shown by the kept username, as the Author filter's counts show it.
 * {@link FolderListing}'s sort by Author orders by the same name.
 */
@Component
public class DocumentAuthors {

	/** The most ids one query binds */
	private static final int BATCH = 500;

	private final NamedParameterJdbcTemplate jdbc;

	/**
	 * A document's Author
	 *
	 * @param username the username the Author filter takes
	 * @param displayName the name to show
	 */
	public record Author(String username, String displayName) {
	}

	@Autowired
	public DocumentAuthors(DataSource dataSource) {
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static DocumentAuthors get() {
		return Context.get(DocumentAuthors.class);
	}

	/**
	 * @param docIds documents' ids
	 * @return the Author of each that has one, by id
	 */
	public Map<Long, Author> of(Collection<Long> docIds) {
		Map<Long, Author> authors = new HashMap<>();
		List<Long> ids = new ArrayList<>(docIds);
		for (int from = 0; from < ids.size(); from += BATCH) {
			MapSqlParameterSource params = new MapSqlParameterSource()
					.addValue("ids", ids.subList(from, Math.min(from + BATCH, ids.size())))
					.addValue("name", HealdanConstants.ATTR_AUTHOR);
			jdbc.query("""
					select x.ld_docid, x.ld_stringvalue, u.ld_username, u.ld_firstname, u.ld_name
					 from ld_document_ext x left join ld_user u on u.ld_id = x.ld_intvalue and u.ld_deleted = 0
					 where x.ld_name = :name and x.ld_docid in (:ids)""", params, rs -> {
				String kept = StringUtils.defaultIfBlank(rs.getString(2), null);
				String username = rs.getString(3);
				Author author;
				if (username != null)
					author = new Author(kept != null ? kept : username,
							fullName(rs.getString(4), rs.getString(5), username));
				else
					author = kept != null ? new Author(kept, kept) : null;
				if (author != null)
					authors.put(rs.getLong(1), author);
			});
		}
		return authors;
	}

	/** As {@code User.getFullName} */
	private static String fullName(String firstName, String name, String username) {
		if (firstName != null && name != null)
			return firstName + " " + name;
		if (name != null)
			return name;
		return firstName != null ? firstName : username;
	}
}
