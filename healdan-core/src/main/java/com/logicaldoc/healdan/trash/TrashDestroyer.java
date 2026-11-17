// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.profile.LocalValueDAO;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.trash.TrashStore.RemovedItem;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.CollectionUtil;
import com.logicaldoc.util.spring.Context;

/**
 * The nightly job (spec 4.6, 5.4): destroys each member of a Trash item that
 * was removed more than {@code trash.removedDays} before, each on its own
 * removal time, and marks it destroyed. The item's record stays.
 * <p>
 * Items go the earliest removal first, in batches, each once a run and each in
 * a transaction of its own, so its rows and its members' state change together
 * or not at all. One that fails is rolled back, logged and left for the next
 * night, and the rest go on. A member is destroyed only while its row is at 2
 * (CMIS's tree delete records its item before it deletes the rows one by one):
 * any other stays as it is.
 * <ul>
 * <li>Documents go first, shortcuts before what they point to, each through
 * CE's {@code destroyDocument} as {@code _system}, whose read of Destroy
 * Documents (menu -9) the {@code trash-system-destroy} seed grants: the rows,
 * versions, history, notes, links, bookmarks, tickets, the index hit and the
 * files, which storage holds for its holding period, and the document's
 * {@code hd_local_value} rows. A document stays removed while a live shortcut
 * points to it, as CE's destroy would delete that row too.</li>
 * <li>Then the folders, aliases first and then the deepest first, with their
 * rows in CE's folder tables and Healdan's ({@code hd_local_value},
 * {@code hd_searchfolder}), and a default workspace naming them. A folder stays
 * removed while it is a matter's own folder or a Store, while a row that isn't
 * destroyed has it as its folder or parent, while another folder, live or
 * deleted, takes its security from it, or while a live alias points to it.</li>
 * </ul>
 * Only a live shortcut or alias keeps what it points to (plan, Ruling 22): one
 * that isn't live goes with its document or folder, whatever item holds it,
 * and its members there are marked destroyed in the same transaction.
 * <p>
 * Its lines name no folder or file at info or above; CE's destroy logs its
 * own.
 */
@Component
public class TrashDestroyer {

	private static final Logger log = LoggerFactory.getLogger(TrashDestroyer.class);

	/** Items read at a time */
	static final int BATCH = 100;

	/** Ids in an {@code in (...)} list at a time */
	private static final int CHUNK = 1000;

	private final TrashStore store;

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	private final PlatformTransactionManager transactions;

	private final HealdanSettings settings;

	private final CacheEvictor evictor;

	/** Items read at a time; tests make it smaller */
	volatile int batch = BATCH;

	/** Tests: told of each member just before it is destroyed; one that throws fails its item */
	volatile Consumer<TrashMember> beforeDestroy = member -> {
	};

	@Autowired
	public TrashDestroyer(TrashStore store, DataSource dataSource, PlatformTransactionManager transactions,
			HealdanSettings settings, CacheEvictor evictor) {
		this.store = store;
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
		this.transactions = transactions;
		this.settings = settings;
		this.evictor = evictor;
	}

	/**
	 * @return the bean
	 */
	public static TrashDestroyer get() {
		return Context.get(TrashDestroyer.class);
	}

	/**
	 * @param now the run's time
	 * @param days the days removed items are kept
	 * @return what was removed before this is destroyed: whole days of 24 hours
	 */
	static Instant cutoff(Instant now, int days) {
		return now.minus(days, ChronoUnit.DAYS);
	}

