// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.rules;

import java.util.List;
import java.util.Map;

import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.docnumber.DocNumberListener;
import com.logicaldoc.healdan.email.EmailIndex;
import com.logicaldoc.healdan.email.OncePerMatter;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.preview.ConversionPages;
import com.logicaldoc.healdan.profile.EditMarks;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.profile.Propagation;
import com.logicaldoc.healdan.profile.StoredProfile;
import com.logicaldoc.healdan.security.PermissionRules;
import com.logicaldoc.healdan.util.CacheEvictor;

import com.logicaldoc.util.spring.Context;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Healdan's checks on the operations whose side effects a listener veto comes
 * too late for (spec §7.5). CE calls each one before the operation writes
 * anything: a document move removes the index hit and a restore undeletes the
 * rows with JDBC; a folder delete or restore is JDBC throughout; a merge moves
 * documents one by one, and a copy writes files and schedules versions, before
 * the store a listener could veto. A folder delete also checks R74's
 * permissions here, as every route deletes a tree through this hook. The
 * once-per-matter rule (M5 spec §6.4) checks moves, restores, merges and
 * folder copies here, for the same reasons.
 */
@Component
public class HealdanHooksImpl implements HealdanHooks {

	static final String EVERYONE_DELETE = "The Everyone group can't be deleted.";

	static final String EVERYONE_RENAME = "The Everyone group can't be renamed.";

	static final String PROFILE_SET_DELETE = "The Healdan profile's attribute set can't be deleted: it holds the "
			+ "Class and Subclass lists.";

	static final String PROFILE_SET_ATTRIBUTES = "The Healdan profile's attribute set must keep its Class and "
			+ "Subclass attributes.";

	private final FilingRules rules;

	private final StoredRows rows;

	private final OncePerMatter oncePerMatter;

	@Autowired
	public HealdanHooksImpl(FilingRules rules, StoredRows rows, OncePerMatter oncePerMatter) {
		this.rules = rules;
		this.rows = rows;
		this.oncePerMatter = oncePerMatter;
	}

	@PostConstruct
	public void register() {
		HealdanHooks.register(this);
	}

	@PreDestroy
	public void unregister() {
		// Only if nothing else has replaced it since
		if (HealdanHooks.get() == this)
			HealdanHooks.register(null);
	}

	/**
	 * Not a rule: puts the document's own doc # in the value object, so that the
	 * update writes no other number into its version and a doc # that belongs to
	 * another document raises no duplicate error (spec 6.3: a change is ignored).
	 */
	@Override
	public void beforeDocumentUpdate(Document document, Document docVO, DocumentHistory transaction)
			throws PersistenceException {
		if (document != null)
			DocNumberListener.protect(document.getId(), docVO);
		if (document != null && docVO != null)
			stampUpdate(document, docVO);
	}

	/**
	 * Puts the profile on the value object, so that the version CE writes before
	 * the store records the stamped values (client and matter back from the
	 * workspace, blanks inherited), and refuses an update that leaves a Subclass
	 * the user supplied outside its Class (spec 6.6), before CE writes anything.
	 * A Class and Subclass that didn't change are never refused. Announces the
	 * edit, so that the store that follows may change the local marks.
	 */
	private void stampUpdate(Document document, Document docVO) throws PersistenceException {
		if (document.getFolder() == null
				|| !MatterLocator.get().locate(document.getFolder()).isInsideMatter())
			return;

		boolean unchanged = StoredProfile.unchanged(document.getId(), docVO.getValue(HealdanConstants.ATTR_DOCCLASS),
				docVO.getValue(HealdanConstants.ATTR_DOCSUBCLASS));
		long folderId = document.getFolder().getId();
		Vetoes.check(Vetoes.ids(folderId), Vetoes.ids(document.getId()),
				() -> Inheritance.get().stampValueObject(docVO, folderId, true, !unchanged));
		EditMarks.mark(document.getId());
	}

	@Override
	public void beforeDocumentMove(Document document, Folder target, DocumentHistory transaction)
			throws PersistenceException {
		// A drop an earlier, failed move of the document recorded is not this move's
		EditMarks.clearDropped(document.getId());
		Vetoes.check(Vetoes.ids(target != null ? target.getId() : null), Vetoes.ids(document.getId()), () -> {
			if (document.getDocRef() != null)
				rules.checkShortcut(target);
			rules.checkDocumentTarget(target);
			// A shortcut is no copy; the store checks again, inside DocumentManager's synchronized block
			if (target != null && document.getFolder() != null && document.getDocRef() == null)
				oncePerMatter.checkMoveAhead(document.getId(), document.getFolder().getId(), target.getId(),
						FilingRules.actor(transaction));
		});
		stampMove(document, target);
	}

