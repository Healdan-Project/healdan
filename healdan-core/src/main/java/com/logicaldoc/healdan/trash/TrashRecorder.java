// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
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

import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Records each delete as one Trash item, in the delete's own transaction
 * (spec 5.2), so a delete that fails records nothing.
 * <ul>
 * <li>A folder's tree from Healdan's folder-delete hook, which every route's
 * {@code deleteTree} calls before CE writes: the folder, what is live in it,
 * and the live aliases pointing into it, as CE is about to delete them.</li>
 * <li>A document, from the document listener, once a store has put it in the
 * trash, with the shortcuts of it deleted beside it.</li>
 * <li>A folder a route deletes on its own, without the hook (CMIS's delete of
 * an empty folder, the source a merge empties), from the folder listener,
 * with the aliases of it CE's store deletes with it.</li>
 * </ul>
 * Something already a deleted member of an item records nothing, so a route
 * that deletes a tree item by item after the hook (CMIS) makes one item.
 * CMIS calls the hook outside any transaction and commits each member's
 * delete on its own, so there the item commits first, whatever follows.
 */
@Component
public class TrashRecorder {

	private static final Logger log = LoggerFactory.getLogger(TrashRecorder.class);

	static final String FOLDER = "folder";

	static final String DOCUMENT = "document";

	/**
	 * A delete whose item can't be written is refused, never left in the trash
	 * unrecorded, where nothing would show or destroy it
	 */
	static final String NOT_RECORDED = "The delete couldn't be recorded in the Trash.";

	/** Large id lists are queried in chunks */
	private static final int CHUNK = 500;

	private final TrashStore store;

	private final StoredRows rows;

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	@Autowired
	public TrashRecorder(TrashStore store, StoredRows rows, DataSource dataSource) {
		this.store = store;
		this.rows = rows;
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
	}

	/**
	 * @return the recorder bean
	 */
	public static TrashRecorder get() {
		return Context.get(TrashRecorder.class);
	}

	/**
	 * From {@code beforeFolderDeleteTree}, once the rules have let the delete
	 * through and before CE writes: records the folder, its live tree, the
	 * live documents in it (shortcuts included) and the live aliases pointing
	 * into it, which CE's {@code deleteTree} is about to delete. Nothing is
	 * recorded for a delete CE then refuses (no user, the root or the Default
	 * workspace), as its refusal commits.
	 *
	 * @param folderId the folder being deleted
	 * @param actor who deletes it
	 * @return the item's id, 0 if nothing was recorded
	 * @throws PersistenceException error at data layer
	 */
	public long treeDeleting(long folderId, User actor) throws PersistenceException {
		FolderRow top = rows.folder(folderId);
		if (actor == null || top == null || top.deleted() != 0 || ceRefuses(top))
			return 0L;

		List<Long> tree = rows.tree(folderId);
		List<Long> documents = live("ld_document", "ld_folderid", tree);
		Set<TrashMember> members = new LinkedHashSet<>();
		tree.forEach(id -> members.add(folderMember(id)));
		live("ld_folder", "ld_foldref", tree).forEach(id -> members.add(folderMember(id)));
		documents.forEach(id -> members.add(documentMember(id)));

		FolderDAO folders = FolderDAO.get();
		Folder folder = folders.findById(folderId);
		Folder parent = folders.findById(top.parentId());
		long id = store.insert(new TrashItemRow(0L, folder.getTenantId(), FOLDER, folderId, folder.getName(), null,
				top.parentId(), path(parent), matterOf(folderId, parent), tree.size() - 1, documents.size(),
				actor.getId(), actor.getFullName(), Instant.now()), members);
		log.debug("Recorded the delete of folder {} as Trash item {}", folderId, id);
		return id;
	}

