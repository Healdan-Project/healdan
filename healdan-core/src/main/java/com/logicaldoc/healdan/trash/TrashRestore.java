// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.CollectionUtil;
import com.logicaldoc.util.spring.Context;

/**
 * Restores what a delete took, and only that (spec 4.4). A folder's restore,
 * on every route, brings back the members of the folder's Trash item that are
 * in its tree, and the aliases among them that point into it: what others
 * deleted inside earlier stays in their Trash, and what was removed since stays
 * removed. A member is taken only while its row shows the state the member
 * says (1 for deleted, 2 for removed): CMIS's tree delete records its item
 * before it deletes the rows one by one. Whatever comes back is marked
 * restored, so that a later delete of it is a new item, and its documents are
 * queued for indexing, as CE's daily optimiser takes the hits of documents in
 * the trash.
 */
@Component
public class TrashRestore {

	private static final Logger log = LoggerFactory.getLogger(TrashRestore.class);

	static final String NOT_IN_TRASH = "This item is no longer in the Trash.";

	static final String PLACE_GONE = "The folder it was deleted from is gone, or you can't restore into it. Choose another.";

	/**
	 * A restore whose record can't be written is refused, never left live
	 * while the Trash still shows it
	 */
	static final String NOT_RECORDED = "The restore couldn't be recorded in the Trash.";

	/** Large id lists are written in chunks */
	private static final int CHUNK = 500;

	/** Members of an item, with where their rows stand */
	private static final String MEMBER_FOLDERS = """
			select m.hd_objectid, m.hd_state, f.ld_deleted, f.ld_foldref from hd_trash_member m
			  join ld_folder f on f.ld_id = m.hd_objectid
			 where m.hd_trashid = :item and m.hd_objecttype = 'F' and m.hd_state in (:states)
			 order by m.hd_objectid
			""";

	private static final String MEMBER_DOCUMENTS = """
			select m.hd_objectid, m.hd_state, d.ld_deleted, d.ld_folderid, f.ld_deleted from hd_trash_member m
			  join ld_document d on d.ld_id = m.hd_objectid
			  join ld_folder f on f.ld_id = d.ld_folderid
			 where m.hd_trashid = :item and m.hd_objecttype = 'D' and m.hd_state in (:states)
			 order by m.hd_objectid
			""";

	/** Nothing comes back with a live folder, whose restore is a move */
	private static final Plan NOTHING = new Plan(0L, Map.of(), new HealdanHooks.RestoreScope(List.of(), List.of()));

	private final TrashStore store;

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	@Autowired
	public TrashRestore(TrashStore store, DataSource dataSource) {
		this.store = store;
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
	}

	/**
	 * @return the bean
	 */
	public static TrashRestore get() {
		return Context.get(TrashRestore.class);
	}

	/**
	 * From the folder-restore hook, before CE sets any row live: the members
	 * of the folder's item in the folder's tree, and the aliases among them
	 * that point into it, in the folder's own state (1 for deleted, 2 for
	 * removed), each only while its row shows that state. From Removed, the
	 * members still deleted under it come back too. Marks them restored and
	 * queues the documents for indexing. A live folder's restore, an admin's
	 * move, brings nothing back.
	 *
	 * @param folderId the folder being restored
	 * @param treeIds the folder and every folder below it, deleted or not
	 * @param actor who restores it, possibly null
	 * @return what the restore sets live; null, with a warning, when the
	 *         folder is in no item, so that CE's restore applies
	 * @throws PersistenceException error at data layer
	 */
	public HealdanHooks.RestoreScope scope(long folderId, Collection<Long> treeIds, User actor)
			throws PersistenceException {
		Plan plan = plan(folderId, treeIds, true);
		if (plan == null)
			return null;
		mark(plan.item(), plan.taken(), actor);
		HealdanHooks.RestoreScope scope = plan.scope();
		queueForIndexing(scope.documentIds());
		CacheEvictor evictor = CacheEvictor.get();
		evictor.evictFoldersAfterCompletion(scope.folderIds());
		evictor.evictDocumentsAfterCompletion(scope.documentIds());
		log.debug("Restoring {} folders and {} documents of Trash item {} with folder {}", scope.folderIds().size(),
				scope.documentIds().size(), plan.item(), folderId);
		return scope;
	}

