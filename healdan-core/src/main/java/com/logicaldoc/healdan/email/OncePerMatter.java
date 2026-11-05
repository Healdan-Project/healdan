// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.util.spring.Context;

/**
 * A matter holds a message once (M5 spec §6.4): a document arriving in a
 * matter that already holds a copy of its message, that the user can read, is
 * refused. Copies are found through {@link EmailIndex}, and each copy's matter
 * afresh with {@link MatterLocator}. A copy the user can't read doesn't count,
 * so a refusal never reveals a document; with no user (a system task) every
 * copy counts. Moves within a matter aren't checked, nor is a new version of
 * the same message; one of another message arrives as that message.
 * <p>
 * Two filings at once are put one after the other by CE's
 * {@code DocumentManager}, which runs each {@code create} and
 * {@code moveToFolder}, its listeners and its store, in one
 * {@code synchronized} block, and commits inside it when no outer transaction
 * is open. So {@link OncePerMatterListener}'s check, inside the store, sees
 * every filing before it. Healdan takes no lock of its own: one taken inside
 * that block and held by a longer transaction would wait on the block in
 * turn. On MariaDB a transaction reads from the snapshot its first read took
 * (REPEATABLE READ), which can be older than a filing committed since, so a
 * transaction begun before the call that checks, whose reads come from a
 * snapshot, looks again in a new one. One the call began, such as the
 * store's own inside the block, took its snapshot after every filing before
 * it.
 * <p>
 * What that leaves: a transaction of several stores commits after the block,
 * and a folder move or restore doesn't go through it at all. So CE's folder
 * copy or merge (one transaction each), a folder move or a restore, and an
 * upload of the same message into the same matter at the same moment, can
 * leave two copies.
 * <p>
 * Folder operations check every email they bring along before anything
 * moves, and two copies of a message arriving together are refused too: the
 * documents of a merge or a copy are stored one by one, and a refusal half
 * way would leave the search index and the file Store changed (plan, D3). A
 * tree checked for a folder move or merge isn't checked again before each of
 * its subfolders and documents moves in the same transaction, as a merge
 * moves them one by one; each document's store still checks it.
 */
@Component
public class OncePerMatter {

	static final String ALREADY_FILED = "This message is already in %s as #%s.";

	/**
	 * The resource, bound to the current transaction, of the folders a folder
	 * move or merge in it checked, each with the matter it was checked against
	 */
	private static final String CHECKED = OncePerMatter.class.getName() + ".checked";

	private final MatterLocator locator;

	private final EmailIndex index;

	private final JdbcTemplate jdbc;

	private final PlatformTransactionManager transactions;

	@Autowired
	public OncePerMatter(MatterLocator locator, EmailIndex index, DataSource dataSource,
			PlatformTransactionManager transactions) {
		this.locator = locator;
		this.index = index;
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = transactions;
	}

	/**
	 * @return the bean
	 */
	public static OncePerMatter get() {
		return Context.get(OncePerMatter.class);
	}

	/**
	 * The copy that would refuse the message arriving in the folder's matter, as
	 * the user sees it.
	 *
	 * @param messageId a normalised Message-ID, or null
	 * @param folderId where the message arrives
	 * @param except a document left out, the one arriving; 0 for a new document
	 * @param actor who files it; null for nobody, when every copy counts
	 * @return the copy, lowest id first; empty outside a matter, or with no
	 *         Message-ID
	 * @throws PersistenceException error at data layer
	 */
	public Optional<Document> copyIn(String messageId, long folderId, long except, User actor)
			throws PersistenceException {
		if (messageId == null)
			return Optional.empty();
		Matter matter = matterAt(folderId);
		if (matter == null)
			return Optional.empty();
		return copyInMatter(messageId, matter.getId(), except, actor);
	}

	private Optional<Document> copyInMatter(String messageId, long matterId, long except, User actor)
			throws PersistenceException {
		return copyAmong(index.copies(messageId), matterId, except, actor, new HashMap<>());
	}

	/**
	 * @param copies the ids of a message's copies, lowest first
	 * @param matters the matter of each folder seen so far, so each is found
	 *        once
	 * @return the first copy in the matter that the user can read
	 */
	private Optional<Document> copyAmong(List<Long> copies, long matterId, long except, User actor,
			Map<Long, Matter> matters) throws PersistenceException {
		for (long id : copies) {
			if (id == except)
				continue;
			Document copy = DocumentDAO.get().findById(id);
			if (copy == null || copy.getFolder() == null
					|| !isMatter(matterId, matterOf(copy.getFolder().getId(), matters)))
				continue;
			if (readable(copy, actor))
				return Optional.of(copy);
		}
		return Optional.empty();
	}

