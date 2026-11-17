// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.seed.SeedStep;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.CollectionUtil;
import com.logicaldoc.util.spring.Context;

/**
 * Makes Trash items of what was deleted before this release (spec 4.7), so the
 * Trash, restores, removal and the nightly job see it. Four passes, each item
 * in one transaction with the change of its rows' state:
 * <ol>
 * <li>each top-most folder at 1 with a live parent, with the rows at 1 in its
 * tree: the item is in the Trash;</li>
 * <li>each document at 1 in a live folder, with its shortcuts at 1;</li>
 * <li>each top-most folder at 2 whose parent isn't at 2, with the rows at 1
 * and 2 in its tree, those at 1 going to 2 (what CE's folder removal left in
 * the Trash): the item is removed;</li>
 * <li>each document at 2 whose folder isn't at 2, with its shortcuts,
 * likewise removed.</li>
 * </ol>
 * The folder aliases outside a tree that point into it join its item, as CE's
 * delete set them to the folder's state; a shortcut joins its document's. Left
 * over after the passes, an alias or a shortcut becomes an item of its own, as
 * a delete of it alone does. Rows already in an item are skipped. The removed
 * time is the run's, and nobody is recorded as having removed them.
 */
@Component
public class TrashExistingSeed implements SeedStep {

	private static final Logger log = LoggerFactory.getLogger(TrashExistingSeed.class);

	private static final String NAME = "trash-record-existing";

	/** Lists in {@code in (...)} and the tops read at a time */
	private static final int CHUNK = 1000;

	/** Rows in no item, deleted or removed: format with the member type and the table's alias */
	private static final String UNRECORDED = " and not exists (select 1 from hd_trash_member m where m.hd_objecttype = '%s'"
			+ " and m.hd_objectid = %s.ld_id and m.hd_state in ('deleted', 'removed'))";

	private static final String FOLDER_COLUMNS = "f.ld_id, f.ld_tenantid, f.ld_parentid, f.ld_name, f.ld_deleteuserid,"
			+ " f.ld_deleteuser, f.ld_lastmodified, f.ld_deleted";

	private static final String DOCUMENT_COLUMNS = "d.ld_id, d.ld_tenantid, d.ld_folderid, d.ld_filename,"
			+ " d.ld_deleteuserid, d.ld_deleteuser, d.ld_lastmodified, d.ld_deleted, d.ld_customid, d.ld_docref";

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	private final TrashStore store;

	private final CacheEvictor evictor;

	/** What a run has made, for its log line */
	private int inTrash;

	private int removed;

	@Autowired
	public TrashExistingSeed(DataSource dataSource, TrashStore store, CacheEvictor evictor) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
		this.store = store;
		this.evictor = evictor;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public void run() {
		Instant now = Instant.now();
		inTrash = 0;
		removed = 0;
		TransactionTemplate transaction = new TransactionTemplate(Context.get(PlatformTransactionManager.class));

		topFolders(1, transaction, now);
		topDocuments(1, transaction, now);
		topFolders(2, transaction, now);
		topDocuments(2, transaction, now);
		leftovers(transaction, now);

		log.info("Trash items made for what was already deleted: {} in the Trash, {} removed", inTrash, removed);
	}

	/**
	 * A folder or document that is the top of a candidate item; for a document,
	 * {@code parentId} is its folder
	 */
	private record Row(long id, long tenantId, Long parentId, String name, Long deletedById, String deletedBy,
			Instant modified, int deleted, String customId, Long docRef) {
	}