	/**
	 * Destroys every member removed more than {@code trashRemovedDays} before
	 * now
	 *
	 * @param now the run's time, which is also the destroyed time it writes
	 * @return how many members it destroyed
	 */
	public int runOnce(Instant now) {
		int days = settings.trashRemovedDays();
		Instant cutoff = cutoff(now, days);
		User system = systemUser();
		int destroyed = 0;
		int items = 0;
		int failed = 0;
		RemovedItem after = null;
		while (true) {
			List<RemovedItem> page = store.removedBefore(cutoff, after, batch);
			for (RemovedItem item : page) {
				try {
					int count = destroy(item.id(), cutoff, now, system);
					destroyed += count;
					if (count > 0)
						items++;
				} catch (RuntimeException e) {
					failed++;
					log.warn("Trash item {} couldn't be destroyed ({}), so it stays removed until the next night",
							item.id(), rootCause(e).getClass().getSimpleName());
					log.debug("Trash item {} couldn't be destroyed", item.id(), e);
				}
			}
			if (page.size() < batch)
				break;
			after = page.get(page.size() - 1);
		}
		log.info("Destroyed {} folders and documents of {} Trash items removed more than {} days before{}", destroyed,
				items, days, failed > 0 ? "; %d items couldn't be destroyed".formatted(failed) : "");
		return destroyed;
	}

