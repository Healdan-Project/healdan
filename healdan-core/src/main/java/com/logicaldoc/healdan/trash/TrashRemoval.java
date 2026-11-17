// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.CollectionUtil;
import com.logicaldoc.util.spring.Context;

/**
 * Moves what a delete took from the Trash to Removed (spec 2.3, 4.5), on every
 * route that does: Remove, the Classic Interface's Delete from the Trash, and
 * Empty Trash. Each removal runs in one transaction, so that a row at 2 never
 * has its member still deleted, nor the reverse. A member is taken only while
 * its row is at 1 (CMIS's tree delete records its item before it deletes the
 * rows one by one): a member whose row says otherwise stays as it is. Who
 * removed it and when (the JVM's time) go in the member.
 */
@Component
public class TrashRemoval {

	private static final Logger log = LoggerFactory.getLogger(TrashRemoval.class);

	/** Large id lists are written in chunks */
	private static final int CHUNK = 500;

	private static final String DELETED_FOLDERS = """
			select m.hd_objectid, f.ld_parentid, f.ld_foldref, f.ld_deleted from hd_trash_member m
			  join ld_folder f on f.ld_id = m.hd_objectid
			 where m.hd_trashid = :item and m.hd_objecttype = 'F' and m.hd_state = :state
			""";

	private static final String DELETED_DOCUMENTS = """
			select m.hd_objectid, d.ld_folderid, d.ld_deleted from hd_trash_member m
			  join ld_document d on d.ld_id = m.hd_objectid
			 where m.hd_trashid = :item and m.hd_objecttype = 'D' and m.hd_state = :state
			""";

	private final TrashStore store;

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	private final PlatformTransactionManager transactions;

	@Autowired
	public TrashRemoval(TrashStore store, DataSource dataSource, PlatformTransactionManager transactions) {
		this.store = store;
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
		this.transactions = transactions;
	}

	/**
	 * @return the bean
	 */
	public static TrashRemoval get() {
		return Context.get(TrashRemoval.class);
	}

	/**
	 * The item's deleted members go to Removed, and those whose rows were
	 * destroyed outside the Trash are marked destroyed
	 *
	 * @param trashId the item
	 * @param actor who removes it
	 * @return how many members changed; 0 when none was in the Trash
	 * @throws PersistenceException error at data layer
	 */
	public int removeItem(long trashId, User actor) throws PersistenceException {
		int[] changed = new int[1];
		inTransaction(() -> changed[0] = removeWholeItem(trashId, actor, Instant.now()));
		return changed[0];
	}

	/**
	 * The Classic Interface's members, one by one: a folder member takes the
	 * item's deleted members in its tree, a document member goes alone, and the
	 * top member takes the whole item. What a member takes on its own leaves
	 * its item for an item of its own, so that Removed lists it where an
	 * administrator can restore it. A member no item holds, deleted before
	 * Healdan recorded them, has only its row changed.
	 *
	 * @param type {@code 'F'} for folders, {@code 'D'} for documents
	 * @param ids the members
	 * @param actor who removes them
	 * @throws PersistenceException error at data layer
	 */
	public void removeMembers(char type, Collection<Long> ids, User actor) throws PersistenceException {
		Instant now = Instant.now();
		inTransaction(() -> {
			List<Long> unrecorded = new ArrayList<>();
			for (Long id : new LinkedHashSet<>(ids)) {
				if (id == null)
					continue;
				TrashMember member = new TrashMember(type, id);
				Optional<Long> item = store.itemHolding(member, TrashState.DELETED);
				if (item.isEmpty()) {
					unrecorded.add(id);
					continue;
				}
				if (isTop(item.get(), member))
					removeWholeItem(item.get(), actor, now);
				else if (type == 'F')
					removeTree(item.get(), id, actor, now);
				else
					take(splitOff(item.get(), member, List.of(member)), List.of(member), actor, now);
			}
			if (!unrecorded.isEmpty())
				setRows(type, unrecorded);
		});
	}

	/**
	 * Every item of the user's in the Trash goes to Removed
	 *
	 * @param user whose Trash it is
	 * @return how many items were emptied
	 * @throws PersistenceException error at data layer
	 */
	public int empty(User user) throws PersistenceException {
		Instant now = Instant.now();
		int[] count = new int[1];
		inTransaction(() -> {
			List<Long> items = store.itemsDeletedBy(user.getId(), TrashState.DELETED);
			for (long item : items)
				removeWholeItem(item, user, now);
			count[0] = items.size();
			emptyUnrecorded(user);
		});
		return count[0];
	}

	/**
	 * What deleted rows no item holds, from before Healdan recorded them, are
	 * what CE's own empty took: the user's rows at 1
	 */
	private void emptyUnrecorded(User user) {
		for (String table : List.of("ld_document", "ld_folder")) {
			char type = table.equals("ld_folder") ? 'F' : 'D';
			List<Long> ids = jdbc.queryForList("select ld_id from " + table
					+ " where ld_deleted = 1 and ld_deleteuserid = ? and not exists (select 1 from hd_trash_member m"
					+ " where m.hd_objecttype = ? and m.hd_objectid = ld_id and m.hd_state = ?)", Long.class,
					user.getId(), String.valueOf(type), TrashState.DELETED.code());
			if (!ids.isEmpty())
				setRows(type, ids);
		}
	}

	private boolean isTop(long item, TrashMember member) {
		Optional<TrashItemRow> row = store.item(item);
		return row.isPresent() && row.get().objectId() == member.objectId()
				&& row.get().kind().equals(member.type() == 'F' ? TrashRecorder.FOLDER : TrashRecorder.DOCUMENT);
	}