	private static Row folderRow(ResultSet rs, int n) throws SQLException {
		return new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), nullable(rs, 5), rs.getString(6),
				rs.getTimestamp(7).toInstant(), rs.getInt(8), null, null);
	}

	private static Row documentRow(ResultSet rs, int n) throws SQLException {
		return new Row(rs.getLong(1), rs.getLong(2), nullable(rs, 3), rs.getString(4), nullable(rs, 5),
				rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getInt(8), rs.getString(9), nullable(rs, 10));
	}

	private static Long nullable(ResultSet rs, int column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	/**
	 * Passes 1 and 3: the top-most folders at the state, which aren't aliases
	 * (those join the item of the folder they point to)
	 */
	private void topFolders(int state, TransactionTemplate transaction, Instant now) {
		String parent = state == 1
				? " and exists (select 1 from ld_folder p where p.ld_id = f.ld_parentid and p.ld_deleted = 0)"
				: " and not exists (select 1 from ld_folder p where p.ld_id = f.ld_parentid and p.ld_deleted = 2)";
		long after = 0;
		while (true) {
			List<Row> tops = jdbc.query("select " + FOLDER_COLUMNS + " from ld_folder f where f.ld_deleted = ?"
					+ " and f.ld_foldref is null and f.ld_id <> f.ld_parentid and f.ld_id > ?" + parent
					+ UNRECORDED.formatted("F", "f") + " order by f.ld_id limit " + CHUNK,
					TrashExistingSeed::folderRow, state, after);
			if (tops.isEmpty())
				return;
			for (Row top : tops) {
				folderItem(top, state == 2, transaction, now);
				after = top.id();
			}
		}
	}

	/**
	 * Passes 2 and 4: the documents at the state that aren't shortcuts, in a
	 * folder that isn't deleted (pass 2) or isn't at 2 (pass 4)
	 */
	private void topDocuments(int state, TransactionTemplate transaction, Instant now) {
		String folder = state == 1
				? " and not exists (select 1 from ld_folder f where f.ld_id = d.ld_folderid and f.ld_deleted <> 0)"
				: " and not exists (select 1 from ld_folder f where f.ld_id = d.ld_folderid and f.ld_deleted = 2)";
		long after = 0;
		while (true) {
			List<Row> tops = jdbc.query("select " + DOCUMENT_COLUMNS + " from ld_document d where d.ld_deleted = ?"
					+ " and d.ld_docref is null and d.ld_id > ?" + folder + UNRECORDED.formatted("D", "d")
					+ " order by d.ld_id limit " + CHUNK, TrashExistingSeed::documentRow, state, after);
			if (tops.isEmpty())
				return;
			for (Row top : tops) {
				documentItem(top, state == 2, transaction, now);
				after = top.id();
			}
		}
	}

	/**
	 * What is left: an alias or a shortcut in the Trash or removed that no
	 * item took, which is an item of its own
	 */
	private void leftovers(TransactionTemplate transaction, Instant now) {
		long after = 0;
		while (true) {
			List<Row> aliases = jdbc.query("select " + FOLDER_COLUMNS
					+ " from ld_folder f where f.ld_deleted in (1, 2) and f.ld_foldref is not null"
					+ " and f.ld_id <> f.ld_parentid and f.ld_id > ?" + UNRECORDED.formatted("F", "f")
					+ " order by f.ld_id limit " + CHUNK, TrashExistingSeed::folderRow, after);
			if (aliases.isEmpty())
				break;
			for (Row alias : aliases) {
				folderItem(alias, alias.deleted() == 2, transaction, now);
				after = alias.id();
			}
		}
		after = 0;
		while (true) {
			List<Row> shortcuts = jdbc.query("select " + DOCUMENT_COLUMNS
					+ " from ld_document d where d.ld_deleted in (1, 2) and d.ld_docref is not null and d.ld_id > ?"
					+ UNRECORDED.formatted("D", "d") + " order by d.ld_id limit " + CHUNK,
					TrashExistingSeed::documentRow, after);
			if (shortcuts.isEmpty())
				return;
			for (Row shortcut : shortcuts) {
				documentItem(shortcut, shortcut.deleted() == 2, transaction, now);
				after = shortcut.id();
			}
		}
	}

	/**
	 * One folder's item: the folder, the folders and documents in its tree at
	 * the item's states, the aliases that point into the tree, and the
	 * shortcuts of the documents
	 */
	private void folderItem(Row top, boolean remove, TransactionTemplate transaction, Instant now) {
		List<Integer> states = remove ? List.of(1, 2) : List.of(1);
		List<Long> tree = tree(top.id(), states);
		List<Long> documents = rows("ld_document", "ld_folderid", tree, states, "D");
		List<Long> shortcuts = rows("ld_document", "ld_docref", documents, states, "D");
		shortcuts.removeAll(new HashSet<>(documents));
		List<Long> aliases = rows("ld_folder", "ld_foldref", tree, states, "F");
		aliases.removeAll(new HashSet<>(tree));

		Set<TrashMember> members = new LinkedHashSet<>();
		tree.forEach(id -> members.add(new TrashMember('F', id)));
		aliases.forEach(id -> members.add(new TrashMember('F', id)));
		documents.forEach(id -> members.add(new TrashMember('D', id)));
		shortcuts.forEach(id -> members.add(new TrashMember('D', id)));

		Folder parent = findFolder(top.parentId());
		TrashItemRow item = new TrashItemRow(0L, top.tenantId(), TrashRecorder.FOLDER, top.id(), name(top.name()),
				null, top.parentId(), path(parent), matterOf(top.id(), parent), tree.size() - 1, documents.size(),
				top.deletedById(), top.deletedBy(), top.modified());
		record(item, members, remove, transaction, now);
	}

	/**
	 * One document's item: the document and its shortcuts at the item's states
	 */
	private void documentItem(Row top, boolean remove, TransactionTemplate transaction, Instant now) {
		boolean shortcut = top.docRef() != null;
		Set<TrashMember> members = new LinkedHashSet<>(List.of(new TrashMember('D', top.id())));
		if (!shortcut)
			rows("ld_document", "ld_docref", List.of(top.id()), remove ? List.of(1, 2) : List.of(1), "D")
					.forEach(id -> members.add(new TrashMember('D', id)));

		Folder folder = findFolder(top.parentId());
		TrashItemRow item = new TrashItemRow(0L, top.tenantId(), TrashRecorder.DOCUMENT, top.id(), name(top.name()),
				shortcut ? null : TrashRecorder.docNumber(top.customId()), top.parentId(), path(folder),
				matterOfDocument(folder), 0, 0, top.deletedById(), top.deletedBy(), top.modified());
		record(item, members, remove, transaction, now);
	}

	/**
	 * Writes the item and, for a removed one, sets its rows at 1 to 2 and its
	 * members to removed, in one transaction
	 */
	private void record(TrashItemRow item, Set<TrashMember> members, boolean remove, TransactionTemplate transaction,
			Instant now) {
		List<Long> folders = new ArrayList<>();
		List<Long> documents = new ArrayList<>();
		for (TrashMember member : members)
			(member.type() == 'F' ? folders : documents).add(member.objectId());

		List<Long> changedFolders = new ArrayList<>();
		List<Long> changedDocuments = new ArrayList<>();
		long id = transaction.execute(status -> {
			long made = store.insert(item, members);
			if (remove) {
				changedFolders.addAll(setRemoved("ld_folder", folders));
				changedDocuments.addAll(setRemoved("ld_document", documents));
				store.change(made, members, TrashState.DELETED, TrashState.REMOVED, null, null, now);
			}
			return made;
		});
		// Hibernate's second-level cache still holds the rows at 1; the transaction has committed
		evictor.evictFolders(changedFolders);
		evictor.evictDocuments(changedDocuments);
		if (remove)
			removed++;
		else
			inTrash++;
		log.debug("Made Trash item {} of {} {}", id, item.kind(), item.objectId());
	}

	/**
	 * @return the ids of the rows that went from 1 to 2
	 */
	private List<Long> setRemoved(String table, List<Long> ids) {
		List<Long> changed = new ArrayList<>();
		for (List<Long> chunk : CollectionUtil.partition(ids, CHUNK)) {
			MapSqlParameterSource args = new MapSqlParameterSource("ids", chunk);
			changed.addAll(named.queryForList("select ld_id from " + table + " where ld_deleted = 1 and ld_id in (:ids)",
					args, Long.class));
			named.update("update " + table + " set ld_deleted = 2 where ld_deleted = 1 and ld_id in (:ids)", args);
		}
		return changed;
	}

	/**
	 * @return the folder and the folders below it, top-down, that are at the
	 *         states and in no item, through the parent each names
	 */
	private List<Long> tree(long topId, List<Integer> states) {
		List<Long> all = new ArrayList<>(List.of(topId));
		Set<Long> seen = new HashSet<>(all);
		List<Long> level = List.of(topId);
		while (!level.isEmpty()) {
			List<Long> next = new ArrayList<>();
			for (List<Long> chunk : CollectionUtil.partition(level, CHUNK))
				for (Long child : named.queryForList(
						"select f.ld_id from ld_folder f where f.ld_parentid in (:ids) and f.ld_id <> f.ld_parentid"
								+ " and f.ld_deleted in (:states)" + UNRECORDED.formatted("F", "f"),
						new MapSqlParameterSource("ids", chunk).addValue("states", states), Long.class))
					if (seen.add(child)) {
						all.add(child);
						next.add(child);
					}
			level = next;
		}
		return all;
	}

	/**
	 * @return the ids of the rows of the table whose column holds one of the
	 *         ids, at the states and in no item
	 */
	private List<Long> rows(String table, String column, List<Long> ids, List<Integer> states, String type) {
		List<Long> found = new ArrayList<>();
		for (List<Long> chunk : CollectionUtil.partition(ids, CHUNK))
			found.addAll(named.queryForList(
					"select t.ld_id from " + table + " t where t." + column + " in (:ids) and t.ld_deleted in (:states)"
							+ UNRECORDED.formatted(type, "t") + " order by t.ld_id",
					new MapSqlParameterSource("ids", chunk).addValue("states", states), Long.class));
		return found;
	}

	private static Folder findFolder(Long id) {
		try {
			return id != null ? FolderDAO.get().findById(id) : null;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Couldn't read the folder an item was deleted from", e);
		}
	}

	private static List<String> path(Folder from) {
		try {
			return TrashRecorder.path(from);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Couldn't work out the path an item was deleted from", e);
		}
	}

	/** A deleted folder's matter: the one it is in, or is */
	private static Long matterOf(long folderId, Folder parent) {
		try {
			return parent != null ? TrashRecorder.matterOf(folderId, parent) : null;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Couldn't find the matter of a deleted folder", e);
		}
	}

	/** A deleted document's matter: the one its folder is in */
	private static Long matterOfDocument(Folder folder) {
		try {
			Matter matter = folder != null ? MatterLocator.get().locate(folder).matter() : null;
			return matter != null ? matter.getId() : null;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Couldn't find the matter of a deleted document", e);
		}
	}

	/** {@code hd_name} can't be null, and a name's row may be */
	private static String name(String name) {
		return name != null ? name : "";
	}
}