	/**
	 * What {@link #scope} would bring back, without writing anything, for the
	 * rules that check a restore before it runs
	 *
	 * @param folderId the folder to restore
	 * @param treeIds the folder and every folder below it, deleted or not
	 * @return what the restore would set live; null when the folder is in no
	 *         item, so that CE's restore applies
	 */
	public HealdanHooks.RestoreScope plan(long folderId, Collection<Long> treeIds) {
		Plan plan = plan(folderId, treeIds, false);
		return plan != null ? plan.scope() : null;
	}

	/**
	 * From the document listener, once CE's document restore has stored the
	 * document live: its newest deleted or removed member is restored. CE's
	 * unarchive stores with the same event, on a live document that no item
	 * holds.
	 *
	 * @param document the document, restored
	 * @param transaction the store's history, possibly null
	 */
	public void documentRestored(Document document, DocumentHistory transaction) {
		TrashMember self = new TrashMember('D', document.getId());
		Optional<Long> deleted = store.itemHolding(self, TrashState.DELETED);
		Optional<Long> removed = store.itemHolding(self, TrashState.REMOVED);
		if (deleted.isEmpty() && removed.isEmpty())
			return;
		boolean fromRemoved = deleted.isEmpty() || removed.isPresent() && removed.get() > deleted.get();
		long item = fromRemoved ? removed.get() : deleted.get();
		mark(item, Map.of(fromRemoved ? TrashState.REMOVED : TrashState.DELETED, List.of(self)),
				transaction != null ? transaction.getUser() : null);
		log.debug("Document {} of Trash item {} is restored", document.getId(), item);
	}

	/**
	 * Restores an item back where it was, or into the folder asked for, as the
	 * person, through CE's folder or document restore, so that the rules and
	 * the hooks run: a folder through {@link #scope}, a document with its
	 * versions and the shortcuts its delete took, each back in its own folder
	 * while that is live. All in one transaction.
	 *
	 * @param trashId the item
	 * @param folderId where to restore it; null for back where it was
	 * @param actor who restores it
	 * @param session the person's session, possibly null
	 * @return the folder it went to
	 * @throws PersistenceException the rules' refusal, {@link #NOT_IN_TRASH}
	 *         for an item that isn't deleted or removed, {@link #PLACE_GONE}
	 *         with no folder asked for when {@link #placeOf} is empty, or an
	 *         error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public long restore(long trashId, Long folderId, User actor, Session session) throws PersistenceException {
		TrashItemRow item = store.item(trashId).orElseThrow(() -> new HealdanRuleException(NOT_IN_TRASH));
		boolean folder = TrashRecorder.FOLDER.equals(item.kind());
		TrashMember top = new TrashMember(folder ? 'F' : 'D', item.objectId());
		TrashState state = store.member(trashId, top).map(TrashMemberRow::state).orElse(null);
		Integer row = deleted(top);
		if (row == null || row != code(state)) {
			log.debug("Trash item {} isn't in the Trash: its top is {} with its row at {}", trashId, state, row);
			throw new HealdanRuleException(NOT_IN_TRASH);
		}
		long target = folderId != null ? folderId
				: placeOf(trashId, actor).orElseThrow(() -> new HealdanRuleException(PLACE_GONE));

		if (folder) {
			FolderHistory history = new FolderHistory();
			history.setSession(session);
			if (actor != null)
				history.setUser(actor);
			FolderDAO.get().restore(item.objectId(), target, history);
			return jdbc.queryForObject("select ld_parentid from ld_folder where ld_id = ?", Long.class,
					item.objectId());
		}

		DocumentHistory history = new DocumentHistory();
		history.setSession(session);
		if (actor != null)
			history.setUser(actor);
		DocumentDAO.get().restore(item.objectId(), target, history);
		restoreShortcuts(trashId, top, state, actor);
		return jdbc.queryForObject("select ld_folderid from ld_document where ld_id = ?", Long.class,
				item.objectId());
	}

	/**
	 * @param trashId an item
	 * @param actor who would restore it
	 * @return the folder it was deleted from, while that is live, in a Store
	 *         and readable by the person; empty otherwise
	 * @throws PersistenceException error at data layer
	 */
	public Optional<Long> placeOf(long trashId, User actor) throws PersistenceException {
		Long from = store.item(trashId).map(TrashItemRow::fromFolderId).orElse(null);
		if (from == null || actor == null)
			return Optional.empty();
		Integer row = deleted(new TrashMember('F', from));
		if (row == null || row != 0 || !MatterLocator.get().locate(from).isUnderAStore()
				|| !FolderDAO.get().isReadAllowed(from, actor.getId()))
			return Optional.empty();
		return Optional.of(from);
	}