	/** The type of what failed is all a warning may say: a message can name a folder or file */
	private static Throwable rootCause(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root)
			root = root.getCause();
		return root;
	}

	private static User systemUser() {
		try {
			User system = UserDAO.get().findById(User.USERID_SYSTEM);
			if (system == null)
				throw new IllegalStateException("LogicalDOC's system user is missing");
			return system;
		} catch (PersistenceException e) {
			throw new IllegalStateException("LogicalDOC's system user can't be read", e);
		}
	}

	/**
	 * Destroys the item's members removed before the cut-off, in one
	 * transaction with their state
	 *
	 * @return how many members it destroyed
	 */
	private int destroy(long item, Instant cutoff, Instant now, User system) {
		Integer count = new TransactionTemplate(transactions).execute(status -> {
			List<TrashMember> members = store.membersRemovedBefore(item, cutoff);
			List<TrashMember> done = new ArrayList<>();
			int along = destroyDocuments(item, members, system, now, done);
			along += destroyFolders(item, members, now, done);
			if (!done.isEmpty())
				store.change(item, done, TrashState.REMOVED, TrashState.DESTROYED, null, null, now);
			return done.size() + along;
		});
		log.debug("Destroyed {} folders and documents of Trash item {}", count, item);
		return count != null ? count : 0;
	}

	/**
	 * The documents at 2, shortcuts first: CE's destroy of a document deletes
	 * the rows of its shortcuts too
	 *
	 * @return how many members of shortcuts that went along were marked
	 *         destroyed
	 */
	private int destroyDocuments(long item, List<TrashMember> members, User system, Instant now,
			List<TrashMember> done) {
		Map<Long, Long> removed = new HashMap<>();
		for (List<Long> chunk : CollectionUtil.partition(ids(members, 'D'), CHUNK))
			named.query("select ld_id, ld_docref from ld_document where ld_deleted = :removed and ld_id in (:ids)",
					new MapSqlParameterSource("removed", PersistentObject.DELETED_CODE_STRONG).addValue("ids", chunk),
					rs -> {
						long id = rs.getLong(1);
						long docRef = rs.getLong(2);
						removed.put(id, rs.wasNull() ? null : docRef);
					});
		List<Long> order = new ArrayList<>();
		for (long id : ids(members, 'D'))
			if (removed.containsKey(id))
				order.add(id);
			else
				log.debug("Left D {} of Trash item {} removed: its row isn't at 2", id, item);
		order.sort(Comparator.comparing((Long id) -> removed.get(id) == null).thenComparing(id -> id));

		List<Long> destroyed = new ArrayList<>();
		int along = 0;
		try {
			for (long id : order) {
				boolean shortcut = removed.get(id) != null;
				if (!shortcut && count("select count(*) from ld_document s where s.ld_docref = ? and s.ld_id <> ?"
						+ " and s.ld_deleted = 0", id, id) > 0) {
					log.debug("Left D {} of Trash item {} removed: a live shortcut points to it", id, item);
					continue;
				}
				TrashMember member = new TrashMember('D', id);
				beforeDestroy.accept(member);
				if (!shortcut) {
					// Its shortcuts that aren't live go with it, each through CE's destroy first, which takes their
					// versions and files too, out of whatever item holds them
					for (long other : jdbc.queryForList("select s.ld_id from ld_document s where s.ld_docref = ?"
							+ " and s.ld_id <> ? and s.ld_deleted <> 0", Long.class, id, id)) {
						destroyed.add(other);
						destroyDocument(other, system);
						along += store.destroyedAlong(new TrashMember('D', other), now);
					}
				}
				destroyed.add(id);
				destroyDocument(id, system);
				done.add(member);
			}
		} catch (PersistenceException | PermissionException e) {
			throw new IllegalStateException("A document of Trash item %d couldn't be destroyed".formatted(item), e);
		} finally {
			evictor.evictDocuments(destroyed);
			evictor.evictDocumentsAfterCompletion(destroyed);
		}
		return along;
	}

	/** CE's destroy, and the document's local values, as a folder's go with it */
	private void destroyDocument(long docId, User system) throws PersistenceException, PermissionException {
		DocumentManager.get().destroyDocument(docId, history(system));
		jdbc.update("delete from hd_local_value where hd_objecttype = ? and hd_objectid = ?",
				String.valueOf(LocalValueDAO.DOCUMENT), docId);
	}

	/** A folder at 2 and where it hangs */
	private record FolderRow(long id, long parentId, Long folderRef) {
	}

	/**
	 * The folders at 2, aliases first (they hold nothing, and a live one keeps
	 * what it points to), then the deepest first
	 *
	 * @return how many members of aliases that went along were marked
	 *         destroyed
	 */
	private int destroyFolders(long item, List<TrashMember> members, Instant now, List<TrashMember> done) {
		Map<Long, FolderRow> removed = new HashMap<>();
		for (List<Long> chunk : CollectionUtil.partition(ids(members, 'F'), CHUNK))
			named.query(
					"select ld_id, ld_parentid, ld_foldref from ld_folder where ld_deleted = :removed and ld_id in (:ids)",
					new MapSqlParameterSource("removed", PersistentObject.DELETED_CODE_STRONG).addValue("ids", chunk),
					rs -> {
						long id = rs.getLong(1);
						long parentId = rs.getLong(2);
						long folderRef = rs.getLong(3);
						removed.put(id, new FolderRow(id, parentId, rs.wasNull() ? null : folderRef));
					});
		List<FolderRow> order = new ArrayList<>();
		for (long id : ids(members, 'F'))
			if (removed.containsKey(id))
				order.add(removed.get(id));
			else
				log.debug("Left F {} of Trash item {} removed: its row isn't at 2", id, item);
		Map<Long, Integer> depths = new HashMap<>();
		order.sort(Comparator.comparing((FolderRow row) -> row.folderRef() == null)
				.thenComparing(row -> -depth(row, removed, depths)).thenComparing(FolderRow::id));

		List<Long> destroyed = new ArrayList<>();
		int along = 0;
		try {
			for (FolderRow folder : order) {
				String kept = keptBecause(folder.id());
				if (kept != null) {
					log.debug("Left F {} of Trash item {} removed: {}", folder.id(), item, kept);
					continue;
				}
				TrashMember member = new TrashMember('F', folder.id());
				beforeDestroy.accept(member);
				// Its aliases that aren't live go with it, out of whatever item holds them
				for (long alias : jdbc.queryForList("select a.ld_id from ld_folder a where a.ld_foldref = ?"
						+ " and a.ld_id <> ? and a.ld_deleted <> 0", Long.class, folder.id(), folder.id())) {
					destroyed.add(alias);
					deleteFolder(alias);
					along += store.destroyedAlong(new TrashMember('F', alias), now);
				}
				destroyed.add(folder.id());
				deleteFolder(folder.id());
				done.add(member);
			}
		} finally {
			evictor.evictFolders(destroyed);
			evictor.evictFoldersAfterCompletion(destroyed);
		}
		return along;
	}

	/**
	 * @return how many of its parents, walked through the folders being
	 *         destroyed, are being destroyed too
	 */
	private static int depth(FolderRow row, Map<Long, FolderRow> removed, Map<Long, Integer> depths) {
		Integer known = depths.get(row.id());
		if (known != null)
			return known;
		int depth = 0;
		FolderRow at = row;
		List<Long> walked = new ArrayList<>(List.of(row.id()));
		while (removed.containsKey(at.parentId()) && !walked.contains(at.parentId())) {
			at = removed.get(at.parentId());
			walked.add(at.id());
			depth++;
		}
		depths.put(row.id(), depth);
		return depth;
	}

	/**
	 * @return why the folder must stay removed, null if nothing keeps it
	 */
	private String keptBecause(long folderId) {
		if (count("select count(*) from ld_folder f where f.ld_id = ? and (f.ld_id = f.ld_parentid"
				+ " or (f.ld_parentid = ? and f.ld_type = ?) or exists (select 1 from hd_matter m"
				+ " where m.hd_folderid = f.ld_id))", folderId, Folder.ROOTID, Folder.TYPE_WORKSPACE) > 0)
			return "it is a matter's own folder or a Store";
		if (count("select count(*) from ld_document where ld_folderid = ?", folderId) > 0
				|| count("select count(*) from ld_folder where ld_parentid = ? and ld_id <> ld_parentid",
						folderId) > 0)
			return "rows that aren't destroyed are in it";
		// Live or deleted: a deleted folder that takes its security from it can be restored (Ruling 23)
		if (count("select count(*) from ld_folder where ld_securityref = ? and ld_id <> ?", folderId, folderId) > 0)
			return "a folder takes its security from it";
		if (count("select count(*) from ld_folder a where a.ld_foldref = ? and a.ld_id <> ? and a.ld_deleted = 0",
				folderId, folderId) > 0)
			return "a live alias points to it";
		return null;
	}

	/**
	 * Deletes the folder's row and the rows that name it, in CE's tables
	 * (ld_folder_ext and ld_foldertag don't cascade) and Healdan's, and takes
	 * it off any user's default workspace
	 */
	private void deleteFolder(long folderId) {
		for (String table : List.of("ld_folder_ext", "ld_foldertag", "ld_folder_acl", "ld_folder_store"))
			jdbc.update("delete from " + table + " where ld_folderid = ?", folderId);
		jdbc.update("delete from ld_bookmark where ld_type = ? and ld_docid = ?", Bookmark.Type.FOLDER.ordinal(),
				folderId);
		jdbc.update("delete from hd_local_value where hd_objecttype = ? and hd_objectid = ?",
				String.valueOf(LocalValueDAO.FOLDER), folderId);
		jdbc.update("delete from hd_searchfolder where hd_folderid = ?", folderId);
		List<Long> users = jdbc.queryForList("select ld_id from ld_user where ld_defworkspace = ?", Long.class,
				folderId);
		if (!users.isEmpty()) {
			jdbc.update("update ld_user set ld_defworkspace = null where ld_defworkspace = ?", folderId);
			evictor.evictUsersAfterCompletion(users);
		}
		jdbc.update("delete from ld_folder where ld_id = ?", folderId);
	}

	private long count(String sql, Object... args) {
		Long count = jdbc.queryForObject(sql, Long.class, args);
		return count != null ? count : 0L;
	}

	/** CE's destroy writes its own details into it, so each document has a fresh one */
	private static FolderHistory history(User system) {
		FolderHistory history = new FolderHistory();
		history.setUser(system);
		return history;
	}

	private static List<Long> ids(List<TrashMember> members, char type) {
		return members.stream().filter(member -> member.type() == type).map(TrashMember::objectId).toList();
	}
}
