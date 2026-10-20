package com.logicaldoc.webdav.resource.service;

import org.apache.jackrabbit.webdav.DavException;

import com.logicaldoc.core.HealdanTreeChecks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Healdan: the document permissions CE's WebDAV doesn't check (M1 plan, R70
 * and R73; principle 4). CE asks DELETE on a document's folder to delete it,
 * and WRITE on the folder a copy goes into but nothing on the document copied.
 * These refuse with a 403, as CE's own checks do. A folder's tree is checked
 * by Healdan's folder-delete hook, which every route goes through.
 * <p>
 * They run core's {@link HealdanTreeChecks#document}, as the GUI's and the web
 * services' document checks do (R78), so every route reads a document's
 * permissions one way: the entries of its own ACL for the user's groups, else
 * its folder's. Admins keep CE's bypass, which the DAO gives them.
 * <p>
 * Healdan addition: lives in its own file so upstream merges don't touch it.
 */
final class HealdanDavChecks {

	private HealdanDavChecks() {
	}

	/**
	 * Deleting a document needs DELETE on it (R70)
	 *
	 * @param user the user
	 * @param docId the document, a shortcut on itself
	 *
	 * @throws DavException the refusal, or an error at data layer
	 */
	static void deletable(User user, long docId) throws DavException {
		check(user, Permission.DELETE, docId, "No rights to delete the document.");
	}

	/**
	 * Copying a document needs DOWNLOAD on it, since the copy can be
	 * downloaded (R73)
	 *
	 * @param user the user
	 * @param docId the document, a shortcut on itself
	 *
	 * @throws DavException the refusal, or an error at data layer
	 */
	static void downloadable(User user, long docId) throws DavException {
		check(user, Permission.DOWNLOAD, docId, "No rights to download the document.");
	}

	private static void check(User user, Permission permission, long docId, String refusal) throws DavException {
		try {
			HealdanTreeChecks.document(user, permission, docId);
		} catch (PermissionException e) {
			throw new DavException(HttpServletResponse.SC_FORBIDDEN, refusal);
		} catch (PersistenceException e) {
			throw new DavException(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e);
		}
	}
}
