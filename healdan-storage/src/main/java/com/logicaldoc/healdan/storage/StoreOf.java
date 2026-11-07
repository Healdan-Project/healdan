// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.util.spring.Context;

/**
 * A document's Store (call 10): the top-level workspace above its folder, the Templates Store included and CE's
 * Default workspace not. It is found by walking {@code ld_parentid} up to the root, with JDBC, and never from
 * {@code ld_path}: CE's folder restore re-parents a tree with JDBC and leaves its paths as they were, so a path can
 * name the wrong Store for good.
 * <p>
 * The walk reads through the caller's transaction, so it sees what the transaction has written: CE's DAOs flush on
 * every store ({@code HibernatePersistentObjectDAO.saveOrUpdate}), so a document or folder made earlier in the same
 * transaction, as {@code HibernateFolderDAO.copy} makes them, is there.
 */
@Component
public class StoreOf {

	private static final Logger log = LoggerFactory.getLogger(StoreOf.class);

	/** Deeper than any real tree; a walk this long is a loop in {@code ld_parentid} */
	private static final int MAX_DEPTH = 1000;

	private final JdbcTemplate jdbc;

	@Autowired
	public StoreOf(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static StoreOf get() {
		return Context.get(StoreOf.class);
	}

	/**
	 * @param docId a document; an alias stands for its target, whose files are the alias's
	 * @return the Store's folder id; empty if the document has no row or folder yet, or its folder is in no Store
	 */
	public Optional<Long> of(long docId) {
		List<Long[]> rows = jdbc.query("select ld_folderid, ld_docref from ld_document where ld_id = ?", (rs, i) -> {
			long folder = rs.getLong(1);
			Long folderId = rs.wasNull() ? null : folder;
			long ref = rs.getLong(2);
			return new Long[] { folderId, rs.wasNull() ? null : ref };
		}, docId);
		if (rows.isEmpty())
			return Optional.empty();
		Long[] row = rows.get(0);
		if (row[1] != null && row[1].longValue() != docId)
			return ofTarget(row[1]);
		return row[0] == null ? Optional.empty() : ofFolder(row[0]);
	}

	/** The target of an alias, which can't be an alias itself */
	private Optional<Long> ofTarget(long targetId) {
		List<Long> folders = jdbc.queryForList("select ld_folderid from ld_document where ld_id = ?", Long.class,
				targetId);
		return folders.isEmpty() || folders.get(0) == null ? Optional.empty() : ofFolder(folders.get(0));
	}

	/**
	 * @param folderId a folder
	 * @return the Store above it, or itself if it is one; empty for the root, CE's Default workspace and anything
	 *         below it, a top-level folder that isn't a workspace, and a folder that doesn't exist
	 */
	public Optional<Long> ofFolder(long folderId) {
		long current = folderId;
		for (int depth = 0; depth < MAX_DEPTH; depth++) {
			List<long[]> rows = jdbc.query("select ld_parentid, ld_type from ld_folder where ld_id = ?",
					(rs, i) -> new long[] { rs.getLong(1), rs.getInt(2) }, current);
			if (rows.isEmpty())
				return Optional.empty();
			long parent = rows.get(0)[0];
			if (parent == current)
				return Optional.empty(); // a root, reached without passing a Store
			if (parent == Folder.ROOTID)
				return store(current, (int) rows.get(0)[1]);
			current = parent;
		}
		log.warn("The parents of folder {} loop, so it is in no Store", folderId);
		return Optional.empty();
	}

	/** @return the top-level folder as a Store, if it is one */
	private static Optional<Long> store(long top, int type) {
		return top != Folder.DEFAULTWORKSPACEID && type == Folder.TYPE_WORKSPACE ? Optional.of(top)
				: Optional.empty();
	}

	/**
	 * Reads every folder's parent once, for bulk work (the recording seed, the nightly check, a Store's check before a
	 * switch), which then finds each folder's Store in memory.
	 *
	 * @return the folders as they are now
	 */
	public Folders folders() {
		Map<Long, long[]> folders = new HashMap<>();
		jdbc.query("select ld_id, ld_parentid, ld_type from ld_folder",
				rs -> {
					folders.put(rs.getLong(1), new long[] { rs.getLong(2), rs.getInt(3) });
				});
		return new Folders(folders);
	}

	/**
	 * The folder tree as {@link StoreOf#folders()} read it. Not for use by several threads at once.
	 */
	public static final class Folders {

		/** A folder's id to its parent's and its type */
		private final Map<Long, long[]> folders;

		/** What each folder resolved to; -1 for no Store */
		private final Map<Long, Long> resolved = new HashMap<>();

		private Folders(Map<Long, long[]> folders) {
			this.folders = folders;
		}

		/**
		 * @param folderId a folder
		 * @return its Store, as {@link StoreOf#ofFolder} answers
		 */
		public Optional<Long> of(long folderId) {
			Long known = resolved.get(folderId);
			if (known == null) {
				known = walk(folderId);
				resolved.put(folderId, known);
			}
			return known < 0 ? Optional.empty() : Optional.of(known);
		}

		private long walk(long folderId) {
			Set<Long> seen = new HashSet<>();
			long current = folderId;
			while (seen.add(current)) {
				Long known = current != folderId ? resolved.get(current) : null;
				if (known != null)
					return known;
				long[] row = folders.get(current);
				if (row == null)
					return -1;
				long parent = row[0];
				if (parent == current)
					return -1;
				if (parent == Folder.ROOTID)
					return store(current, (int) row[1]).orElse(-1L);
				current = parent;
			}
			log.warn("The parents of folder {} loop, so it is in no Store", folderId);
			return -1;
		}
	}
}
