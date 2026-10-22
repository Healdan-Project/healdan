package com.logicaldoc.healdan.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.history.History;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.StoredRows.Contents;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * The filing rule and the matter-structure rules (spec §7.1 and §7.2): where
 * documents and folders may go, and who may create, rename, move, delete or
 * restore which folders. Each check throws a {@link HealdanRuleException} with
 * a message for the user, after asking {@link CacheEvictor} to evict on
 * rollback the folders it refuses (plan, D3).
 * <p>
 * Admins are bound by every rule not marked "admins only". Inside
 * {@link HealdanOperation.Kind#SEED} the actor counts as an admin (seeds run
 * without a user); otherwise the actor is the user of the operation's history
 * entry, and no user is no admin.
 * <p>
 * Existing folders are placed where they are stored, not where the folder
 * object being stored says (see {@link StoredRows}); new places, such as the
 * copies a folder copy would create, are worked out from their parents.
 */
@Component
public class FilingRules {

	static final String DOCUMENT_OUTSIDE = "Documents must be filed in a folder inside a %s.";

	static final String DOCUMENT_TOP_LEVEL = "Documents can't be saved at the top level of a %s. Choose one of its folders.";

	static final String DOCUMENT_IN_SEARCH_FOLDER = "Search folders can't hold documents.";

	static final String SHORTCUT = "Shortcuts aren't allowed inside Stores.";

	static final String STORE_SHORTCUT = "A Store can't become a shortcut.";

	static final String ALIAS_MERGE = "A folder alias can't be merged, or merged into.";

	static final String FOLDER_IN_SEARCH_FOLDER = "Search folders can't hold folders.";

	static final String FOLDER_IN_STORE = "Folders can't be created directly in a Store. Use New %s.";

	static final String TOP_LEVEL = "Only administrators can add, rename, move or delete a %s's top-level folders.";

	static final String WORKSPACE_RENAME = "Rename a %1$s with Edit %1$s.";

	static final String WORKSPACE_DELETE_MOVE = "Only administrators can delete a %s or move it to another Store.";

	static final String STORE_TYPE = "Only administrators can create Stores.";

	static final String SEARCH_FOLDER_TYPE = "Search folders can only be created with New search folder.";

	static final String SEARCH_FOLDER_PLACE = "A search folder can only be inside a %s or a workspace template.";

	static final String TEMPLATES = "Only administrators can change workspace templates.";

	static final String RESTORE_PERMISSION = "You need the delete permission on this folder to restore it.";

	static final String RESTORE_DOCUMENT_PERMISSION = "You need the delete permission on this document to restore it.";

	static final String RESTORE_NOT_IN_TRASH = "Only a document in the trash can be restored.";

	static final String RESTORE_TARGET_PERMISSION = "You need the write permission on the folder to restore a document into it.";

	static final String RESTORE_TARGET = "You need the add permission on a folder to restore a folder into it.";

	/** Key of what the current transaction found of who is an admin */
	private static final String ADMINS = FilingRules.class.getName() + ".admins";

	private final MatterLocator locator;

	private final HealdanSettings settings;

	private final CacheEvictor evictor;

	private final StoredRows rows;

	@Autowired
	public FilingRules(MatterLocator locator, HealdanSettings settings, CacheEvictor evictor, StoredRows rows) {
		this.locator = locator;
		this.settings = settings;
		this.evictor = evictor;
		this.rows = rows;
	}

	/**
	 * @return the bean
	 */
	public static FilingRules get() {
		return Context.get(FilingRules.class);
	}

	/**
	 * @param transaction the history entry of an operation, possibly null
	 * @return who performs the operation, null if nobody is named
	 */
	public static User actor(History transaction) {
		return transaction != null ? transaction.getUser() : null;
	}

	/**
	 * @param user the actor, possibly null
	 * @return true if the actor counts as an admin: an admin, or anyone inside
	 *         a seed
	 * @throws PersistenceException error at data layer
	 */
	public boolean isAdmin(User user) throws PersistenceException {
		if (HealdanOperation.active(HealdanOperation.Kind.SEED))
			return true;
		if (user == null)
			return false;
		// A user's groups are transient: empty until the DAO initializes the user
		if (user.getId() != 0L) {
			Map<Long, Boolean> known = admins();
			Boolean admin = known != null ? known.get(user.getId()) : null;
			if (admin != null)
				return admin;
			User loaded = UserDAO.get().findById(user.getId(), true);
			if (loaded != null) {
				if (known != null)
					known.put(user.getId(), loaded.isAdmin());
				return loaded.isAdmin();
			}
		}
		return user.isAdmin();
	}