	private Matter matterOf(long folderId, Map<Long, Matter> matters) throws PersistenceException {
		if (matters.containsKey(folderId))
			return matters.get(folderId);
		Matter matter = locator.locate(folderId).matter();
		matters.put(folderId, matter);
		return matter;
	}

	/**
	 * {@link #copyIn}, then refuses. When the current transaction reads from a
	 * snapshot, it looks again in a new one.
	 *
	 * @param messageId a normalised Message-ID, or null
	 * @param folderId where the message arrives
	 * @param except a document left out, the one arriving; 0 for a new document
	 * @param actor who files it; null for nobody
	 * @throws AlreadyFiledException if the matter holds a copy
	 * @throws PersistenceException error at data layer
	 */
	public void check(String messageId, long folderId, long except, User actor) throws PersistenceException {
		if (messageId == null)
			return;
		Matter matter = matterAt(folderId);
		if (matter != null)
			checkArrivals(Map.of(except, messageId), matter, true, actor, false);
	}

	/**
	 * A document moves: checked as it arrives, unless it stays in its matter
	 *
	 * @param docId the document
	 * @param fromFolderId where it is
	 * @param targetFolderId where it goes
	 * @param actor who moves it; null for nobody
	 * @throws AlreadyFiledException if the target's matter holds a copy
	 * @throws PersistenceException error at data layer
	 */
	public void checkMove(long docId, long fromFolderId, long targetFolderId, User actor)
			throws PersistenceException {
		String messageId = index.messageIdOf(docId);
		if (messageId == null)
			return;
		Matter target = matterAt(targetFolderId);
		if (target != null && !isMatter(target.getId(), matterAt(fromFolderId)))
			checkArrivals(Map.of(docId, messageId), target, true, actor, false);
	}

	/**
	 * {@link #checkMove}, before CE's move begins: nothing when a folder move
	 * or merge in this transaction already checked the tree the document leaves
	 * against the target's matter, as a merge moves its documents one by one.
	 * The move's store checks it either way.
	 *
	 * @param docId the document
	 * @param fromFolderId where it is
	 * @param targetFolderId where it goes
	 * @param actor who moves it; null for nobody
	 * @throws AlreadyFiledException if the target's matter holds a copy
	 * @throws PersistenceException error at data layer
	 */
	public void checkMoveAhead(long docId, long fromFolderId, long targetFolderId, User actor)
			throws PersistenceException {
		Long checkedAgainst = checkedTrees().get(fromFolderId);
		if (checkedAgainst == null || !isMatter(checkedAgainst, matterAt(targetFolderId)))
			checkMove(docId, fromFolderId, targetFolderId, actor);
	}

	/**
	 * A folder moves, or merges, into another matter: {@link #check} for every
	 * email under it, itself included, arriving in the target's matter. Nothing
	 * when both are in one matter, or when a folder move or merge in this
	 * transaction checked the folder against that matter already.
	 *
	 * @param folderId the folder moved, or merged
	 * @param targetFolderId its new parent, or the folder it merges into
	 * @param actor who moves it; null for nobody
	 * @throws AlreadyFiledException if the target's matter holds a copy, or two
	 *         would arrive
	 * @throws PersistenceException error at data layer
	 */
	public void checkSubtree(long folderId, long targetFolderId, User actor) throws PersistenceException {
		Matter target = matterAt(targetFolderId);
		if (target == null || isMatter(target.getId(), storedMatter(folderId))
				|| Long.valueOf(target.getId()).equals(checkedTrees().get(folderId)))
			return;
		Collection<Long> tree = FolderDAO.get().findFolderIdInTree(folderId, false);
		checkArrivals(index.emailsIn(tree, false), target, true, actor, true);
		remember(tree, target.getId());
	}

	/**
	 * A folder is copied with its documents: the copy of every email under it
	 * arrives in the target's matter, where the email itself counts
	 *
	 * @param folderId the folder copied
	 * @param targetFolderId where the copy goes
	 * @param actor who copies it; null for nobody
	 * @throws AlreadyFiledException if the target's matter holds a copy, or two
	 *         would arrive
	 * @throws PersistenceException error at data layer
	 */
	public void checkSubtreeCopy(long folderId, long targetFolderId, User actor) throws PersistenceException {
		Matter target = matterAt(targetFolderId);
		if (target != null)
			checkArrivals(index.emailsIn(FolderDAO.get().findFolderIdInTree(folderId, false), false), target, false,
					actor, true);
	}

