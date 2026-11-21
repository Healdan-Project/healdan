// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
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
import com.logicaldoc.healdan.listing.DocumentAuthors.Author;
import com.logicaldoc.util.spring.Context;

/**
 * The Class, Subclass, Typist and email fields of documents, read for a whole
 * page at once, as {@link DocumentAuthors} reads their Authors, so a list
 * shows them without a query per row (list columns spec section 5.2).
 * <p>
 * Each is the document's own property. The Typist is a user field, named as
 * {@link DocumentAuthors} names an Author; Sent is a date; the rest are text,
 * as stored. A blank value is none.
 */
@Component
public class DocumentExtras {

	/** The most ids one query binds */
	private static final int BATCH = 500;

	/** The properties read */
	private static final List<String> NAMES = List.of(HealdanConstants.ATTR_DOCCLASS,
			HealdanConstants.ATTR_DOCSUBCLASS, HealdanConstants.ATTR_TYPIST, HealdanConstants.ATTR_EMAILFROM,
			HealdanConstants.ATTR_EMAILTO, HealdanConstants.ATTR_EMAILSENT);

	private final NamedParameterJdbcTemplate jdbc;

	/**
	 * A document's Class, Subclass, Typist and email fields, each null when it
	 * has none
	 *
	 * @param docClass the Class
	 * @param docSubclass the Subclass
	 * @param typist the Typist, named as an Author is
	 * @param emailFrom an email's From
	 * @param emailTo an email's To
	 * @param emailSent when an email was sent
	 */
	public record Extras(String docClass, String docSubclass, Author typist, String emailFrom, String emailTo,
			Date emailSent) {
	}

	@Autowired
	public DocumentExtras(DataSource dataSource) {
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static DocumentExtras get() {
		return Context.get(DocumentExtras.class);
	}

	/**
	 * @param docIds documents' ids
	 * @return the fields of each that has any, by id
	 */
	public Map<Long, Extras> of(Collection<Long> docIds) {
		Map<Long, Map<String, Object>> values = new HashMap<>();
		List<Long> ids = new ArrayList<>(docIds);
		for (int from = 0; from < ids.size(); from += BATCH) {
			MapSqlParameterSource params = new MapSqlParameterSource()
					.addValue("ids", ids.subList(from, Math.min(from + BATCH, ids.size()))).addValue("names", NAMES)
					.addValue("typist", HealdanConstants.ATTR_TYPIST);
			jdbc.query("""
					select x.ld_docid, x.ld_name, x.ld_stringvalue, x.ld_datevalue, u.ld_username, u.ld_firstname, u.ld_name
					 from ld_document_ext x left join ld_user u
					  on x.ld_name = :typist and u.ld_id = x.ld_intvalue and u.ld_deleted = 0
					 where x.ld_name in (:names) and x.ld_docid in (:ids)""", params, rs -> {
				String name = rs.getString(2);
				Object value;
				if (HealdanConstants.ATTR_TYPIST.equals(name)) {
					value = DocumentAuthors.named(rs.getString(3), rs.getString(5), rs.getString(6), rs.getString(7));
				} else if (HealdanConstants.ATTR_EMAILSENT.equals(name)) {
					Timestamp sent = rs.getTimestamp(4);
					value = sent == null ? null : new Date(sent.getTime());
				} else {
					value = StringUtils.defaultIfBlank(rs.getString(3), null);
				}
				if (value != null)
					values.computeIfAbsent(rs.getLong(1), id -> new HashMap<>()).put(name, value);
			});
		}

		Map<Long, Extras> extras = new HashMap<>();
		values.forEach((id, found) -> extras.put(id,
				new Extras((String) found.get(HealdanConstants.ATTR_DOCCLASS),
						(String) found.get(HealdanConstants.ATTR_DOCSUBCLASS),
						(Author) found.get(HealdanConstants.ATTR_TYPIST),
						(String) found.get(HealdanConstants.ATTR_EMAILFROM),
						(String) found.get(HealdanConstants.ATTR_EMAILTO),
						(Date) found.get(HealdanConstants.ATTR_EMAILSENT))));
		return extras;
	}
}