	/**
	 * @return whether each user is an admin, as found so far in the current
	 *         transaction, whose checks each ask again; null outside a
	 *         transaction
	 */
	@SuppressWarnings("unchecked")
	private static Map<Long, Boolean> admins() {
		if (!TransactionSynchronizationManager.isSynchronizationActive())
			return null;
		Map<Long, Boolean> admins = (Map<Long, Boolean>) TransactionSynchronizationManager.getResource(ADMINS);
		if (admins == null) {
			admins = new HashMap<>();
			TransactionSynchronizationManager.bindResource(ADMINS, admins);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					TransactionSynchronizationManager.unbindResourceIfPossible(ADMINS);
				}
			});
		}
		return admins;
	}

	/**
	 * A document is created, moved, copied or restored into the folder
	 *
	 * @param target the folder
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkDocumentTarget(Folder target) throws PersistenceException {
		Folder folder = resolve(target);
		checkDocumentAt(locator.locate(folder), ids(target, folder));
	}

	/**
	 * A shortcut is created, moved, copied or restored into the folder
	 *
	 * @param target the folder
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkShortcut(Folder target) throws PersistenceException {
		Folder folder = resolve(target);
		checkShortcutAt(locator.locate(folder), ids(target, folder));
	}

	/**
	 * A folder is created
	 *
	 * @param parent where
	 * @param vo the new folder
	 * @param user who creates it
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkFolderCreate(Folder parent, Folder vo, User user) throws PersistenceException {
		Folder into = resolve(parent);
		checkCreateAt(locator.locate(into), vo.getType(), isAlias(vo), isAdmin(user), false, ids(parent, into));
	}

	/**
	 * A folder becomes an alias of another, where it is. Neither a Store nor
	 * the Templates Store may, whoever asks: under the root the shortcut rule
	 * doesn't reach them, and SOAP's {@code update} sets the alias reference
	 * it is given (plan, R34).
	 *
	 * @param folder the folder
	 * @param parent its parent
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkFolderAlias(Folder folder, Folder parent) throws PersistenceException {
		Kind kind = currentPosition(folder.getId()).kind();
		if (kind == Kind.STORE || kind == Kind.TEMPLATES_STORE)
			throw refuse(STORE_SHORTCUT, ids(folder.getId()));
		if (noShortcuts(locator.locate(parent)))
			throw refuse(SHORTCUT, ids(parent));
	}

	/**
	 * A folder moves, with everything in it
	 *
	 * @param folder the folder
	 * @param newParent where to
	 * @param user who moves it
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkFolderMove(Folder folder, Folder newParent, User user) throws PersistenceException {
		Folder into = resolve(newParent);
		FolderRow row = rows.folder(folder.getId());
		Collection<Long> ids = ids(folder.getId(), row != null ? row.parentId() : null,
				into != null ? into.getId() : null);
		FolderPosition parent = locator.locate(into);
		checkMoveAt(currentPosition(folder.getId(), row), parent, isAlias(folder), isAdmin(user), ids);
		checkSearchFolderAt(parent, folder.getType(), ids);
		checkPlacement(folder.getId(), childOf(parent, folder.getId(), folder.getType()), false, ids);
	}

	/**
	 * A folder is renamed
	 *
	 * @param folder the folder
	 * @param user who renames it
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkFolderRename(Folder folder, User user) throws PersistenceException {
		FolderPosition current = currentPosition(folder.getId());
		Collection<Long> ids = ids(folder.getId());
		if (current.kind() == Kind.MATTER_WORKSPACE && !HealdanOperation.active(HealdanOperation.Kind.MATTER_EDIT))
			throw refuse(label(WORKSPACE_RENAME), ids);
		if (current.isMatterTopLevel() || inTemplates(current)) {
			boolean admin = isAdmin(user);
			if (current.isMatterTopLevel() && !topLevelAllowed(admin))
				throw refuse(label(TOP_LEVEL), ids);
			if (inTemplates(current) && !admin)
				throw refuse(TEMPLATES, ids);
		}
	}

	/**
	 * A folder's own settings change, such as a search folder's rule. In the
	 * templates that is for admins, as every new matter copies them.
	 *
	 * @param folderId the folder
	 * @param user who changes them
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkFolderEdit(long folderId, User user) throws PersistenceException {
		if (inTemplates(currentPosition(folderId)) && !isAdmin(user))
			throw refuse(TEMPLATES, ids(folderId));
	}

	/**
	 * A folder is deleted, with everything in it
	 *
	 * @param folder the folder
	 * @param user who deletes it
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkFolderDelete(Folder folder, User user) throws PersistenceException {
		checkDeleteAt(currentPosition(folder.getId()), isAdmin(user), ids(folder.getId()));
	}

	/**
	 * A deleted folder is restored, with everything deleted in it
	 *
	 * @param folderId the folder
	 * @param parentId the requested parent (CE puts a Store back under the
	 *        root whatever is asked)
	 * @param user who restores it
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkFolderRestore(long folderId, long parentId, User user) throws PersistenceException {
		FolderRow row = rows.folder(folderId);
		if (row == null)
			return;
		// CE restores under the folder an alias points to
		Folder into = row.type() == Folder.TYPE_WORKSPACE ? FolderDAO.get().findById(Folder.ROOTID)
				: resolve(FolderDAO.get().findById(parentId));
		Collection<Long> ids = ids(folderId, parentId, into != null ? into.getId() : null);
		boolean admin = isAdmin(user);
		if (!admin && (user == null || !rows.mayDelete(folderId, user.getId())))
			throw refuse(RESTORE_PERMISSION, ids);

		FolderPosition parent = locator.locate(into);
		// CE doesn't check that the folder is deleted: restoring a live one moves it
		if (row.deleted() == 0)
			checkMoveAt(currentPosition(folderId, row), parent, row.alias(), admin, ids);
		if (locator.locate(row.parentId()).kind() == Kind.STORE) {
			// A matter's workspace goes back into a Store, by an admin
			if (!admin || parent.kind() != Kind.STORE)
				throw refuse(label(WORKSPACE_DELETE_MOVE), ids);
		} else {
			checkCreateAt(parent, row.type(), row.alias(), admin, true, ids);
		}
		checkPlacement(folderId, childOf(parent, folderId, row.type()), true, ids);
		// Anywhere but back where it was, the folder is added to a parent: ADD there, as creating one asks
		if (!admin && into != null && into.getId() != row.parentId()
				&& !FolderDAO.get().isPermissionAllowed(Permission.ADD, into.getId(), user.getId()))
			throw refuse(RESTORE_TARGET, ids);
	}

	/**
	 * A deleted document is restored: the user needs DELETE on it, as on a
	 * folder they restore (R22). CE's own check sees nothing on a deleted
	 * document. CE doesn't check that the document is in the trash: restoring a
	 * live one would move it with no MOVE asked, and a purged one would undo
	 * the purge, so only an admin may. Restored anywhere but the folder it was
	 * deleted from, it is filed there anew, which needs WRITE on that folder, as
	 * for adding a document and as CE's SOAP restore asks.
	 *
	 * @param documentId the document
	 * @param folderId the folder it is restored into
	 * @param user who restores it
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkDocumentRestore(long documentId, long folderId, User user) throws PersistenceException {
		if (isAdmin(user))
			return;
		if (user == null || !rows.mayDeleteDocument(documentId, user.getId()))
			throw refuse(RESTORE_DOCUMENT_PERMISSION, ids(folderId));
		if (!Integer.valueOf(PersistentObject.DELETED_CODE_DEFAULT).equals(rows.documentDeleted(documentId)))
			throw refuse(RESTORE_NOT_IN_TRASH, ids(folderId));
		if (!Long.valueOf(folderId).equals(rows.documentFolder(documentId))
				&& !FolderDAO.get().isPermissionAllowed(Permission.WRITE, folderId, user.getId()))
			throw refuse(RESTORE_TARGET_PERMISSION, ids(folderId));
	}

	/**
	 * A folder's type changes
	 *
	 * @param storedType the type it has
	 * @param incoming the folder with its new type
	 * @param user who changes it
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkTypeChange(int storedType, Folder incoming, User user) throws PersistenceException {
		int type = incoming.getType();
		if (type == storedType)
			return;
		FolderPosition parent = locator.locate(incoming.getParentId());
		Collection<Long> ids = ids(incoming.getId(), incoming.getParentId());
		if ((storedType == Folder.TYPE_WORKSPACE || type == Folder.TYPE_WORKSPACE)
				&& (!isAdmin(user) || parent.kind() != Kind.ROOT))
			throw refuse(STORE_TYPE, ids);
		if ((storedType == HealdanConstants.FOLDER_TYPE_SEARCH || type == HealdanConstants.FOLDER_TYPE_SEARCH)
				&& !searchFolderService())
			throw refuse(SEARCH_FOLDER_TYPE, ids);
		checkSearchFolderAt(parent, type, ids);
		if (type == Folder.TYPE_ALIAS && noShortcuts(parent))
			throw refuse(SHORTCUT, ids);
	}

	/**
	 * A folder is copied as CE's {@code FolderDAO.copy} does it: the copy and
	 * each name on its path are found or created under the target, then each
	 * subfolder is copied the same way (as a plain folder that keeps an alias's
	 * reference), and unless only folders are copied each folder's documents
	 * are created in its copy. Refuses what any of those steps would be refused.
	 *
	 * @param source the folder to copy
	 * @param target where to
	 * @param newName the copy's name, null for the source's
	 * @param foldersOnly true if no documents are copied
	 * @param user who copies
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkFolderCopy(Folder source, Folder target, String newName, boolean foldersOnly, User user)
			throws PersistenceException {
		if (source == null || target == null)
			return;
		Folder into = resolve(target);
		Collection<Long> ids = ids(source.getId(), target.getId(), into.getId());
		boolean admin = isAdmin(user);

		// The copies would be plain folders posing as search folders
		if (!searchFolderService() && rows.treeHasType(source.getId(), HealdanConstants.FOLDER_TYPE_SEARCH))
			throw refuse(SEARCH_FOLDER_TYPE, ids);

		Place parent = Place.real(into.getId(), locator.locate(into));
		Place copy = parent;
		StringTokenizer path = new StringTokenizer(StringUtils.isNotEmpty(newName) ? newName : source.getName(), "/",
				false);
		while (path.hasMoreTokens()) {
			parent = copy;
			copy = copyPlace(parent, path.nextToken(), admin, ids);
		}
		if (source.getFoldRef() != null && noShortcuts(parent.position()))
			throw refuse(SHORTCUT, ids);
		checkCopyContent(source.getId(), copy, foldersOnly, admin, ids);
	}

	/**
	 * A folder is merged into another as CE's {@code FolderDAO.merge} does it:
	 * its documents move into the target; each of its subfolders moves into the
	 * target with everything below it, unless the target already has a folder
	 * of that name, into which it is then merged the same way; the emptied
	 * folders are deleted. A subfolder may instead meet a sibling of its name
	 * that moved there first, depending on the order CE meets them in, so this
	 * refuses whatever either outcome would be refused.
	 *
	 * @param source the folder to merge
	 * @param target where to
	 * @param user who merges
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkFolderMerge(Folder source, Folder target, User user) throws PersistenceException {
		if (source == null || target == null)
			return;
		/*
		 * Documents and folders moved into an alias land in the folder it points
		 * to, but CE looks for clashing names among the alias's own children
		 */
		Folder into = resolve(target);
		Collection<Long> ids = ids(source.getId(), target.getId(), into.getId());
		// CE merges an alias as the folder it points to, whose tree nothing here reads
		if (source.getType() == Folder.TYPE_ALIAS || source.getFoldRef() != null)
			throw refuse(ALIAS_MERGE, ids);
		boolean admin = isAdmin(user);
		// The source's tree is read once: the checks below walk parts of it many times
		Subtree tree = new Subtree(source.getId());
		checkMergeInto(tree, source.getId(), Place.real(target.getId(), locator.locate(into)), admin, ids,
				new HashSet<>());
		checkDeleteAt(tree.position(source.getId()), admin, ids);
	}

	private void checkMergeInto(Subtree tree, long sourceId, Place place, boolean admin, Collection<Long> ids,
			Set<String> checked) throws PersistenceException {
		if (!checked.add(sourceId + ">" + place.key()))
			return;
		checkContentAt(tree.contents(sourceId), place.position(), ids);

		List<FolderRow> subfolders = tree.children(List.of(sourceId));
		Map<String, List<FolderRow>> byName = subfolders.stream().collect(Collectors.groupingBy(FolderRow::name));
		for (FolderRow folder : subfolders) {
			// A moved folder keeps its children, so its namesakes are read where they are stored too
			FolderRow namesake = rows.liveChild(place.id(), folder.name(), true);
			if (namesake != null) {
				// Merging an alias, or into one, would reach the folder it points to
				if (folder.alias() || namesake.alias())
					throw refuse(ALIAS_MERGE, ids);
				Place into = namesakePlace(place, namesake);
				checkMergeInto(tree, folder.id(), into, admin, ids, checked);
				// ...also after a sibling of its name has merged there, bringing its subfolders
				for (FolderRow sibling : byName.get(folder.name()))
					if (sibling.id() != folder.id())
						checkMergeInto(tree, folder.id(), Place.moved(sibling.id(), into.position(), into.key()),
								admin, ids, checked);
				checkDeleteAt(tree.position(folder.id()), admin, ids);
				continue;
			}

			// Moved into the target with what it holds...
			FolderPosition current = tree.position(folder.id());
			checkMoveAt(current, place.position(), folder.alias(), admin, ids);
			checkSearchFolderAt(place.position(), folder.type(), ids);
			checkPlacement(tree, folder.id(), childOf(place.position(), folder.id(), folder.type()), ids);

			// ...or merged into a sibling of its name that moved there first
			for (FolderRow sibling : byName.get(folder.name()))
				if (sibling.id() != folder.id()) {
					if (folder.alias() || sibling.alias())
						throw refuse(ALIAS_MERGE, ids);
					checkMergeInto(tree, folder.id(),
							Place.moved(sibling.id(), childOf(place.position(), sibling.id(), sibling.type()),
									place.key()),
							admin, ids, checked);
					checkDeleteAt(current, admin, ids);
				}
		}
	}

	/**
	 * @return the namesake to merge into: where it is stored, or, below a
	 *         folder of the merged tree, where it moves with that folder
	 */
	private Place namesakePlace(Place place, FolderRow namesake) throws PersistenceException {
		if (place.moved())
			return Place.moved(namesake.id(), childOf(place.position(), namesake.id(), namesake.type()), place.key());
		return Place.real(namesake.id(), locator.locate(namesake.id()));
	}

	/**
	 * {@link #checkPlacement(long, FolderPosition, boolean, Collection)} for a
	 * part of a tree already read, live folders and documents only
	 */
	private void checkPlacement(Subtree tree, long rootId, FolderPosition rootPosition, Collection<Long> ids)
			throws HealdanRuleException {
		Map<Long, FolderPosition> level = Map.of(rootId, rootPosition);
		while (!level.isEmpty()) {
			for (Map.Entry<Long, FolderPosition> folder : level.entrySet())
				checkContentAt(tree.contents(folder.getKey()), folder.getValue(), ids);

			Map<Long, FolderPosition> next = new LinkedHashMap<>();
			for (FolderRow child : tree.children(level.keySet())) {
				FolderPosition parent = level.get(child.parentId());
				if (child.alias() && noShortcuts(parent))
					throw refuse(SHORTCUT, ids);
				checkSearchFolderAt(parent, child.type(), ids);
				next.put(child.id(), childOf(parent, child.id(), child.type()));
			}
			level = next;
		}
	}

	/**
	 * @return where a copy named so lands under the parent: the live child of
	 *         that name, or a new folder, which must be allowed there
	 */
	private Place copyPlace(Place parent, String name, boolean admin, Collection<Long> ids)
			throws PersistenceException {
		if (parent.id() != null) {
			FolderRow existing = rows.liveChild(parent.id(), name, false);
			if (existing != null)
				return Place.real(existing.id(), locator.locate(existing.id()));
		}
		// CE's createPath makes a workspace under the root, a plain folder elsewhere
		int type = parent.position().kind() == Kind.ROOT ? Folder.TYPE_WORKSPACE : Folder.TYPE_DEFAULT;
		checkCreateAt(parent.position(), type, false, admin, false, ids);
		return Place.virtual(childOf(parent.position(), 0L, type), null);
	}

	private void checkCopyContent(long sourceId, Place copy, boolean foldersOnly, boolean admin,
			Collection<Long> ids) throws PersistenceException {
		if (!foldersOnly)
			checkContentAt(rows.contents(sourceId), copy.position(), ids);
		for (FolderRow child : rows.children(sourceId)) {
			Place childCopy = copyPlace(copy, child.name(), admin, ids);
			if (child.foldRef() != null && noShortcuts(copy.position()))
				throw refuse(SHORTCUT, ids);
			checkCopyContent(child.id(), childCopy, foldersOnly, admin, ids);
		}
	}

	/**
	 * Checks that the documents and aliases in a tree may be where the tree
	 * lands
	 *
	 * @param rootId the root of the tree
	 * @param rootPosition where the root lands
	 * @param withDeleted true for a restore, which brings back the folders and
	 *        documents deleted with code 1
	 */
	private void checkPlacement(long rootId, FolderPosition rootPosition, boolean withDeleted, Collection<Long> ids)
			throws PersistenceException {
		Map<Long, FolderPosition> level = Map.of(rootId, rootPosition);
		Set<Long> seen = new HashSet<>(level.keySet());
		while (!level.isEmpty()) {
			for (Map.Entry<Long, Contents> folder : rows.contents(level.keySet(), withDeleted).entrySet())
				checkContentAt(folder.getValue(), level.get(folder.getKey()), ids);

			Map<Long, FolderPosition> next = new LinkedHashMap<>();
			for (FolderRow child : rows.children(level.keySet(), withDeleted)) {
				if (!seen.add(child.id()))
					continue;
				FolderPosition parent = level.get(child.parentId());
				if (child.alias() && noShortcuts(parent))
					throw refuse(SHORTCUT, ids);
				checkSearchFolderAt(parent, child.type(), ids);
				next.put(child.id(), childOf(parent, child.id(), child.type()));
			}
			level = next;
		}
	}

	private void checkContentAt(Contents contents, FolderPosition position, Collection<Long> ids)
			throws HealdanRuleException {
		if (contents.documents() > 0)
			checkDocumentAt(position, ids);
		if (contents.shortcuts() > 0)
			checkShortcutAt(position, ids);
	}

	private void checkDocumentAt(FolderPosition position, Collection<Long> ids) throws HealdanRuleException {
		if (position.searchFolder())
			throw refuse(DOCUMENT_IN_SEARCH_FOLDER, ids);
		if (position.kind() == Kind.MATTER_WORKSPACE)
			throw refuse(label(DOCUMENT_TOP_LEVEL), ids);
		if (!position.isInsideMatter())
			throw refuse(label(DOCUMENT_OUTSIDE), ids);
	}

	private void checkShortcutAt(FolderPosition position, Collection<Long> ids) throws HealdanRuleException {
		if (noShortcuts(position))
			throw refuse(SHORTCUT, ids);
	}

	/**
	 * A folder of the given type lands under the parent, by whatever route:
	 * a search folder may only be in a matter or below a template. Directly in
	 * the Templates Store it would be a template itself.
	 */
	private void checkSearchFolderAt(FolderPosition parent, int type, Collection<Long> ids)
			throws HealdanRuleException {
		if (type == HealdanConstants.FOLDER_TYPE_SEARCH
				&& !(parent.isInsideMatter() || parent.kind() == Kind.IN_TEMPLATES))
			throw refuse(label(SEARCH_FOLDER_PLACE), ids);
	}

	/**
	 * @param restore true if the folder comes back from the trash, which
	 *        doesn't create its type anew
	 */
	private void checkCreateAt(FolderPosition parent, int type, boolean alias, boolean admin, boolean restore,
			Collection<Long> ids) throws HealdanRuleException {
		if (parent.searchFolder())
			throw refuse(FOLDER_IN_SEARCH_FOLDER, ids);
		if (type == Folder.TYPE_WORKSPACE && !(admin && parent.kind() == Kind.ROOT))
			throw refuse(STORE_TYPE, ids);
		if (!restore && type == HealdanConstants.FOLDER_TYPE_SEARCH && !searchFolderService())
			throw refuse(SEARCH_FOLDER_TYPE, ids);
		checkSearchFolderAt(parent, type, ids);
		if (alias && noShortcuts(parent))
			throw refuse(SHORTCUT, ids);
		if (parent.kind() == Kind.STORE && !HealdanOperation.active(HealdanOperation.Kind.MATTER_CREATE))
			throw refuse(label(FOLDER_IN_STORE), ids);
		if (parent.kind() == Kind.MATTER_WORKSPACE && !topLevelAllowed(admin))
			throw refuse(label(TOP_LEVEL), ids);
		if (inTemplates(parent) && !admin)
			throw refuse(TEMPLATES, ids);
	}

	private void checkMoveAt(FolderPosition current, FolderPosition parent, boolean alias, boolean admin,
			Collection<Long> ids) throws HealdanRuleException {
		if (parent.searchFolder())
			throw refuse(FOLDER_IN_SEARCH_FOLDER, ids);
		if (current.kind() == Kind.MATTER_WORKSPACE) {
			// Its new parent must be a Store
			if (!admin || parent.kind() != Kind.STORE)
				throw refuse(label(WORKSPACE_DELETE_MOVE), ids);
		} else {
			// Moved into a Store, it would become a matter's workspace
			if (parent.kind() == Kind.STORE && !HealdanOperation.active(HealdanOperation.Kind.MATTER_CREATE))
				throw refuse(label(FOLDER_IN_STORE), ids);
			if ((current.isMatterTopLevel() || parent.kind() == Kind.MATTER_WORKSPACE) && !topLevelAllowed(admin))
				throw refuse(label(TOP_LEVEL), ids);
		}
		if ((inTemplates(current) || inTemplates(parent)) && !admin)
			throw refuse(TEMPLATES, ids);
		if (alias && noShortcuts(parent))
			throw refuse(SHORTCUT, ids);
	}

	private void checkDeleteAt(FolderPosition current, boolean admin, Collection<Long> ids)
			throws HealdanRuleException {
		// Deleting a Store deletes its matters
		if ((current.kind() == Kind.MATTER_WORKSPACE || current.kind() == Kind.STORE) && !admin)
			throw refuse(label(WORKSPACE_DELETE_MOVE), ids);
		if (current.isMatterTopLevel() && !topLevelAllowed(admin))
			throw refuse(label(TOP_LEVEL), ids);
		if (inTemplates(current) && !admin)
			throw refuse(TEMPLATES, ids);
	}

	/**
	 * @return where an existing folder is stored, not where an unsaved change
	 *         puts it
	 */
	private FolderPosition currentPosition(long folderId) throws PersistenceException {
		return currentPosition(folderId, rows.folder(folderId));
	}

	private FolderPosition currentPosition(long folderId, FolderRow row) throws PersistenceException {
		if (row == null || row.parentId() == folderId)
			return locator.locate(folderId);
		return childOf(locator.locate(row.parentId()), folderId, row.type());
	}

	/**
	 * Where a folder of the given type sits directly under a parent, as
	 * {@link MatterLocator} would place it. Positions worked out this way carry
	 * no matter.
	 *
	 * @param parent where the parent sits
	 * @param childId the folder, 0 for one not created yet
	 * @param childType its type
	 * @return where the folder sits
	 */
	FolderPosition childOf(FolderPosition parent, long childId, int childType) {
		boolean search = childType == HealdanConstants.FOLDER_TYPE_SEARCH;
		Long child = childId != 0L ? childId : null;
		switch (parent.kind()) {
		case ROOT:
			if (child != null && childId == Folder.DEFAULTWORKSPACEID)
				return new FolderPosition(Kind.DEFAULT_WORKSPACE, null, null, null, -1, search);
			if (child != null && child.equals(settings.templatesStoreId()))
				return new FolderPosition(Kind.TEMPLATES_STORE, null, null, null, -1, search);
			if (childType == Folder.TYPE_WORKSPACE)
				return new FolderPosition(Kind.STORE, child, null, null, -1, search);
			return new FolderPosition(Kind.OUTSIDE, null, null, null, -1, search);
		case STORE:
			return new FolderPosition(Kind.MATTER_WORKSPACE, parent.storeId(), null, child, 0, search);
		case MATTER_WORKSPACE, IN_MATTER:
			return new FolderPosition(Kind.IN_MATTER, parent.storeId(), parent.matter(), parent.workspaceId(),
					parent.depth() + 1, search);
		case TEMPLATES_STORE, IN_TEMPLATES:
			return new FolderPosition(Kind.IN_TEMPLATES, null, null, null, -1, search);
		default:
			return new FolderPosition(Kind.OUTSIDE, null, null, null, -1, search);
		}
	}

	private static boolean noShortcuts(FolderPosition position) {
		return position.isUnderAStore() || inTemplates(position);
	}

	private static boolean inTemplates(FolderPosition position) {
		return position.kind() == Kind.TEMPLATES_STORE || position.kind() == Kind.IN_TEMPLATES;
	}

	private static boolean topLevelAllowed(boolean admin) {
		return admin || HealdanOperation.active(HealdanOperation.Kind.MATTER_CREATE);
	}

	private static boolean searchFolderService() {
		return HealdanOperation.active(HealdanOperation.Kind.SEARCH_FOLDER)
				|| HealdanOperation.active(HealdanOperation.Kind.MATTER_CREATE);
	}

	static boolean isAlias(Folder folder) {
		return folder.getType() == Folder.TYPE_ALIAS || folder.getFoldRef() != null;
	}

	/**
	 * @return the folder an alias points to, as CE resolves a parent or target;
	 *         the folder itself otherwise
	 */
	private static Folder resolve(Folder folder) throws PersistenceException {
		if (folder != null && folder.getFoldRef() != null) {
			Folder referenced = FolderDAO.get().findById(folder.getFoldRef());
			if (referenced != null)
				return referenced;
		}
		return folder;
	}

	private String label(String message) {
		return message.formatted(settings.matterLabel().toLowerCase(Locale.ROOT));
	}

	private HealdanRuleException refuse(String message, Collection<Long> folderIds) {
		evictor.evictFoldersOnRollback(folderIds);
		return new HealdanRuleException(message);
	}

	private static Collection<Long> ids(Folder... folders) {
		return Vetoes.ids(Arrays.stream(folders).map(f -> f != null ? f.getId() : null).toArray(Long[]::new));
	}

	private static Collection<Long> ids(Long... ids) {
		return Vetoes.ids(ids);
	}

	/**
	 * A folder and every live folder below it, with their live documents and
	 * where each folder is stored, read with one query a level
	 */
	private final class Subtree {

		private final Map<Long, List<FolderRow>> children = new HashMap<>();

		private final Map<Long, FolderPosition> positions = new HashMap<>();

		private final Map<Long, Contents> contents;

		Subtree(long rootId) throws PersistenceException {
			positions.put(rootId, currentPosition(rootId));
			List<Long> all = new ArrayList<>(List.of(rootId));
			Set<Long> seen = new HashSet<>(all);
			List<Long> level = List.of(rootId);
			while (!level.isEmpty()) {
				List<Long> next = new ArrayList<>();
				for (FolderRow child : rows.children(level, false))
					if (seen.add(child.id())) {
						children.computeIfAbsent(child.parentId(), id -> new ArrayList<>()).add(child);
						// As currentPosition places it: under its stored parent
						positions.put(child.id(), childOf(positions.get(child.parentId()), child.id(), child.type()));
						next.add(child.id());
					}
				all.addAll(next);
				level = next;
			}
			contents = rows.contents(all, false);
		}

		/**
		 * @return where the folder is stored
		 */
		FolderPosition position(long folderId) {
			return positions.get(folderId);
		}

		/**
		 * @return the folder's live documents
		 */
		Contents contents(long folderId) {
			return contents.getOrDefault(folderId, Contents.NONE);
		}

		/**
		 * @return the children of the folders, by id, as
		 *         {@link StoredRows#children(Collection, boolean)} lists them
		 */
		List<FolderRow> children(Collection<Long> parentIds) {
			List<FolderRow> found = new ArrayList<>();
			for (Long parentId : parentIds)
				found.addAll(children.getOrDefault(parentId, List.of()));
			found.sort(Comparator.comparingLong(FolderRow::id));
			return found;
		}

	}

	/**
	 * A folder a copy or merge puts things in
	 *
	 * @param id the folder, null if it would be created
	 * @param position where it sits, or will once moved
	 * @param key tells places apart
	 * @param moved true for a folder of the merged tree, which moves there
	 */
	private record Place(Long id, FolderPosition position, String key, boolean moved) {

		static Place real(long id, FolderPosition position) {
			return new Place(id, position, Long.toString(id), false);
		}

		static Place virtual(FolderPosition position, String key) {
			return new Place(null, position, "new " + key, false);
		}

		static Place moved(long id, FolderPosition position, String parentKey) {
			return new Place(id, position, "%d@%s".formatted(id, parentKey), true);
		}
	}
}