	/**
	 * A deleted folder is restored, also into the matter it was in. CE brings
	 * back the documents deleted in its tree, and a live document there comes
	 * back too where a deleted folder hid it; CE restores a live folder by
	 * moving it, which brings its live emails along. Each such email arrives,
	 * unless it is a live copy in the parent's matter already. Two copies of a
	 * message coming back together are refused, such as one deleted on its own
	 * and one filed after it and deleted with the folder.
	 *
	 * @param folderId the folder restored
	 * @param parentId where to
	 * @param actor who restores it; null for nobody
	 * @throws AlreadyFiledException if the parent's matter holds a copy, or two
	 *         would come back
	 * @throws PersistenceException error at data layer
	 */
	public void checkSubtreeRestore(long folderId, long parentId, User actor) throws PersistenceException {
		Matter target = matterAt(parentId);
		if (target == null)
			return;
		Collection<Long> tree = FolderDAO.get().findFolderIdInTree(folderId, true);
		Map<Long, String> arriving = new TreeMap<>(index.emailsIn(tree, true));
		Map<Long, Matter> matters = new HashMap<>();
		for (Map.Entry<Long, String> live : index.emailsIn(tree, false).entrySet()) {
			Document document = DocumentDAO.get().findById(live.getKey());
			if (document == null || document.getFolder() == null
					|| !isMatter(target.getId(), matterOf(document.getFolder().getId(), matters)))
				arriving.put(live.getKey(), live.getValue());
		}
		checkArrivals(arriving, target, true, actor, true);
	}

	/**
	 * @param arriving the documents arriving, each with its Message-ID
	 * @param target the matter they arrive in
	 * @param selves true if they arrive themselves, so each leaves itself out;
	 *        false for copies of them, where they count
	 * @param twins true to refuse two of one message arriving together
	 */
	private void checkArrivals(Map<Long, String> arriving, Matter target, boolean selves, User actor, boolean twins)
			throws PersistenceException {
		if (arriving.isEmpty())
			return;
		Optional<Document> copy = firstCopy(arriving, target.getId(), selves, actor);
		if (copy.isEmpty() && readsFromSnapshot())
			copy = inNewTransaction(() -> firstCopy(arriving, target.getId(), selves, actor));
		if (copy.isPresent())
			throw refusal(target, copy.get());
		if (twins)
			checkTwins(arriving, target, actor);
	}

	private Optional<Document> firstCopy(Map<Long, String> arriving, long matterId, boolean selves, User actor)
			throws PersistenceException {
		// One lookup for all the messages, and one walk for each folder a copy is in
		Map<String, List<Long>> copies = index.copiesOf(arriving.values());
		Map<Long, Matter> matters = new HashMap<>();
		for (Map.Entry<Long, String> email : arriving.entrySet()) {
			Optional<Document> copy = copyAmong(copies.getOrDefault(email.getValue(), List.of()), matterId,
					selves ? email.getKey() : 0L, actor, matters);
			if (copy.isPresent())
				return copy;
		}
		return Optional.empty();
	}

	/**
	 * Two copies of a message arriving together would both be in the matter:
	 * the first the user can read is named, in the matter it is in now. One a
	 * folder restore brings back is in the trash, where the DAO doesn't find
	 * it, so it is read from its row and named in the matter it comes back to.
	 */
	private void checkTwins(Map<Long, String> arriving, Matter target, User actor) throws PersistenceException {
		Map<String, List<Long>> byMessage = new LinkedHashMap<>();
		arriving.forEach((id, messageId) -> byMessage.computeIfAbsent(messageId, k -> new ArrayList<>()).add(id));
		for (List<Long> ids : byMessage.values()) {
			if (ids.size() < 2)
				continue;
			for (long id : ids) {
				Document twin = DocumentDAO.get().findById(id);
				if (twin != null && readable(twin, actor)) {
					Matter now = twin.getFolder() != null ? locator.locate(twin.getFolder().getId()).matter() : null;
					throw refusal(now != null ? now : target, twin);
				}
				if (twin == null) {
					List<String> trashed = jdbc.queryForList(
							"select ld_customid from ld_document where ld_id = ? and ld_deleted <> 0", String.class, id);
					if (!trashed.isEmpty() && readableInTrash(id, actor))
						throw refusal(target, id, trashed.get(0));
				}
			}
		}
	}

	private static boolean readable(Document copy, User actor) throws PersistenceException {
		return actor == null || AccessChecks.readableDocument(copy, actor).isPresent();
	}

	/**
	 * @return true if the user may read the deleted document, as they will once
	 *         it is restored
	 */
	private static boolean readableInTrash(long docId, User actor) throws PersistenceException {
		return actor == null || FilingRules.get().isAdmin(actor)
				|| StoredRows.get().mayReadDocument(docId, actor.getId());
	}

