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
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;

/**
 * Renaming a folder (folder rename spec section 4.1), through CE's folder DAO
 * as CE's own rename does, so Healdan's listeners apply M1's rules as on every
 * route. The refusals people meet are checked here first, to give them codes
 * of their own, in this order: a folder the caller can't read, outside the
 * Stores or an alias ({@code 404}); a Store or a matter's own folder
 * ({@code 409 not-renamable}); without the Rename permission
 * ({@code 403 forbidden}); directly in a matter's workspace, unless the caller
 * is an administrator ({@code 403 admins-only}); a name that isn't allowed
 * ({@code 422 validation}); a name another live folder in the parent that the
 * caller can see has, without regard to case ({@code 409 name-taken}). The
 * folder's own name, sent back, changes nothing, whatever the rules for a new
 * name say of it. A veto of Healdan's
 * listeners is {@code 409 refused}, as the ProblemMapper answers one.
 * <p>
 * Its rule for a name, {@link #name}, is the admin API's too, for every
 * folder name it takes ({@code openapi-admin.yaml}'s {@code FolderName}).
 */
public final class FolderRenames {

	/**
	 * One lock per parent folder, so two renames to one name can't both pass
	 * the check. {@link DocumentLocks} is a lock per id; the ids here are the
	 * parents'. It is this server's alone, as Healdan runs one node, and CE's
	 * New Folder and folder moves don't take it, so a rename checks again
	 * inside it that the folder is still in the parent it locked. Package
	 * private for FolderRenameTest, which holds it.
	 */
	final DocumentLocks parents = new DocumentLocks();

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
			Folder folder = FolderDAO.get().findById(id);
			// Its own name changes nothing, before the rules for a new one: CE's routes can give a folder a name they
			// refuse, such as one with a b tag, which CE's cleaning keeps
			if (StringUtils.strip(requested).equals(folder.getName()))
				return view;
			String name = name(requested);
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
				if (taken(current.getParentId(), id, name, user))
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

	/**
	 * Refuses what the view's {@code canRename} refuses ({@link ApiViews#may}
	 * is the rule), with the code for its reason
	 */
	private static void refuse(FolderView view, User user) throws PersistenceException {
		if ("store".equals(view.kind()))
			throw ApiProblem.conflict("not-renamable", "A Store isn't renamed here.");
		if ("matter".equals(view.kind()))
			throw ApiProblem.conflict("not-renamable",
					"A matter's own folder takes the matter's name: an administrator changes it with Edit Matter.");
		if (view.canRename())
			return;
		if (!FolderDAO.get().isPermissionAllowed(Permission.RENAME, view.id(), user.getId()))
			throw ApiProblem.forbidden("You may not rename folder %d.".formatted(view.id()));
		// With the Rename permission, the other reason is a top-level folder and a caller who isn't an administrator
		throw new ApiProblem(403, "admins-only", "Only administrators can rename a matter's top-level folders.");
	}

	/**
	 * @param requested the name sent
	 * @return the name to store: the one sent, without spaces at either end
	 * @throws ApiProblem {@code 422 validation} for the field {@code name}:
	 *         {@code required}, also for a name of characters that show
	 *         nothing; {@code too-long}; {@code invalid} (a slash, a
	 *         backslash, a control character, one Healdan can't keep, one
	 *         that changes the text's direction, any HTML tag, or a character
	 *         reference such as {@code &amp;}, which CE's folder DAO would turn
	 *         into its character)
	 */
	public static String name(String requested) {
		List<FieldError> errors = new ArrayList<>();
		String name = name(requested, errors);
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);
		return name;
	}

	/**
	 * As {@link #name(String)}, for a form with other fields: what's wrong
	 * with the name is added to the errors, as the field {@code name}, rather
	 * than thrown.
	 *
	 * @param requested the name sent, null for none, which is
	 *        {@code required}
	 * @param errors what's wrong with the form's fields so far, which this
	 *        adds to
	 * @return the name to store, null if it has an error
	 */
	public static String name(String requested, List<FieldError> errors) {
		String sent = StringUtils.strip(requested);
		// None, spaces alone, or characters that show nothing (such as U+200B and U+FEFF), make no name
		if (sent == null || sent.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
				|| Character.getType(c) == Character.FORMAT)) {
			errors.add(new FieldError("name", "required", "Give the folder a name."));
			return null;
		}

		int before = errors.size();
		// As a document's and a favourite's name: no slash, backslash or control character, nothing PostgreSQL can't
		// store (a NUL or a lone surrogate, StorableText), and at most 255 characters
		Uploads.checkText(sent, "name", Uploads.MAX_NAME_LENGTH, false, errors);
		List<FieldError> found = errors.subList(before, errors.size());
		boolean invalid = found.stream().anyMatch(error -> "invalid".equals(error.code()));
		// A direction override shows a name as another, such as "Invoice", U+202E, then "fdp.exe" as "Invoiceexe.pdf"
		if (!invalid && sent.codePoints().anyMatch(FolderRenames::directionControl))
			found.add(0, new FieldError("name", "invalid",
					"A name can't contain characters that change the direction of the text."));
		// CE's folder DAO cleans the name of markup and character references as it stores it, so a name it would change
		// isn't the name typed; and the tags CE's cleaning keeps (b, em, i, strong and u) are refused too, as a name has
		// no HTML tags at all
		else if (!invalid && !sent.equals(withoutTags(sent)))
			found.add(0, new FieldError("name", "invalid",
					"A name can't contain HTML tags or character references such as &amp;."));
		return found.isEmpty() ? sent : null;
	}

	/** The embeddings, overrides and isolates (U+202A to U+202E, U+2066 to U+2069); the marks a script needs are fine */
	private static boolean directionControl(int c) {
		return (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
	}

	/** The text with every HTML tag taken out, built as CE's {@code HTMLSanitizer.sanitizeSimpleText} builds its own */
	private static String withoutTags(String text) {
		OutputSettings settings = new OutputSettings().indentAmount(0).prettyPrint(false);
		return StringEscapeUtils.unescapeHtml(Jsoup.clean(text, "", Safelist.none(), settings));
	}

	/**
	 * Whether another live folder in the parent, an alias included, has the
	 * name, without regard to case. Only one the caller can see counts: a
	 * hidden one, or one the caller can't read, isn't the caller's to learn of,
	 * as one the caller can't read is {@code 404} elsewhere. A folder move asks
	 * it of the folder it moves into ({@link FolderChanges}).
	 *
	 * @param parentId the folder the name would be in
	 * @param exceptId the folder that would have the name, which doesn't count
	 * @param name the name
	 * @param user the caller, with its groups
	 * @return true if the name is taken there
	 * @throws PersistenceException error at data layer
	 */
	static boolean taken(long parentId, long exceptId, String name, User user) throws PersistenceException {
		FolderDAO folders = FolderDAO.get();
		for (FolderRow row : StoredRows.get().children(parentId)) {
			if (row.id() == exceptId || !name.equalsIgnoreCase(row.name()))
				continue;
			Folder sibling = folders.findById(row.id());
			if (sibling != null && !sibling.isHidden() && folders.isReadAllowed(row.id(), user.getId()))
				return true;
		}
		return false;
	}
}
