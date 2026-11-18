// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.api.model.HeldDocument;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.api.model.TrashContents;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.trash.TrashItemRow;
import com.logicaldoc.healdan.trash.TrashMember;
import com.logicaldoc.healdan.trash.TrashRules;
import com.logicaldoc.healdan.trash.TrashState;
import com.logicaldoc.healdan.trash.TrashStore;

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
 */
final class FolderChanges {

	private static final Logger log = LoggerFactory.getLogger(FolderChanges.class);

	/** The refusal for a tree with something the caller may not delete, in the same words whatever it is */
	static final String DELETE_ITEMS = "It holds items you don't have permission to delete.";

	/** The refusal for a tree with something the caller may not move */
	static final String MOVE_ITEMS = "It holds items you don't have permission to move.";

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
					.orElseThrow(() -> new IllegalStateException(
							"Folder %d was deleted with no Trash item".formatted(id)));
			return TrashViews.item(item, user);
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
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
