// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.cmis;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException;

import com.logicaldoc.core.HealdanTreeChecks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;

/**
 * Healdan: the document permissions CE's CMIS doesn't check (M1 plan, R70 and
 * R73; principle 4). CE asks DELETE on a document's folder to delete it, and
 * WRITE on the folder a copy goes into but nothing on the document copied.
 * These refuse as CE's own checks do, with a
 * {@link CmisPermissionDeniedException}. A folder's tree is checked by
 * Healdan's folder-delete hook, which {@code deleteTree} calls first.
 * <p>
 * They run core's {@link HealdanTreeChecks#document}, as the GUI's and the web
 * services' document checks do (R78), so every route reads a document's
 * permissions one way: the entries of its own ACL for the user's groups, else
 * its folder's. Admins keep CE's bypass, which the DAO gives them.
 * <p>
 * Healdan addition: lives in its own file so upstream merges don't touch it.
 */
final class HealdanCmisChecks {

	private HealdanCmisChecks() {
	}

	/**
	 * Deleting a document needs DELETE on it (R70); anything else is left to
	 * CE
	 *
	 * @param object what is deleted
	 * @param user who deletes it
	 */
	static void deletable(PersistentObject object, User user) {
		if (object instanceof Document document)
			check(document, user, Permission.DELETE);
	}

	/**
	 * Copying a document needs DOWNLOAD on it, since the copy can be
	 * downloaded (R73)
	 *
	 * @param document the document copied, a shortcut on itself
	 * @param user who copies it
	 */
	static void downloadable(Document document, User user) {
		check(document, user, Permission.DOWNLOAD);
	}

	/**
	 * The document or folder an object ID names as it is stored: a shortcut or a
	 * folder alias itself, where CE's {@code getObject} resolves it to what it
	 * points to. A listing shows a shortcut or an alias under its own ID, so a
	 * delete given that ID deletes it, as the GUI does, and not what it points
	 * to.
	 *
	 * @param objectId the object ID
	 *
	 * @return the document or folder, null for a version or an ID that names
	 *         neither
	 */
	static PersistentObject itself(String objectId) {
		try {
			if (objectId.startsWith("doc."))
				return DocumentDAO.get().findById(Long.parseLong(objectId.substring(4)), true);
			if (objectId.startsWith("ver."))
				return null;
			return FolderDAO.get()
					.findById(Long.parseLong(objectId.startsWith("fld.") ? objectId.substring(4) : objectId), true);
		} catch (NumberFormatException | PersistenceException e) {
			return null;
		}
	}

	private static void check(Document document, User user, Permission permission) {
		try {
			HealdanTreeChecks.document(user, permission, document.getId());
		} catch (PermissionException e) {
			throw new CmisPermissionDeniedException(
					"Permission %s not granted on document %d".formatted(permission.name(), document.getId()));
		} catch (PersistenceException e) {
			throw new CmisStorageException(e.getMessage(), e);
		}
	}
}