	/**
	 * For the document listener: the refusal of a restore whose record
	 * couldn't be written, which rolls the restore back
	 *
	 * @param documentId the document being restored
	 * @param cause why it couldn't be recorded
	 * @return the refusal to throw
	 */
	static HealdanVetoException notRecorded(long documentId, Exception cause) {
		log.warn("A restore was refused: it couldn't be recorded in the Trash ({})", cause.getClass().getSimpleName());
		log.debug("Couldn't record the restore of document {} in the Trash", documentId, cause);
		CacheEvictor.get().evictDocumentsOnRollback(List.of(documentId));
		return new HealdanVetoException(NOT_RECORDED, cause);
	}

	/**
	 * @param warn true to warn when the folder is in no item, which only the
	 *        restore itself does
	 */
	private Plan plan(long folderId, Collection<Long> treeIds, boolean warn) {
		TrashMember self = new TrashMember('F', folderId);
		Integer row = deleted(self);
		if (row == null)
			return null;
		if (row == 0)
			return NOTHING;
		TrashState state = row == PersistentObject.DELETED_CODE_DEFAULT ? TrashState.DELETED
				: row == PersistentObject.DELETED_CODE_STRONG ? TrashState.REMOVED : null;
		Optional<Long> item = state != null ? store.itemHolding(self, state) : Optional.empty();
		if (item.isEmpty()) {
			if (warn) {
				log.warn("A restored folder is in no Trash item, so everything deleted in its tree comes back");
				log.debug("Folder {} is in no Trash item, with its row at {}", folderId, row);
			}
			return null;
		}

		// From Removed, what is still deleted under it comes back with it
		List<String> states = state == TrashState.REMOVED
				? List.of(TrashState.DELETED.code(), TrashState.REMOVED.code())
				: List.of(TrashState.DELETED.code());
		MapSqlParameterSource args = new MapSqlParameterSource("item", item.get()).addValue("states", states);
		Set<Long> tree = new HashSet<>(treeIds);
		Map<TrashState, List<TrashMember>> taken = new EnumMap<>(TrashState.class);
		List<Long> folders = new ArrayList<>();
		List<Long> documents = new ArrayList<>();
		named.query(MEMBER_FOLDERS, args, rs -> {
			long id = rs.getLong(1);
			long foldRef = rs.getLong(4);
			boolean aliasIntoTree = !rs.wasNull() && tree.contains(foldRef);
			if ((tree.contains(id) || aliasIntoTree)
					&& take(item.get(), new TrashMember('F', id), TrashState.of(rs.getString(2)), rs.getInt(3), taken))
				folders.add(id);
		});
		named.query(MEMBER_DOCUMENTS, args, rs -> {
			long id = rs.getLong(1);
			if (tree.contains(rs.getLong(4))
					&& take(item.get(), new TrashMember('D', id), TrashState.of(rs.getString(2)), rs.getInt(3), taken))
				documents.add(id);
		});
		return new Plan(item.get(), taken, new HealdanHooks.RestoreScope(folders, documents));
	}

	/**
	 * Takes a member whose row shows its state; one that doesn't stays as it is
	 * in the item
	 *
	 * @return true if taken
	 */
	private static boolean take(long item, TrashMember member, TrashState state, int row,
			Map<TrashState, List<TrashMember>> taken) {
		if (row != code(state)) {
			log.debug("Left {} {} of Trash item {}: it is {} while its row is at {}", member.type(), member.objectId(),
					item, state, row);
			return false;
		}
		taken.computeIfAbsent(state, s -> new ArrayList<>()).add(member);
		return true;
	}