	/**
	 * Re-stamps the document for its new folder before CE creates the version of
	 * the move, which shares the document's attribute objects
	 */
	private void stampMove(Document document, Folder target) throws PersistenceException {
		if (target == null || document.getDocRef() != null || !Hibernate.isInitialized(document.getAttributes())
				|| !MatterLocator.get().locate(target).isInsideMatter())
			return;
		Inheritance.get().stampForMove(document, target.getId());
	}

	@Override
	public void beforeDocumentRestore(long docId, long folderId, DocumentHistory transaction)
			throws PersistenceException {
		Folder target = FolderDAO.get().findById(folderId);
		Long docRef = rows.documentRef(docId);
		Vetoes.check(Vetoes.ids(folderId), Vetoes.ids(docId), () -> {
			rules.checkDocumentRestore(docId, folderId, FilingRules.actor(transaction));
			if (docRef != null)
				rules.checkShortcut(target);
			rules.checkDocumentTarget(target);
			if (docRef == null)
				oncePerMatter.check(EmailIndex.get().messageIdOf(docId), folderId, docId,
						FilingRules.actor(transaction));
		});
	}

	@Override
	public void beforeFolderDeleteTree(long folderId, FolderHistory transaction) throws PersistenceException {
		if (folderId == 0L)
			return;
		Folder folder = FolderDAO.get().findById(folderId);
		if (folder == null)
			return;
		User actor = FilingRules.actor(transaction);
		Vetoes.check(Vetoes.ids(folderId), List.of(), () -> {
			rules.checkFolderDelete(folder, actor);
			PermissionRules.get().checkDeleteTree(folderId, actor);
		});
		evictTreeAfterCompletion(folderId);
	}

	/**
	 * Not a rule: CE deletes the tree with JDBC and evicts only the folders
	 * from the second-level cache. A document left there live would be loaded
	 * as live by the next DAO read, and Hibernate would then delete its
	 * attribute and ACL rows, which a restore can't bring back (plan, R9). So
	 * the tree's folders and documents, collected before CE marks them
	 * deleted, are evicted once the delete's transaction ends.
	 */
	private void evictTreeAfterCompletion(long folderId) {
		List<Long> folderIds = rows.tree(folderId);
		List<Long> documentIds = rows.documentIds(folderIds);
		CacheEvictor evictor = CacheEvictor.get();
		evictor.evictFoldersAfterCompletion(folderIds);
		evictor.evictDocumentsAfterCompletion(documentIds);
	}

	@Override
	public void beforeFolderRestore(long folderId, long parentId, FolderHistory transaction)
			throws PersistenceException {
		Vetoes.check(Vetoes.ids(folderId, parentId), List.of(), () -> {
			rules.checkFolderRestore(folderId, parentId, FilingRules.actor(transaction));
			PermissionRules.get().checkRestore(folderId, parentId);
			oncePerMatter.checkSubtreeRestore(folderId, parentId, FilingRules.actor(transaction));
		});
	}

	/**
	 * CE restores the folder, its subtree and their documents with JDBC, so
	 * what the second-level cache holds of them (deleted, or under the old
	 * parent) is evicted now and again when the transaction ends (carry-forward:
	 * an after-restore rule evicts the whole restored tree). Then the restored
	 * tree is re-stamped from its new place, documents included (spec 7.2), and
	 * narrowed to its new parent's permissions (spec 7.3).
	 */
	@Override
	public void afterFolderRestore(long folderId, long parentId, FolderHistory transaction)
			throws PersistenceException {
		List<Long> folderIds = rows.tree(folderId);
		List<Long> documentIds = rows.documentIds(folderIds);
		CacheEvictor evictor = CacheEvictor.get();
		evictor.evictFolders(folderIds);
		evictor.evictDocuments(documentIds);
		evictor.evictFoldersAfterCompletion(folderIds);
		evictor.evictDocumentsAfterCompletion(documentIds);
		Propagation.get().restampSubtree(folderId);
		PermissionRules.get().narrowTree(folderId);
		for (FolderTreeListener listener : Context.get().getBeansOfType(FolderTreeListener.class))
			listener.afterTreeRestored(folderId, parentId, documentIds);
	}

