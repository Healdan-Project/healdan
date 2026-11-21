// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.stream.LongStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.HealdanTreeChecks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.stores.StoreService;
import com.logicaldoc.healdan.admin.workspace.WorkspaceAdmin;
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.api.model.HeldDocument;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.api.model.TrashContents;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.trash.TrashItemRow;
import com.logicaldoc.healdan.trash.TrashMember;
import com.logicaldoc.healdan.trash.TrashRules;
import com.logicaldoc.healdan.trash.TrashState;
import com.logicaldoc.healdan.trash.TrashStore;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Deleting and moving a folder, and counting what it holds (folders and Trash
 * spec 6.1, 6.2, plan correction 6). The work is CE's own
 * {@code FolderDAO.deleteTree} and {@code FolderDAO.move}, so Healdan's hooks
 * and listeners decide as they do on every route; the checks here come first
 * only to give the refusals people meet codes of their own, in the spec's
 * order, and a veto of Healdan's rules after them is {@code 409 refused}, as
 * the ProblemMapper answers one. No refusal names or counts anything inside
 * the folder that the caller can't read (spec 4.2): {@code items-not-allowed}
 * names nothing, and a {@code checked-out} problem's {@code holds} lists only
 * the documents the caller can read.
 * <p>
 * The Admin app moves and deletes a matter's own folder through
 * {@link #moveMatter} and {@link #deleteMatter}: the same paths, with the same
 * locks and checks, but for the refusal of a matter's own folder, and with
 * this class's own refusals answered as {@code 409 refused}, with the same
 * sentence and no {@code holds} (Admin app plan, P2-R13); a workspace's
 * folders and templates through {@link #deleteWorkspaceFolder} and
 * {@link #deleteTemplate}, and a Store with its matters through
 * {@link #deleteStore}, likewise, each refusing a folder its route doesn't
 * delete, and the Admin app's renames and moves of them, and a Store's
 * create, rename and restore, hold this class's locks with
 * {@link #withFolderLocks}. Each of those five refuses anyone but an
 * administrator first ({@code 403 admins-only}), as their routes do before
 * them, and as every admin service does for itself.
 */
public final class FolderChanges {

	private static final Logger log = LoggerFactory.getLogger(FolderChanges.class);

	/** The refusal for a tree with something the caller may not delete, in the same words whatever it is */
	static final String DELETE_ITEMS = "It holds items you don't have permission to delete.";

	/** The refusal for a tree with something the caller may not move */
	static final String MOVE_ITEMS = "It holds items you don't have permission to move.";

	/** The refusal of a matter's move into a {@code storeId} that isn't a live Store */
	static final String NO_STORE = "There's no Store %d to move the matter into.";

	/** The refusal of anyone but an administrator by the Admin app's moves and deletes */
	static final String ADMINS_ONLY = "Only administrators can move or delete matters, Stores, workspace templates "
			+ "and their folders here.";

	/**
	 * {@link FolderRenames}' locks, one per parent folder: a move holds both
	 * parents', in id order, so that a rename and a move can't both give a
	 * name to two folders in one parent
	 */
	private final DocumentLocks parents;

	/**
	 * @param parents the locks renames take, by parent folder
	 */
	FolderChanges(DocumentLocks parents) {
		this.parents = parents;
	}

	/**
	 * Moves the folder and everything live in it to the caller's Trash, through
	 * CE's {@code deleteTree}. Checks, in order: a folder the caller can't
	 * read, outside the Stores (the Templates Store too), an alias, or already
	 * deleted ({@code 404}); a Store or a matter's own folder
	 * ({@code 409 not-deletable}, admins too); no Delete on the folder
	 * ({@code 403 forbidden}); directly in a matter's workspace, unless the
	 * caller is an administrator ({@code 403 admins-only}); something inside
	 * without Delete ({@code 403 items-not-allowed}); a document inside checked
	 * out, by anyone ({@code 409 checked-out}); an immutable document inside
	 * ({@code 409 immutable}). The tree comes before its check-outs, as on CE's
	 * route, so a hidden item is refused before its check-out could show.
	 *
	 * @param id the folder's id
	 * @param caller the signed-in caller
	 * @return the Trash item the delete made
	 */
	TrashItem delete(long id, Caller caller) {
		User user = caller.user();
		String failure = "Can't delete folder #" + id;
		try {
			FolderView view = FolderResource.view(id, user);
			if ("store".equals(view.kind()) || "matter".equals(view.kind()))
				throw ApiProblem.conflict("not-deletable", "A Store or a matter's own folder can't be deleted here.");
			if (!view.canDelete()) {
				if (!FolderDAO.get().isPermissionAllowed(Permission.DELETE, id, user.getId()))
					throw ApiProblem.forbidden("You may not delete folder %d.".formatted(id));
				// With the Delete permission, the other reason is a top-level folder and a caller who isn't an
				// administrator
				throw new ApiProblem(403, "admins-only",
						"Only administrators can delete a matter's top-level folders.");
			}
			try {
				HealdanTreeChecks.deletedTrees(user, List.of(id));
			} catch (PermissionException e) {
				log.debug("Folder {} holds items the caller may not delete", id);
				throw new ApiProblem(403, "items-not-allowed", DELETE_ITEMS);
			}
			// The live tree is read once for both, and the hook reads it again inside the delete's transaction
			List<Long> tree = StoredRows.get().tree(id);
			refuseCheckedOut(tree, user, "deleted");
			return trashTree(id, tree, caller);
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * Moves a matter's own folder and everything live in it to the caller's
	 * Trash, as {@link #delete} does any other folder, for the Admin app. The
	 * matter's row stays, so its number stays taken. Checks, in order: a
	 * caller who isn't an administrator ({@code 403 admins-only}); a folder
	 * that isn't a matter's own, or is in the Trash already ({@code 404});
	 * something inside without Delete; a document inside
	 * checked out, by anyone, the caller too; an immutable document inside.
	 * Each of those is {@code 409 refused}, with the sentence {@link #delete}
	 * gives, as is a veto of Healdan's rules.
	 *
	 * @param id the matter's own folder
	 * @param caller the signed-in caller, an administrator
	 * @return the Trash item the delete made
	 */
	public TrashItem deleteMatter(long id, Caller caller) {
		try {
			requireAdmin(caller.user());
			matterFolder(id, caller.user());
			return deleteForAdmin(id, caller);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't delete matter folder #" + id, e);
		}
	}

	/**
	 * Moves a workspace's folder or search folder, below its top folder, and
	 * everything live in it, to the caller's Trash, as {@link #deleteMatter}
	 * does a matter's own folder, for the Admin app's workspace routes.
	 * Checks, in order: a caller who isn't an administrator
	 * ({@code 403 admins-only}); a folder that isn't a workspace's
	 * ({@code WorkspaceAdmin.workspaceFolder}), such as a Store, the Templates
	 * Store or the root ({@code 404}); the workspace's top folder, a matter's
	 * own or a template's, which goes with its matter or template; something
	 * inside without Delete; a document inside checked out, by anyone, the
	 * caller too; an immutable document inside. Each but the first two is
	 * {@code 409 refused}, with the sentence {@link #delete} gives, as is a
	 * veto of Healdan's rules.
	 *
	 * @param id the folder
	 * @param caller the signed-in caller, an administrator
	 * @return the Trash item the delete made
	 */
	public TrashItem deleteWorkspaceFolder(long id, Caller caller) {
		try {
			requireAdmin(caller.user());
			WorkspaceAdmin workspaces = WorkspaceAdmin.get();
			Folder folder = workspaces.workspaceFolder(id);
			if (folder == null)
				throw ApiProblem.notFound("There's no folder %d in a workspace.".formatted(id));
			try {
				workspaces.refuseTopDelete(folder);
			} catch (HealdanRuleException e) {
				throw ApiProblem.conflict("refused", e.getMessage());
			}
			return deleteForAdmin(id, caller);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't delete folder #" + id, e);
		}
	}

	/**
	 * Moves a workspace template's own folder, with its folders and search
	 * folders, to the caller's Trash, for the Admin app's template route.
	 * Checks, in order: a caller who isn't an administrator
	 * ({@code 403 admins-only}); a folder that isn't a template's own,
	 * directly in the Templates Store ({@code 404}); something inside without Delete; a
	 * document inside checked out, by anyone, the caller too; an immutable
	 * document inside. Each of those is {@code 409 refused}, with the sentence
	 * {@link #delete} gives, as is a veto of Healdan's rules.
	 *
	 * @param id the template's own folder
	 * @param caller the signed-in caller, an administrator
	 * @return the Trash item the delete made
	 */
	public TrashItem deleteTemplate(long id, Caller caller) {
		try {
			requireAdmin(caller.user());
			Folder folder = WorkspaceAdmin.get().workspaceFolder(id);
			if (folder == null || !Long.valueOf(folder.getParentId()).equals(HealdanSettings.get().templatesStoreId()))
				throw ApiProblem.notFound("There's no template %d.".formatted(id));
			return deleteForAdmin(id, caller);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't delete template #" + id, e);
		}
	}

	/**
	 * Moves a Store, every matter in it and everything live in them, to the
	 * caller's Trash as one item, as {@link #deleteMatter} does a matter's own
	 * folder, for the Admin app's Store route. The matters' rows stay, so their
	 * numbers stay taken. Checks, in order: a caller who isn't an
	 * administrator ({@code 403 admins-only}); LogicalDOC's Default workspace,
	 * the Templates Store and the root, which aren't Stores ({@code 409 refused},
	 * each with its sentence); any other folder that isn't a live Store
	 * ({@code 404}); something inside without Delete; a document inside
	 * checked out, by anyone, the caller too; an immutable document inside.
	 * Each of those but the {@code 403} and the {@code 404} is
	 * {@code 409 refused}, with the sentence {@link #delete} gives, as is a
	 * veto of Healdan's rules.
	 *
	 * @param id the Store
	 * @param caller the signed-in caller, an administrator
	 * @return the Trash item the delete made
	 */
	public TrashItem deleteStore(long id, Caller caller) {
		try {
			requireAdmin(caller.user());
			StoreService stores = StoreService.get();
			String fixed = stores.fixedFolder(id, "deleted");
			if (fixed != null)
				throw ApiProblem.conflict("refused", fixed);
			if (stores.store(id) == null)
				throw ApiProblem.notFound("There's no Store %d.".formatted(id));
			return deleteForAdmin(id, caller);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't delete Store #" + id, e);
		}
	}

	/**
	 * Refuses anyone but an administrator, first in each of the Admin app's
	 * moves and deletes
	 *
	 * @throws ApiProblem {@code 403 admins-only}
	 */
	private static void requireAdmin(User user) throws PersistenceException {
		if (!AccessChecks.isAdmin(user))
			throw new ApiProblem(403, "admins-only", ADMINS_ONLY);
	}

	/**
	 * {@link #deleteMatter}, {@link #deleteWorkspaceFolder},
	 * {@link #deleteTemplate} and {@link #deleteStore} once they know the
	 * folder: their checks, then the delete
	 */
	private static TrashItem deleteForAdmin(long id, Caller caller) throws PersistenceException {
		User user = caller.user();
		try {
			HealdanTreeChecks.deletedTrees(user, List.of(id));
		} catch (PermissionException e) {
			log.debug("Folder {} holds items the caller may not delete", id);
			throw ApiProblem.conflict("refused", DELETE_ITEMS);
		}
		List<Long> tree = StoredRows.get().tree(id);
		refused(() -> {
			refuseCheckedOut(tree, user, "deleted");
			return null;
		});
		return refused(() -> trashTree(id, tree, caller));
	}

	/**
	 * Runs work while holding the locks of these folders, as renames and moves
	 * hold their parents': the Admin app's workspace and template routes hold
	 * the folder a name goes into while they check and write it, so two
	 * changes can't give one name twice there. They're taken in id order, as a
	 * move takes both parents'.
	 *
	 * @param <T> what the work returns
	 * @param failure what failed, for the log
	 * @param work what to do
	 * @param folderIds the folders whose locks to hold
	 * @return what the work returned
	 */
	public <T> T withFolderLocks(String failure, Callable<T> work, long... folderIds) {
		return locked(LongStream.of(folderIds).distinct().sorted().toArray(), 0, failure, work);
	}

	private <T> T locked(long[] ids, int next, String failure, Callable<T> work) {
		if (next < ids.length)
			return parents.withLock(ids[next], failure, () -> locked(ids, next + 1, failure, work));
		try {
			return work.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * Refuses a tree that holds an immutable document, then moves it to the
	 * caller's Trash through CE's {@code deleteTree}: what {@link #delete} and
	 * {@link #deleteMatter} do once their checks have passed
	 *
	 * @param id the folder at the top of the tree
	 * @param tree the folder and the live folders below it
	 * @return the Trash item the delete made
	 */
	private static TrashItem trashTree(long id, List<Long> tree, Caller caller) throws PersistenceException {
		if (TrashRules.get().holdsImmutable(tree))
			throw ApiProblem.conflict("immutable", "It holds an immutable document, which can't be deleted.");

		FolderHistory history = new FolderHistory();
		history.setSession(caller.session());
		history.setEvent(FolderEvent.DELETED);
		// A veto of Healdan's rules is answered 409 refused by the ProblemMapper
		List<Folder> kept = FolderDAO.get().deleteTree(id, PersistentObject.DELETED_CODE_DEFAULT, history);
		// CE stops at an immutable document, which Healdan's hook refuses first; one filed meanwhile is the same
		if (!kept.isEmpty())
			throw ApiProblem.conflict("immutable", "It holds an immutable document, which can't be deleted.");

		TrashStore store = TrashStore.get();
		TrashItemRow item = store.itemHolding(new TrashMember('F', id), TrashState.DELETED).flatMap(store::item)
				.orElseThrow(() -> new IllegalStateException("Folder %d was deleted with no Trash item".formatted(id)));
		return TrashViews.item(item, caller.user());
	}

	/**
	 * Moves the folder into another, with everything in it, through CE's
	 * {@code move}, holding both parents' locks. Checks, in order: no folder
	 * to move it into ({@code 400}); a folder, or a folder to move it into,
	 * that the caller can't read ({@code 404}, as {@code GET} answers); a Store
	 * or a matter's own folder ({@code 409 not-movable}); into a Store, a
	 * search folder, the folder itself or a folder inside it
	 * ({@code 409 filing-rule}); no Move on the folder, or no Add on the folder
	 * it goes into ({@code 403 forbidden}); a top-level folder, or into a
	 * matter's top level, unless the caller is an administrator
	 * ({@code 403 admins-only}); then, inside the locks, something inside
	 * without Move ({@code 403 items-not-allowed}); a document inside checked
	 * out ({@code 409 checked-out}); a name another folder there has that the
	 * caller can see, without regard to case ({@code 409 name-taken}); then the
	 * rules' own refusals, such as an email the matter holds already
	 * ({@code 409 already-filed}). Moving it into the folder it is in changes
	 * nothing, after the checks up to {@code admins-only}.
	 *
	 * @param id the folder's id
	 * @param folderId the folder to move it into, null if none was sent
	 * @param caller the signed-in caller
	 * @return the folder in its new place
	 */
	FolderView move(long id, Long folderId, Caller caller) {
		if (folderId == null)
			throw ApiProblem.badRequest("Say which folder to move the folder into with folderId.");
		User user = caller.user();
		String failure = "Can't move folder #" + id;
		try {
			FolderView view = FolderResource.view(id, user);
			FolderView target = FolderResource.view(folderId, user);
			refuseMove(view, target, user);
			long parentId = FolderDAO.get().findById(id).getParentId();
			if (parentId == folderId)
				return view;

			long low = Math.min(parentId, folderId);
			long high = Math.max(parentId, folderId);
			return parents.withLock(low, failure, () -> parents.withLock(high, failure, () -> {
				FolderDAO folders = FolderDAO.get();
				// Read again, as the rename does: another request may have changed either meanwhile, or taken them away
				// from the caller (404)
				FolderView now = FolderResource.view(id, user);
				FolderView into = FolderResource.view(folderId, user);
				Folder current = folders.findById(id, true);
				// Or moved it, out from under the lock of the parent it was in
				if (current.getParentId() != parentId)
					throw ApiProblem.conflict("refused",
							"Folder %d moved while it was being moved. Try again.".formatted(id));
				refuseMove(now, into, user);
				try {
					HealdanTreeChecks.movedTrees(user, List.of(id), folderId);
				} catch (PermissionException e) {
					// The Add on the folder it goes into was checked above, so this is what it holds
					log.debug("Folder {} holds items the caller may not move", id);
					throw new ApiProblem(403, "items-not-allowed", MOVE_ITEMS);
				}
				refuseCheckedOut(StoredRows.get().tree(id), user, "moved");
				if (FolderRenames.taken(folderId, id, current.getName(), user))
					throw ApiProblem.conflict("name-taken",
							"Another folder in folder %d is called %s.".formatted(folderId, current.getName()));

				FolderHistory history = new FolderHistory();
				history.setSession(caller.session());
				history.setEvent(FolderEvent.MOVED);
				// A veto of Healdan's rules is answered 409 refused, and the once-per-matter rule's 409
				// already-filed, by the ProblemMapper
				folders.move(current, folders.findById(folderId), history);
				return FolderResource.view(id, user);
			}));
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * Moves a matter's own folder, with everything in it, into another Store,
	 * through CE's {@code move}, holding both Stores' locks, as {@link #move}
	 * moves any other folder, for the Admin app. The folder keeps its name: a
	 * clash is refused, never settled with CE's {@code (1)}. Checks, in order:
	 * a caller who isn't an administrator ({@code 403 admins-only}); a folder
	 * that isn't a matter's own, or is in the Trash ({@code 404}); a
	 * {@code storeId} that isn't a live Store; then, inside the locks,
	 * something inside without Move; a document inside checked out, by anyone;
	 * a folder in the Store with the matter's folder's name, without regard to
	 * case, hidden ones too. Each of those is {@code 409 refused}, as is a veto
	 * of Healdan's rules, such as P2 for the new Store. The move and
	 * {@code moved} run in one transaction, so the administration record that
	 * {@code moved} writes stands or falls with the move. The documents' files
	 * follow to the new Store's storage backend by the listeners, in the
	 * background.
	 *
	 * @param id the matter's own folder
	 * @param storeId the Store to move it into
	 * @param caller the signed-in caller, an administrator
	 * @param moved what to do inside the move's transaction once the folder
	 *        has moved, given the Store it was in and the Store it's in now
	 * @return true if it moved; false if it is in that Store already, which
	 *         changes nothing
	 */
	public boolean moveMatter(long id, long storeId, Caller caller, BiConsumer<Folder, Folder> moved) {
		User user = caller.user();
		String failure = "Can't move matter folder #" + id;
		try {
			requireAdmin(user);
			matterFolder(id, user);
			if (MatterService.get().liveStore(storeId) == null)
				throw ApiProblem.conflict("refused", NO_STORE.formatted(storeId));
			long parentId = FolderDAO.get().findById(id).getParentId();
			if (parentId == storeId)
				return false;

			long low = Math.min(parentId, storeId);
			long high = Math.max(parentId, storeId);
			return parents.withLock(low, failure, () -> parents.withLock(high, failure, () -> {
				FolderDAO folders = FolderDAO.get();
				// Read again, as the folder move does: another request may have moved it, or the Store, meanwhile
				matterFolder(id, user);
				Folder current = folders.findById(id, true);
				if (current.getParentId() != parentId)
					throw ApiProblem.conflict("refused",
							"Folder %d moved while it was being moved. Try again.".formatted(id));
				Folder into = MatterService.get().liveStore(storeId);
				if (into == null)
					throw ApiProblem.conflict("refused", NO_STORE.formatted(storeId));
				try {
					HealdanTreeChecks.movedTrees(user, List.of(id), storeId);
				} catch (PermissionException e) {
					log.debug("Matter folder {} holds items the caller may not move", id);
					throw ApiProblem.conflict("refused", MOVE_ITEMS);
				}
				refused(() -> {
					refuseCheckedOut(StoredRows.get().tree(id), user, "moved");
					return null;
				});
				String name = current.getName();
				if (nameInStore(storeId, id, name))
					throw ApiProblem.conflict("refused",
							"The Store %s already has a folder called %s.".formatted(into.getName(), name));

				Folder from = folders.findById(parentId);
				new TransactionTemplate(Context.get(PlatformTransactionManager.class)).executeWithoutResult(status -> {
					FolderHistory history = new FolderHistory();
					history.setSession(caller.session());
					history.setEvent(FolderEvent.MOVED);
					// The cache isn't transactional: a rollback, such as a record that fails, mustn't leave the
					// folder cached in the new Store
					CacheEvictor.get().evictFoldersOnRollback(List.of(id));
					try {
						// A veto of Healdan's rules is answered 409 refused by the ProblemMapper, wrapped or not
						folders.move(current, into, history);
					} catch (PersistenceException e) {
						throw new IllegalStateException(failure, e);
					}
					// CE renames a moved folder whose name another has: one made meanwhile is a clash too
					if (!name.equals(current.getName()))
						throw ApiProblem.conflict("refused",
								"The Store %s already has a folder called %s.".formatted(into.getName(), name));
					moved.accept(from, into);
				});
				return true;
			}));
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * @throws ApiProblem {@code 404} unless the folder is a matter's own,
	 *         live, as {@code GET} reads it
	 */
	private static void matterFolder(long id, User user) throws PersistenceException {
		if (!"matter".equals(FolderResource.view(id, user).kind()))
			throw ApiProblem.notFound("No matter folder #" + id);
	}

	/**
	 * @return whether a live folder in the Store, other than the matter's own,
	 *         has the name, without regard to case: any, hidden or not, an
	 *         alias too, as CE's move would rename the matter's folder to
	 *         make way for any
	 */
	private static boolean nameInStore(long storeId, long exceptId, String name) {
		for (FolderRow row : StoredRows.get().children(storeId))
			if (row.id() != exceptId && name.equalsIgnoreCase(row.name()))
				return true;
		return false;
	}

	/** One of this class's checks, that throws a refusal of its own */
	@FunctionalInterface
	private interface Check<T> {
		T run() throws PersistenceException;
	}

	/**
	 * Runs one of this class's checks for the Admin app, which answers the
	 * check's own refusals ({@code checked-out}, {@code immutable}) as
	 * {@code 409 refused}, with the same sentence and no {@code holds}
	 */
	private static <T> T refused(Check<T> check) throws PersistenceException {
		try {
			return check.run();
		} catch (ApiProblem problem) {
			if (problem.status() == 409 && !"refused".equals(problem.code()))
				throw ApiProblem.conflict("refused", problem.detail());
			throw problem;
		}
	}

	/**
	 * @param id the folder's id
	 * @param caller the signed-in user, with its groups
	 * @return the live folders and documents in its tree that the caller can
	 *         read, the folder itself left out
	 */
	TrashContents contents(long id, User caller) {
		try {
			FolderResource.view(id, caller);
			return TrashViews.liveContents(id, caller);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't count folder #" + id, e);
		}
	}

	/**
	 * Refuses a move for what the folder and its new parent say, from
	 * {@code not-movable} to {@code admins-only}. A {@code forbidden} or an
	 * {@code admins-only} says which check it was in {@code cause}, as each
	 * code has two: {@code no-move} or {@code no-add}, and {@code top-level}
	 * or {@code into-top-level}
	 */
	private static void refuseMove(FolderView view, FolderView target, User user) throws PersistenceException {
		if ("store".equals(view.kind()) || "matter".equals(view.kind()))
			throw ApiProblem.conflict("not-movable", "A Store or a matter's own folder can't be moved here.");
		if ("store".equals(target.kind()))
			throw ApiProblem.conflict("filing-rule", "A Store holds matters, not folders. Move it into a matter.");
		if ("searchFolder".equals(target.kind()))
			throw ApiProblem.conflict("filing-rule", "A search folder can't hold folders.");
		if (target.id() == view.id() || FolderDAO.get().isInPath(view.id(), target.id()))
			throw ApiProblem.conflict("filing-rule", "A folder can't be moved into itself or a folder inside it.");

		FolderDAO folders = FolderDAO.get();
		if (!folders.isPermissionAllowed(Permission.MOVE, view.id(), user.getId()))
			throw ApiProblem.forbidden("You may not move folder %d.".formatted(view.id())).with("cause", "no-move");
		if (!folders.isPermissionAllowed(Permission.ADD, target.id(), user.getId()))
			throw ApiProblem.forbidden("You may not add folders to folder %d.".formatted(target.id())).with("cause",
					"no-add");
		// With the Move permission, the folder's other reason is a top-level folder and a caller who isn't an
		// administrator
		if (!view.canMove())
			throw new ApiProblem(403, "admins-only", "Only administrators can move a matter's top-level folders.")
					.with("cause", "top-level");
		if ("matter".equals(target.kind()) && !AccessChecks.isAdmin(user))
			throw new ApiProblem(403, "admins-only", "Only administrators can move folders into a matter's top level.")
					.with("cause", "into-top-level");
	}

	/**
	 * Refuses while a live document in the folder's tree is checked out, by
	 * anyone, naming those the caller can read, with who holds each and since
	 * when
	 *
	 * @param tree the folder and the live folders below it
	 * @param done what would be done to the folder, for the refusal's text
	 */
	private static void refuseCheckedOut(List<Long> tree, User user, String done) throws PersistenceException {
		List<Long> held = TrashRules.get().checkedOutIn(tree);
		if (held.isEmpty())
			return;
		List<HeldDocument> holds = new ArrayList<>();
		for (Long docId : held) {
			Optional<Document> document = AccessChecks.readableDocument(DocumentDAO.get().findById(docId), user);
			if (document.isEmpty() || !ApiViews.hasDocNumber(document.get()))
				continue;
			Document d = document.get();
			HoldView hold = ApiViews.hold(d);
			// One checked in since is no longer held
			if (hold != null)
				holds.add(new HeldDocument(Long.parseLong(d.getCustomId()), d.getFileName(),
						ApiViews.heldOn(hold, d, user)));
		}
		throw ApiProblem.conflict("checked-out",
				"Documents in it are checked out. It can be %s once they're checked in.".formatted(done))
				.with("holds", holds);
	}
}
