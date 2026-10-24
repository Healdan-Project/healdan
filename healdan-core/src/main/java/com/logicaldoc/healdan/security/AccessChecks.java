package com.logicaldoc.healdan.security;

import java.util.Optional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.rules.FilingRules;

/**
 * What a user may see and do, for code that answers a user rather than a
 * listener's veto. {@code HealdanTreeChecks} in logicaldoc-core throws
 * {@code PermissionException}; these return answers, so the API can report an
 * unreadable object as one that doesn't exist (M2 spec section 7).
 */
public final class AccessChecks {

	/** The group whose members CE's searches show unpublished documents to */
	private static final String PUBLISHER_GROUP = "publisher";

	private AccessChecks() {
	}

	/**
	 * @param user the user, with its groups
	 * @return true if the user is an administrator, as Healdan's rules decide
	 * @throws PersistenceException error at data layer
	 */
	public static boolean isAdmin(User user) throws PersistenceException {
		return FilingRules.get().isAdmin(user);
	}

	/**
	 * @param docNumber the doc # (the document's custom id), looked up in the
	 *        user's own tenant
	 * @param user the user, with its groups
	 * @return the document, if it exists, isn't deleted and the user may read
	 *         it; otherwise empty, whatever the reason
	 * @throws PersistenceException error at data layer
	 */
	public static Optional<Document> readableDocument(long docNumber, User user) throws PersistenceException {
		// The DAO skips deleted documents
		return readableDocument(DocumentDAO.get().findByCustomId(Long.toString(docNumber), user.getTenantId()), user);
	}

	/**
	 * @param document a document, or null
	 * @param user the user, with its groups
	 * @return the document, if it isn't deleted, the user may read it (through
	 *         the document's own grants, or its folder's) and it is published
	 *         now, unless the user is in the {@code admin} or {@code publisher}
	 *         group; otherwise empty
	 * @throws PersistenceException error at data layer
	 */
	public static Optional<Document> readableDocument(Document document, User user) throws PersistenceException {
		if (document == null || document.getDeleted() != 0)
			return Optional.empty();
		if (!DocumentDAO.get().isReadAllowed(document.getId(), user.getId()))
			return Optional.empty();
		if (!document.isPublishing() && !user.isMemberOf(Group.GROUP_ADMIN) && !user.isMemberOf(PUBLISHER_GROUP))
			return Optional.empty();
		return Optional.of(document);
	}

	/**
	 * @param user the user
	 * @param permission the permission
	 * @param document the document
	 * @return true if the user has the permission on the document: its own
	 *         grants if it has any, else its folder's
	 * @throws PersistenceException error at data layer
	 */
	public static boolean allowed(User user, Permission permission, Document document) throws PersistenceException {
		return DocumentDAO.get().isPermissionAllowed(permission, document.getId(), user.getId());
	}

	/**
	 * @param folderId the folder's id
	 * @param user the user, with its groups
	 * @return the folder, if it exists and the user may read it; otherwise empty
	 * @throws PersistenceException error at data layer
	 */
	public static Optional<Folder> readableFolder(long folderId, User user) throws PersistenceException {
		Folder folder = FolderDAO.get().findById(folderId);
		if (folder == null || folder.getDeleted() != 0 || !FolderDAO.get().isReadAllowed(folderId, user.getId()))
			return Optional.empty();
		return Optional.of(folder);
	}

	/**
	 * @param user the user
	 * @param permission the permission
	 * @param folder the folder
	 * @return true if the user has the permission on the folder
	 * @throws PersistenceException error at data layer
	 */
	public static boolean allowed(User user, Permission permission, Folder folder) throws PersistenceException {
		return FolderDAO.get().isPermissionAllowed(permission, folder.getId(), user.getId());
	}
}
