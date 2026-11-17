// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.util.CollectionUtil;
import com.logicaldoc.util.spring.Context;

/**
 * What stops a folder's delete whoever asks, admins included (spec 4.2): a
 * live document in its live tree that is checked out, by anyone, or
 * immutable. Healdan's folder-delete hook asks after the permissions, so the
 * refusal tells nothing to someone who may not delete everything inside, and
 * before the Trash's record, so a refused delete writes nothing. CE's
 * {@code deleteTree} stopped silently at an immutable document, after writing
 * the folder's history, and its routes reported a success or a failure with
 * no reason. Neither refusal names anything inside.
 */
@Component
public class TrashRules {

	static final String CHECKED_OUT = "Documents in this folder are checked out. It can be deleted once they're checked in.";

	static final String IMMUTABLE = "This folder holds an immutable document, which can't be deleted.";

	/** The folders go in lists of at most 1000 */
	private static final int CHUNK = 1000;

	private final StoredRows rows;

	private final NamedParameterJdbcTemplate named;

	@Autowired
	public TrashRules(StoredRows rows, DataSource dataSource) {
		this.rows = rows;
		this.named = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static TrashRules get() {
		return Context.get(TrashRules.class);
	}

	/**
	 * @param folderId a folder
	 * @return every live document in the folder's live tree that is checked
	 *         out, by anyone
	 */
	public List<Long> checkedOutIn(long folderId) {
		return checkedOutIn(rows.tree(folderId));
	}

	/**
	 * @param folderId a folder
	 * @return true if a live document in the folder's live tree is immutable,
	 *         the documents CE's {@code deleteTree} stopped at
	 */
	public boolean holdsImmutable(long folderId) {
		return holdsImmutable(rows.tree(folderId));
	}

	/**
	 * Refuses a delete of the folder while a document in it is checked out or
	 * immutable, whoever asks
	 *
	 * @param folderId the folder to delete
	 * @throws PersistenceException the refusal, a {@link HealdanRuleException}
	 *         with {@link #CHECKED_OUT} or {@link #IMMUTABLE}
	 */
	public void checkDeletable(long folderId) throws PersistenceException {
		List<Long> tree = rows.tree(folderId);
		if (!checkedOutIn(tree).isEmpty())
			throw new HealdanRuleException(CHECKED_OUT);
		if (holdsImmutable(tree))
			throw new HealdanRuleException(IMMUTABLE);
	}

	private List<Long> checkedOutIn(List<Long> tree) {
		List<Long> ids = new ArrayList<>();
		for (List<Long> folders : chunks(tree))
			ids.addAll(named.queryForList("""
					select ld_id from ld_document
					 where ld_deleted = 0 and ld_status = :status and ld_folderid in (:folders)
					 order by ld_id
					""", new MapSqlParameterSource("folders", folders).addValue("status",
					DocumentStatus.CHECKEDOUT.ordinal()), Long.class));
		return ids;
	}

	private boolean holdsImmutable(List<Long> tree) {
		for (List<Long> folders : chunks(tree)) {
			Long count = named.queryForObject("""
					select count(*) from ld_document
					 where ld_deleted = 0 and ld_immutable = 1 and ld_folderid in (:folders)
					""", new MapSqlParameterSource("folders", folders), Long.class);
			if (count != null && count > 0)
				return true;
		}
		return false;
	}

	private static Collection<List<Long>> chunks(List<Long> tree) {
		return CollectionUtil.partition(tree, CHUNK);
	}
}