	/**
	 * From the folder listener: records a folder a store took from live to
	 * deleted, unless it is already a deleted member of an item, with the
	 * aliases of it CE's store deleted with it. CE's {@code updateAliases}
	 * gives every live alias of a folder it stores the folder's state, with no
	 * listener call for the alias, and before the listeners run, so an alias
	 * this store deleted is told by its row as it was before the session wrote
	 * it. Like a tree's outside aliases, they aren't counted.
	 *
	 * @param folder the folder, deleted
	 * @param transaction the store's history, possibly null
	 * @throws PersistenceException error at data layer
	 */
	public void folderDeleted(Folder folder, FolderHistory transaction) throws PersistenceException {
		TrashMember self = folderMember(folder.getId());
		if (deletedMember(self))
			return;

		Set<TrashMember> members = new LinkedHashSet<>(List.of(self));
		// As CE's store, which updates the aliases only of a folder that isn't one itself
		if (folder.getFoldRef() == null)
			for (Long alias : jdbc.queryForList("select ld_id from ld_folder where ld_foldref = ? and not ld_id = ?",
					Long.class, folder.getId(), folder.getId())) {
				FolderRow before = rows.folder(alias);
				if (before != null && before.deleted() == 0 && !deletedMember(folderMember(alias)))
					members.add(folderMember(alias));
			}

		Folder parent = FolderDAO.get().findById(folder.getParentId());
		User user = transaction != null ? transaction.getUser() : null;
		long id = store.insert(new TrashItemRow(0L, folder.getTenantId(), FOLDER, folder.getId(), folder.getName(),
				null, folder.getParentId(), path(parent), matterOf(folder.getId(), parent), 0, 0,
				user != null ? Long.valueOf(user.getId()) : folder.getDeleteUserId(),
				user != null ? user.getFullName() : folder.getDeleteUser(), Instant.now()), members);
		log.debug("Recorded the delete of folder {} as Trash item {}", folder.getId(), id);
	}

	/**
	 * From the document listener: records a document a store put in the trash,
	 * unless it is already a deleted member of an item. A document's item takes
	 * its shortcuts already in the trash that no item holds, as the Classic
	 * Interface deletes them first. A shortcut stored with no history was
	 * deleted beside its document, as only the routes that delete a document's
	 * shortcuts with it pass none: it joins the document's item, or, when the
	 * document isn't deleted yet, waits for it. A shortcut deleted with a
	 * history was deleted on its own, and is an item of its own.
	 *
	 * @param document the document, deleted
	 * @param transaction the store's history, possibly null
	 * @throws PersistenceException error at data layer
	 */
	public void documentDeleted(Document document, DocumentHistory transaction) throws PersistenceException {
		TrashMember self = documentMember(document.getId());
		if (deletedMember(self))
			return;

		Long target = document.getDocRef();
		if (target != null && transaction == null) {
			Optional<Long> item = store.itemHolding(documentMember(target), TrashState.DELETED);
			if (item.isPresent()) {
				store.addMember(item.get(), self);
				log.debug("Added shortcut {} to Trash item {}", document.getId(), item.get());
			}
			return;
		}

		Set<TrashMember> members = new LinkedHashSet<>(List.of(self));
		if (target == null)
			for (Long shortcut : jdbc.queryForList("select ld_id from ld_document where ld_docref = ? and ld_deleted = 1",
					Long.class, document.getId()))
				if (!deletedMember(documentMember(shortcut)))
					members.add(documentMember(shortcut));

		Folder folder = document.getFolder();
		Matter matter = folder != null ? MatterLocator.get().locate(folder).matter() : null;
		User user = transaction != null ? transaction.getUser() : null;
		long id = store.insert(new TrashItemRow(0L, document.getTenantId(), DOCUMENT, document.getId(),
				document.getFileName(), target == null ? docNumber(document) : null,
				folder != null ? folder.getId() : null, path(folder), matter != null ? matter.getId() : null, 0, 0,
				user != null ? Long.valueOf(user.getId()) : document.getDeleteUserId(),
				user != null ? user.getFullName() : document.getDeleteUser(), Instant.now()), members);
		log.debug("Recorded the delete of document {} as Trash item {}", document.getId(), id);
	}