	private int removeWholeItem(long item, User actor, Instant now) {
		int gone = store.destroyedIfGone(item, TrashState.DELETED, now);
		return gone + take(item, store.members(item, TrashState.DELETED), actor, now);
	}

	/**
	 * What a member takes on its own goes to an item of its own
	 * ({@link TrashRecorder#splitOff})
	 *
	 * @return the new item
	 */
	private static long splitOff(long item, TrashMember top, List<TrashMember> members) {
		try {
			return TrashRecorder.get().splitOff(item, top, members);
		} catch (PersistenceException e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	/**
	 * A folder member takes the item's deleted members in its tree: the folders
	 * below it, their documents, and the aliases pointing into it
	 */
	private void removeTree(long item, long folderId, User actor, Instant now) {
		MapSqlParameterSource args = new MapSqlParameterSource("item", item).addValue("state",
				TrashState.DELETED.code());
		Map<Long, List<Long>> children = new HashMap<>();
		Map<Long, Long> aliases = new HashMap<>();
		List<Long> members = new ArrayList<>();
		named.query(DELETED_FOLDERS, args, rs -> {
			long id = rs.getLong(1);
			members.add(id);
			children.computeIfAbsent(rs.getLong(2), k -> new ArrayList<>()).add(id);
			long ref = rs.getLong(3);
			if (!rs.wasNull())
				aliases.put(id, ref);
		});
		Set<Long> tree = new HashSet<>(List.of(folderId));
		List<Long> queue = new ArrayList<>(List.of(folderId));
		for (int i = 0; i < queue.size(); i++)
			for (long child : children.getOrDefault(queue.get(i), List.of()))
				if (tree.add(child))
					queue.add(child);

		List<TrashMember> taken = new ArrayList<>();
		for (long id : members)
			if (tree.contains(id) || aliases.containsKey(id) && tree.contains(aliases.get(id)))
				taken.add(new TrashMember('F', id));
		named.query(DELETED_DOCUMENTS, args, rs -> {
			if (tree.contains(rs.getLong(2)))
				taken.add(new TrashMember('D', rs.getLong(1)));
		});
		take(splitOff(item, new TrashMember('F', folderId), taken), taken, actor, now);
	}

	/**
	 * Sets the members' rows from 1 to 2 and marks them removed, only those
	 * whose row was at 1
	 *
	 * @return how many members were removed
	 */
	private int take(long item, Collection<TrashMember> members, User actor, Instant now) {
		List<Long> wantedFolders = new ArrayList<>();
		List<Long> wantedDocuments = new ArrayList<>();
		for (TrashMember member : members)
			(member.type() == 'F' ? wantedFolders : wantedDocuments).add(member.objectId());
		Set<Long> folders = new HashSet<>(setRows('F', wantedFolders));
		Set<Long> documents = new HashSet<>(setRows('D', wantedDocuments));
		List<TrashMember> done = new ArrayList<>();
		for (TrashMember member : members) {
			if ((member.type() == 'F' ? folders : documents).contains(member.objectId()))
				done.add(member);
			else
				log.debug("Left {} {} of Trash item {} deleted: its row isn't at 1", member.type(),
						member.objectId(), item);
		}
		if (done.isEmpty())
			return 0;
		int changed = store.change(item, done, TrashState.DELETED, TrashState.REMOVED,
				actor != null ? (Long) actor.getId() : null, actor != null ? actor.getFullName() : null, now);
		log.debug("Removed {} folders and {} documents of Trash item {}", folders.size(), documents.size(), item);
		return changed;
	}

	/**
	 * Sets the rows at 1 to 2, and evicts them from the second-level cache now
	 * and when the transaction ends. The rows that changed are read again after
	 * the update, as a restore that commits between the read and the update
	 * makes the update skip its row.
	 *
	 * @return the ids of the rows that went from 1 to 2
	 */
	private List<Long> setRows(char type, Collection<Long> ids) {
		List<Long> changed = new ArrayList<>();
		String table = type == 'F' ? "ld_folder" : "ld_document";
		CacheEvictor evictor = CacheEvictor.get();
		for (List<Long> chunk : CollectionUtil.partition(new ArrayList<>(ids), CHUNK)) {
			MapSqlParameterSource args = new MapSqlParameterSource("weak", PersistentObject.DELETED_CODE_DEFAULT)
					.addValue("ids", chunk);
			List<Long> inTrash = named.queryForList(
					"select ld_id from " + table + " where ld_deleted = :weak and ld_id in (:ids)", args, Long.class);
			if (inTrash.isEmpty())
				continue;
			named.update("update " + table + " set ld_deleted = :strong where ld_deleted = :weak and ld_id in (:ids)",
					args.addValue("strong", PersistentObject.DELETED_CODE_STRONG));
			// Those at 1 before that are at 2 now: the update's own rows
			inTrash = named.queryForList("select ld_id from " + table + " where ld_deleted = :strong and ld_id in (:ids)",
					new MapSqlParameterSource("strong", PersistentObject.DELETED_CODE_STRONG).addValue("ids", inTrash),
					Long.class);
			changed.addAll(inTrash);
			if (type == 'F') {
				evictor.evictFolders(inTrash);
				evictor.evictFoldersAfterCompletion(inTrash);
			} else {
				evictor.evictDocuments(inTrash);
				evictor.evictDocumentsAfterCompletion(inTrash);
			}
		}
		return changed;
	}

	private void inTransaction(Runnable work) throws PersistenceException {
		try {
			new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
		} catch (RuntimeException e) {
			if (e.getCause() instanceof PersistenceException pe)
				throw pe;
			throw new PersistenceException(e.getMessage(), e);
		}
	}
}
