// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document.OutputSettings;
import org.jsoup.safety.Safelist;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.FolderRef;
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.util.BasicPlane;
import com.logicaldoc.util.html.HTMLSanitizer;

/**
 * Renaming a folder (folder rename spec section 4.1), through CE's folder DAO
 * as CE's own rename does, so Healdan's listeners apply M1's rules as on every
 * route. The refusals people meet are checked here first, to give them codes
 * of their own, in this order: a folder the caller can't read, outside the
 * Stores or an alias ({@code 404}); a Store or a matter's own folder
 * ({@code 409 not-renamable}); without the Rename permission
 * ({@code 403 forbidden}); directly in a matter's workspace, unless the caller
 * is an administrator ({@code 403 admins-only}); a name that isn't allowed
 * ({@code 422 validation}); a name another live folder in the parent has,
 * without regard to case ({@code 409 name-taken}). A veto of Healdan's
 * listeners is {@code 409 refused}, as the ProblemMapper answers one.
 */
final class FolderRenames {

	/**
	 * One lock per parent folder, so two renames to one name can't both pass
	 * the check. {@link DocumentLocks} is a lock per id; the ids here are the
	 * parents'. It is this server's alone, as Healdan runs one node, and CE's
	 * New Folder and folder moves don't take it, so a rename checks again
	 * inside it that the folder is still in the parent it locked.
	 */
	private final DocumentLocks parents = new DocumentLocks();

	/**
	 * @param id the folder's id
	 * @param requested the name sent, null if none
	 * @param caller the signed-in caller
	 * @return the folder, renamed; as it was for its own name
	 */
	FolderView rename(long id, String requested, Caller caller) {
		if (requested == null)
			throw ApiProblem.badRequest("Say the folder's new name with name.");
		User user = caller.user();
		String failure = "Can't rename folder #" + id;
		try {
			FolderView view = FolderResource.view(id, user);
			refuse(view, user);
			String name = name(requested);
			Folder folder = FolderDAO.get().findById(id);
			if (name.equals(folder.getName()))
				return view;
			long parentId = folder.getParentId();
			return parents.withLock(parentId, failure, () -> {
				FolderDAO folders = FolderDAO.get();
				// Read again, initialised as CE's rename reads it: another request may have renamed it meanwhile, or
				// taken it away from the caller (404)
				FolderView now = FolderResource.view(id, user);
				Folder current = folders.findById(id, true);
				// Or moved it, out from under the parent's lock, which then guards the wrong folder's names
				if (current.getParentId() != parentId)
					throw ApiProblem.conflict("refused",
							"Folder %d moved while it was being renamed. Try again.".formatted(id));
				// And the checks again, on the folder as it is stored now: its permissions may have changed
				refuse(now, user);
				if (name.equals(current.getName()))
					return now;
				if (taken(current, name))
					throw ApiProblem.conflict("name-taken",
							"Another folder in folder %d is called %s.".formatted(current.getParentId(), name));

				FolderHistory history = new FolderHistory();
				history.setFilenameOld(current.getName());
				history.setPathOld(folders.computePathExtended(id));
				history.setEvent(FolderEvent.RENAMED);
				history.setSession(caller.session());
				current.setName(name);
				// A veto of Healdan's listeners is answered 409 refused by the ProblemMapper
				folders.store(current, history);
				return FolderResource.view(id, user);
			});
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	private static void refuse(FolderView view, User user) throws PersistenceException {
		if ("store".equals(view.kind()))
			throw ApiProblem.conflict("not-renamable", "A Store isn't renamed here.");
		if ("matter".equals(view.kind()))
			throw ApiProblem.conflict("not-renamable",
					"A matter's own folder takes the matter's name: an administrator changes it with Edit Matter.");
		if (!AccessChecks.allowed(user, Permission.RENAME, FolderDAO.get().findById(view.id())))
			throw ApiProblem.forbidden("You may not rename folder %d.".formatted(view.id()));
		if (topLevel(view) && !AccessChecks.isAdmin(user))
			throw new ApiProblem(403, "admins-only", "Only administrators can rename a matter's top-level folders.");
	}

	/** Directly in a matter's workspace: the step above it in its path is the matter's own folder */
	private static boolean topLevel(FolderView view) {
		List<FolderRef> path = view.path();
		return path.size() >= 2 && "matter".equals(path.get(path.size() - 2).kind());
	}

	/**
	 * @param requested the name sent
	 * @return the name to store: the one sent, without spaces at either end
	 * @throws ApiProblem {@code 422 validation} for the field {@code name}:
	 *         {@code required}, {@code too-long}, {@code invalid} (a slash, a
	 *         backslash, a control character, one the database can't store,
	 *         markup CE's folder DAO would take out, or any HTML tag)
	 */
	static String name(String requested) {
		String sent = StringUtils.strip(requested);
		if (StringUtils.isBlank(sent))
			throw ApiProblem.validation(List.of(new FieldError("name", "required", "Give the folder a name.")));

		List<FieldError> errors = new ArrayList<>();
		if (sent.chars().anyMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c)))
			errors.add(new FieldError("name", "invalid", "A name can't contain /, \\ or control characters."));
		// CE's tables hold only the Basic Multilingual Plane, and strict MariaDB refuses the rest at the write
		else if (!BasicPlane.holds(sent))
			errors.add(new FieldError("name", "invalid",
					"A name can't contain emoji or other characters the database can't store."));
		// CE's folder DAO cleans the name of markup as it stores it, so a name it would change isn't the name typed; and
		// the tags CE's cleaning keeps (b, em, i, strong and u) are refused too, as a name has no HTML tags at all
		else if (!sent.equals(HTMLSanitizer.sanitizeSimpleText(sent)) || !sent.equals(withoutTags(sent)))
			errors.add(new FieldError("name", "invalid", "A name can't contain HTML tags."));
		if (sent.length() > Uploads.MAX_NAME_LENGTH)
			errors.add(new FieldError("name", Uploads.TOO_LONG,
					"A name can't be longer than %d characters.".formatted(Uploads.MAX_NAME_LENGTH)));
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);
		return sent;
	}

	/** The text with every HTML tag taken out, built as CE's {@code HTMLSanitizer.sanitizeSimpleText} builds its own */
	private static String withoutTags(String text) {
		OutputSettings settings = new OutputSettings().indentAmount(0).prettyPrint(false);
		return StringEscapeUtils.unescapeHtml(Jsoup.clean(text, "", Safelist.none(), settings));
	}

	/** Whether another live folder in the folder's parent, an alias included, has the name, without regard to case */
	private static boolean taken(Folder folder, String name) {
		return StoredRows.get().children(folder.getParentId()).stream()
				.anyMatch(row -> row.id() != folder.getId() && name.equalsIgnoreCase(row.name()));
	}
}