	/**
	 * For the listeners: the refusal of a delete whose item couldn't be
	 * written, which rolls the delete back. The folder or document is evicted
	 * from the second-level cache on rollback, as the rules' refusals are.
	 *
	 * @param member the folder or document being deleted
	 * @param cause why it couldn't be recorded
	 * @return the refusal to throw
	 */
	static HealdanVetoException notRecorded(TrashMember member, Exception cause) {
		log.warn("A delete was refused: it couldn't be recorded in the Trash ({})", cause.getClass().getSimpleName());
		log.debug("Couldn't record {} {} in the Trash", member.type(), member.objectId(), cause);
		CacheEvictor evictor = CacheEvictor.get();
		if (member.type() == 'F')
			evictor.evictFoldersOnRollback(List.of(member.objectId()));
		else
			evictor.evictDocumentsOnRollback(List.of(member.objectId()));
		return new HealdanVetoException(NOT_RECORDED, cause);
	}

	private boolean deletedMember(TrashMember member) {
		return store.itemHolding(member, TrashState.DELETED).isPresent();
	}

	/**
	 * CE's {@code deleteTree} refuses the root and the Default workspace after
	 * the hook, with an exception that commits
	 */
	private boolean ceRefuses(FolderRow top) {
		if (top.parentId() == top.id())
			return true;
		FolderRow parent = rows.folder(top.parentId());
		return parent != null && parent.parentId() == parent.id() && Folder.DEFAULTWORKSPACENAME.equals(top.name());
	}

	/**
	 * @return the live rows of the table whose column holds one of the ids
	 */
	private List<Long> live(String table, String column, Collection<Long> ids) {
		List<Long> all = new ArrayList<>(ids);
		List<Long> found = new ArrayList<>();
		for (int i = 0; i < all.size(); i += CHUNK)
			found.addAll(named.queryForList(
					"select ld_id from %s where ld_deleted = 0 and %s in (:ids) order by ld_id".formatted(table, column),
					new MapSqlParameterSource("ids", all.subList(i, Math.min(all.size(), i + CHUNK))), Long.class));
		return found;
	}

	/**
	 * @param from the folder an item was in, live
	 * @return the names from its Store down to it, as the API's paths run;
	 *         none outside the Stores
	 */
	static List<String> path(Folder from) throws PersistenceException {
		if (from == null || !MatterLocator.get().locate(from).isUnderAStore())
			return List.of();
		List<Folder> chain = new ArrayList<>(FolderDAO.get().findParents(from.getId()));
		chain.add(from);
		// CE's parents run from the root, which isn't part of a path
		return chain.subList(1, chain.size()).stream().map(Folder::getName).toList();
	}

	/**
	 * @return the matter the folder is, or is in: a folder directly in a Store
	 *         is a matter's own
	 */
	static Long matterOf(long folderId, Folder parent) throws PersistenceException {
		FolderPosition above = MatterLocator.get().locate(parent);
		Matter matter = above.kind() == FolderPosition.Kind.STORE ? MatterDAO.get().findByFolderId(folderId)
				: above.matter();
		return matter != null ? matter.getId() : null;
	}

	/**
	 * @return the document's doc #, its custom id when that is a number
	 */
	private static String docNumber(Document document) {
		return docNumber(document.getCustomId());
	}

	/**
	 * @return the doc # a custom id is, when it is a number
	 */
	static String docNumber(String customId) {
		boolean numbered = customId != null && !customId.isEmpty() && customId.length() <= 18
				&& customId.chars().allMatch(c -> c >= '0' && c <= '9');
		return numbered ? customId : null;
	}

	private static TrashMember folderMember(long id) {
		return new TrashMember('F', id);
	}

	private static TrashMember documentMember(long id) {
		return new TrashMember('D', id);
	}
}