	@Override
	public void beforeFolderCopy(Folder source, Folder target, String newName, boolean foldersOnly,
			String securityOption, FolderHistory transaction) throws PersistenceException {
		Vetoes.check(Vetoes.ids(source != null ? source.getId() : null, target != null ? target.getId() : null),
				List.of(), () -> {
					rules.checkFolderCopy(source, target, newName, foldersOnly, FilingRules.actor(transaction));
					if (source != null && target != null && !foldersOnly)
						oncePerMatter.checkSubtreeCopy(source.getId(), target.getId(), FilingRules.actor(transaction));
				});
		PermissionRules.copying();
	}

	@Override
	public void beforeFolderMerge(Folder source, Folder target, FolderHistory transaction)
			throws PersistenceException {
		Vetoes.check(Vetoes.ids(source != null ? source.getId() : null, target != null ? target.getId() : null),
				List.of(), () -> {
					rules.checkFolderMerge(source, target, FilingRules.actor(transaction));
					if (source != null && target != null)
						oncePerMatter.checkSubtree(source.getId(), target.getId(), FilingRules.actor(transaction));
				});
	}

	/**
	 * Healdan finds Everyone by its name, which CE's GUI never lets anyone
	 * change, but a hand-made saveGroup call or SOAP's storeGroup could (R138).
	 * The name is read with JDBC: the group being stored already carries the
	 * new one.
	 */
	@Override
	public void beforeGroupStore(Group group) throws PersistenceException {
		if (group == null || group.getId() == 0 || HealdanConstants.EVERYONE_GROUP.equals(group.getName()))
			return;
		String stored = GroupDAO.get().queryForString("select ld_name from ld_group where ld_id = :id",
				Map.of("id", group.getId()));
		if (HealdanConstants.EVERYONE_GROUP.equals(stored))
			throw new HealdanRuleException(EVERYONE_RENAME);
	}

	/**
	 * Every user but read-only ones stays in Everyone (plan, R132), so deleting
	 * the group would leave their memberships behind, which CE's permission
	 * checks still count
	 */
	@Override
	public void beforeGroupDelete(Group group) throws PersistenceException {
		if (group != null && HealdanConstants.EVERYONE_GROUP.equals(group.getName()))
			throw new HealdanRuleException(EVERYONE_DELETE);
	}

	/**
	 * The profile's attribute set holds the Class and Subclass lists, which
	 * deleting it would empty, for whoever holds Document metadata (25), CE's
	 * poweruser group among them
	 */
	@Override
	public void beforeAttributeSetDelete(AttributeSet set) throws PersistenceException {
		if (set != null && ProfileSchema.get().holdsTheLists(set.getId()))
			throw new HealdanRuleException(PROFILE_SET_DELETE);
	}

	/**
	 * CE deletes the options of an attribute a stored set no longer has, so
	 * taking Class or Subclass out of the set the profile takes it from
	 * empties its list. Only those it takes from the set are kept.
	 */
	@Override
	public void beforeAttributeSetStore(AttributeSet set) throws PersistenceException {
		if (set == null || set.getId() == 0)
			return;
		for (String list : ProfileSchema.get().listsIn(set.getId()))
			if (set.getAttribute(list) == null)
				throw new HealdanRuleException(PROFILE_SET_ATTRIBUTES);
	}

	/**
	 * CE's "apply to subfolders" deletes every ACL below the root with JDBC,
	 * which would re-open a restricted folder (spec 7.3); from the root or a
	 * Store it can point matter folders at the root, which the matters'
	 * narrowing replaces before the transaction commits (R28)
	 */
	@Override
	public void beforeApplySecurityToTree(long rootId, FolderHistory transaction) throws PersistenceException {
		Vetoes.check(Vetoes.ids(rootId), List.of(),
				() -> PermissionRules.get().checkApplyToTree(rootId, FilingRules.actor(transaction)));
		PermissionRules.get().afterApplySecurityToTree(rootId);
	}

	/**
	 * A Word file that saved no page count takes its preview's (M1 spec
	 * section 9), on a recount as when the preview worker fills it in
	 */
	@Override
	public int countPages(Document document, int pages) throws PersistenceException {
		return ConversionPages.recount(document, pages);
	}
}