	private static AlreadyFiledException refusal(Matter matter, Document copy) throws PersistenceException {
		return refusal(matter, copy.getId(), copy.getCustomId());
	}

	private static AlreadyFiledException refusal(Matter matter, long copyId, String docNumber)
			throws PersistenceException {
		Client client = ClientDAO.get().findById(matter.getClientId());
		String number = MatterDisplay.number(client != null ? client.getNumber() : 0L, matter.getNumber());
		return new AlreadyFiledException(ALREADY_FILED.formatted(number, docNumber), copyId);
	}

	/**
	 * @return the matter a document filed in the folder is in; the folder an
	 *         alias points to stands for the alias, as CE files there
	 */
	private Matter matterAt(long folderId) throws PersistenceException {
		Folder folder = FolderDAO.get().findById(folderId);
		if (folder != null && folder.getFoldRef() != null) {
			Folder referenced = FolderDAO.get().findById(folder.getFoldRef());
			if (referenced != null)
				folder = referenced;
		}
		return locator.locate(folder).matter();
	}

	/**
	 * @return the matter the folder is in as stored, not where an unsaved move
	 *         puts it; for a matter's workspace, its own
	 */
	private Matter storedMatter(long folderId) throws PersistenceException {
		FolderRow row = StoredRows.get().folder(folderId);
		if (row == null || row.parentId() == folderId)
			return locator.locate(folderId).matter();
		FolderPosition parent = locator.locate(row.parentId());
		if (parent.kind() == FolderPosition.Kind.STORE)
			return MatterDAO.get().findByFolderId(folderId);
		return parent.matter();
	}

	private static boolean isMatter(long matterId, Matter matter) {
		return matter != null && matter.getId() == matterId;
	}

	/**
	 * @return true if the current transaction reads from a snapshot (REPEATABLE
	 *         READ or SERIALIZABLE) that can be older than another filing's
	 *         commit: one begun before the innermost transactional call, such as
	 *         a folder copy's around each document's store. The store's own,
	 *         begun inside {@code DocumentManager}'s block, took its snapshot
	 *         after every filing before it.
	 */
	private boolean readsFromSnapshot() {
		if (!TransactionSynchronizationManager.isActualTransactionActive() || begunByTheCall())
			return false;
		Integer isolation = jdbc.execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation);
		return isolation != null && isolation >= Connection.TRANSACTION_REPEATABLE_READ;
	}

	/**
	 * @return true if the innermost transactional call began the current
	 *         transaction; false when it joined one, or no such call is under
	 *         way, as for a transaction begun by a {@code TransactionTemplate}
	 */
	private static boolean begunByTheCall() {
		try {
			return TransactionAspectSupport.currentTransactionStatus().isNewTransaction();
		} catch (NoTransactionException e) {
			return false;
		}
	}

	/**
	 * @return the folders checked in the current transaction by a folder move
	 *         or merge, each with the matter it was checked against
	 */
	@SuppressWarnings("unchecked")
	private static Map<Long, Long> checkedTrees() {
		Object bound = TransactionSynchronizationManager.getResource(CHECKED);
		return bound != null ? (Map<Long, Long>) bound : Map.of();
	}

	/**
	 * Remembers, until the current transaction ends, that the tree was checked
	 * against the matter; nothing outside a transaction
	 */
	@SuppressWarnings("unchecked")
	private static void remember(Collection<Long> tree, long matterId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive())
			return;
		Map<Long, Long> checked = (Map<Long, Long>) TransactionSynchronizationManager.getResource(CHECKED);
		if (checked == null) {
			checked = new HashMap<>();
			TransactionSynchronizationManager.bindResource(CHECKED, checked);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					TransactionSynchronizationManager.unbindResourceIfPossible(CHECKED);
				}
			});
		}
		for (Long folderId : tree)
			checked.put(folderId, matterId);
	}

	@FunctionalInterface
	private interface Lookup {
		Optional<Document> run() throws PersistenceException;
	}

	/**
	 * Runs the lookup in a read-only transaction of its own, READ COMMITTED: it
	 * sees what other filings committed since the current transaction's
	 * snapshot, though not the current transaction's own writes, which the
	 * lookup in it has seen
	 */
	private Optional<Document> inNewTransaction(Lookup lookup) throws PersistenceException {
		TransactionTemplate template = new TransactionTemplate(transactions);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		template.setReadOnly(true);
		PersistenceException[] failure = new PersistenceException[1];
		Optional<Document> found = template.execute(status -> {
			try {
				return lookup.run();
			} catch (PersistenceException e) {
				failure[0] = e;
				return Optional.empty();
			}
		});
		if (failure[0] != null)
			throw failure[0];
		return found != null ? found : Optional.empty();
	}
}