	/**
	 * The shortcuts a document's delete took come back with it, each in its
	 * own folder while that is live, with JDBC as a folder's restore brings
	 * them back: CE's document restore refuses a shortcut anywhere in a Store
	 */
	private void restoreShortcuts(long trashId, TrashMember top, TrashState state, User actor) {
		List<String> states = state == TrashState.REMOVED
				? List.of(TrashState.DELETED.code(), TrashState.REMOVED.code())
				: List.of(TrashState.DELETED.code());
		Map<TrashState, List<TrashMember>> taken = new EnumMap<>(TrashState.class);
		List<Long> shortcuts = new ArrayList<>();
		named.query(MEMBER_DOCUMENTS, new MapSqlParameterSource("item", trashId).addValue("states", states), rs -> {
			long id = rs.getLong(1);
			if (id == top.objectId())
				return;
			TrashMember member = new TrashMember('D', id);
			if (rs.getInt(5) != 0)
				log.debug("Left shortcut {} of Trash item {}: its folder isn't live", id, trashId);
			else if (take(trashId, member, TrashState.of(rs.getString(2)), rs.getInt(3), taken))
				shortcuts.add(id);
		});
		if (shortcuts.isEmpty())
			return;
		for (List<Long> chunk : CollectionUtil.partition(shortcuts, CHUNK)) {
			MapSqlParameterSource ids = new MapSqlParameterSource("ids", chunk);
			named.update("update ld_document set ld_deleted = 0, ld_lastmodified = CURRENT_TIMESTAMP"
					+ " where not ld_deleted = 0 and ld_id in (:ids)", ids);
			named.update("update ld_version set ld_deleted = 0, ld_lastmodified = CURRENT_TIMESTAMP"
					+ " where not ld_deleted = 0 and ld_documentid in (:ids)", ids);
		}
		mark(trashId, taken, actor);
		queueForIndexing(shortcuts);
		CacheEvictor evictor = CacheEvictor.get();
		evictor.evictDocuments(shortcuts);
		evictor.evictDocumentsAfterCompletion(shortcuts);
		log.debug("Restored {} shortcuts of Trash item {}", shortcuts.size(), trashId);
	}

	private void mark(long item, Map<TrashState, List<TrashMember>> taken, User actor) {
		Instant now = Instant.now();
		Long byId = actor != null ? Long.valueOf(actor.getId()) : null;
		String byName = actor != null ? actor.getFullName() : null;
		taken.forEach((from, members) -> store.change(item, members, from, TrashState.RESTORED, byId, byName, now));
	}

	/**
	 * As a move into another matter does ({@code Propagation}): a restored
	 * document is found again once the indexer has read it anew
	 */
	private void queueForIndexing(Collection<Long> documentIds) {
		for (List<Long> chunk : CollectionUtil.partition(new ArrayList<>(documentIds), CHUNK))
			named.update("update ld_document set ld_indexed = :toIndex where ld_indexed = :indexed and ld_id in (:ids)",
					new MapSqlParameterSource("toIndex", IndexingStatus.TO_INDEX.ordinal())
							.addValue("indexed", IndexingStatus.INDEXED.ordinal()).addValue("ids", chunk));
	}

	/**
	 * @return the row's deletion code, null if there is no row
	 */
	private Integer deleted(TrashMember member) {
		List<Integer> found = jdbc.queryForList("select ld_deleted from %s where ld_id = ?"
				.formatted(member.type() == 'F' ? "ld_folder" : "ld_document"), Integer.class, member.objectId());
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * @return the deletion code a row in the state has; -1 for a state no row
	 *         has
	 */
	private static int code(TrashState state) {
		if (state == TrashState.DELETED)
			return PersistentObject.DELETED_CODE_DEFAULT;
		if (state == TrashState.REMOVED)
			return PersistentObject.DELETED_CODE_STRONG;
		return -1;
	}

	/**
	 * @param item the folder's item, 0 for none
	 * @param taken the members taken, by the state they are in
	 * @param scope what the restore sets live
	 */
	private record Plan(long item, Map<TrashState, List<TrashMember>> taken, HealdanHooks.RestoreScope scope) {
	}
}
