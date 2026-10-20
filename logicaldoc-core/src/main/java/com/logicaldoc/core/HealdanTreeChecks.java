package com.logicaldoc.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.util.CollectionUtil;

/**
 * Healdan: the permission checks on a whole folder tree that CE's calls
 * lacked, shared by the GUI's RPCs ({@code HealdanRpcChecks} in the webapp)
 * and the web services (M1 plan, R73, R74 and R78; principle 4), with the
 * checks on one folder or document they are built from. A call runs one of
 * these before it writes, so a refused call writes nothing.
 * <p>
 * A document's permissions are its effective ones: the entries of its own ACL
 * for the user's groups, else its folder's. Admins keep CE's bypass.
 */
public final class HealdanTreeChecks {

    /** The most items a query's {@code in} list holds: Oracle refuses more */
    private static final int IN_LIST_LIMIT = 1000;

    private HealdanTreeChecks() {
    }

    /**
     * Refuses unless the user has the permission on the folder
     *
     * @param user the user
     * @param permission the permission needed
     * @param folderId the folder
     *
     * @throws PersistenceException error at data layer
     * @throws PermissionException the refusal
     */
    public static void folder(User user, Permission permission, long folderId)
            throws PersistenceException, PermissionException {
        if (!FolderDAO.get().isPermissionAllowed(permission, folderId, user.getId()))
            throw new PermissionException("User %s doesn't have permission %s on folder %s"
                    .formatted(user.getUsername(), permission.name(), folderId));
    }

    /**
     * Refuses unless the user has the permission on the document; a missing or
     * deleted document is refused
     *
     * @param user the user
     * @param permission the permission needed
     * @param docId the document
     *
     * @throws PersistenceException error at data layer
     * @throws PermissionException the refusal
     */
    public static void document(User user, Permission permission, long docId)
            throws PersistenceException, PermissionException {
        if (!DocumentDAO.get().isPermissionAllowed(permission, docId, user.getId()))
            throw new PermissionException("User %s doesn't have permission %s on document %s"
                    .formatted(user.getUsername(), permission.name(), docId));
    }

    /**
     * Refuses unless the user has every one of the permissions on every
     * document that exists; missing or deleted documents are left to the call,
     * which skips them
     *
     * @param user the user
     * @param docIds the documents, each checked as it is, a shortcut on itself
     * @param permissions the permissions needed
     *
     * @throws PersistenceException error at data layer
     * @throws PermissionException the refusal
     */
    public static void existingDocuments(User user, Collection<Long> docIds, Permission... permissions)
            throws PersistenceException, PermissionException {
        DocumentDAO dao = DocumentDAO.get();
        for (Long docId : docIds) {
            if (docId == null || dao.findById(docId) == null)
                continue;
            Set<Permission> allowed = dao.getAllowedPermissions(docId, user.getId());
            for (Permission permission : permissions)
                if (!allowed.contains(permission))
                    throw new PermissionException("User %s doesn't have permission %s on document %s"
                            .formatted(user.getUsername(), permission.name(), docId));
        }
    }

    /**
     * Refuses unless the user has the permission on the folder and on every
     * live folder below it
     *
     * @param user the user
     * @param permission the permission needed
     * @param rootId the tree's root
     *
     * @throws PersistenceException error at data layer
     * @throws PermissionException the refusal
     */
    public static void tree(User user, Permission permission, long rootId)
            throws PersistenceException, PermissionException {
        tree(user, permission, rootId, null);
    }

    /**
     * {@link #tree(User, Permission, long)} on the tree's folders, null to look
     * them up
     */
    private static void tree(User user, Permission permission, long rootId, Collection<Long> treeIds)
            throws PersistenceException, PermissionException {
        FolderDAO dao = FolderDAO.get();
        if (dao.findById(rootId) == null || !user.isAdmin()
                && !dao.findFolderIdByUserIdAndPermission(user.getId(), permission, rootId, true)
                        .containsAll(treeIds != null ? treeIds : dao.findFolderIdInTree(rootId, false)))
            throw new PermissionException("User %s doesn't have permission %s on every folder of tree %d"
                    .formatted(user.getUsername(), permission.name(), rootId));
    }

    /**
     * Refuses unless the user has each permission on every live folder of each
     * tree
     *
     * @param user the user
     * @param rootIds the trees' roots
     * @param permissions the permissions needed
     *
     * @throws PersistenceException error at data layer
     * @throws PermissionException the refusal
     */
    public static void trees(User user, Collection<Long> rootIds, Permission... permissions)
            throws PersistenceException, PermissionException {
        for (Long rootId : rootIds)
            if (rootId != null)
                for (Permission permission : permissions)
                    tree(user, permission, rootId);
    }

    /**
     * Deleting a folder deletes everything in it: DELETE on every live folder
     * of each tree and on every live document in them
     *
     * @param user the user
     * @param folderIds the folders to delete
     *
     * @throws PersistenceException error at data layer
     * @throws PermissionException the refusal
     */
    public static void deletedTrees(User user, Collection<Long> folderIds)
            throws PersistenceException, PermissionException {
        if (user.isAdmin())
            return;
        for (Long folderId : folderIds) {
            if (folderId == null)
                continue;
            Collection<Long> treeIds = FolderDAO.get().findFolderIdInTree(folderId, false);
            tree(user, Permission.DELETE, folderId, treeIds);
            documentsIn(user, Permission.DELETE, treeIds);
        }
    }

    /**
     * Copying a folder needs READ on every folder the copy walks and, unless
     * only folders are copied, DOWNLOAD on every document it copies, since the
     * copies can be downloaded. CE's copy walks the folder and then, level by
     * level, the children {@code FolderDAO.findChildren(parentId, userId)}
     * gives the user.
     *
     * @param user the user
     * @param folderIds the folders to copy
     * @param foldersOnly true if no documents are copied
     *
     * @throws PersistenceException error at data layer
     * @throws PermissionException the refusal
     */
    public static void folderCopies(User user, Collection<Long> folderIds, boolean foldersOnly)
            throws PersistenceException, PermissionException {
        if (user.isAdmin())
            return;
        FolderDAO dao = FolderDAO.get();
        for (Long folderId : folderIds) {
            if (folderId == null)
                continue;
            // First: findChildren fails on a missing folder
            folder(user, Permission.READ, folderId);
            Set<Long> walked = new LinkedHashSet<>();
            Deque<Long> next = new ArrayDeque<>();
            next.push(folderId);
            while (!next.isEmpty()) {
                long id = next.pop();
                if (walked.add(id))
                    for (Folder child : dao.findChildren(id, user.getId()))
                        next.push(child.getId());
            }
            for (Long id : walked)
                folder(user, Permission.READ, id);
            if (!foldersOnly)
                documentsIn(user, Permission.DOWNLOAD, walked);
        }
    }

    /**
     * Refuses unless the user has the permission on every live document in the
     * folders. As CE's {@code DocumentDAO.getAllowedPermissions} reads it, a
     * document whose own ACL has no entry for the user's groups has its
     * folder's permissions, so its folder is checked once; any other document
     * is checked by itself. The folders are queried a thousand at a time.
     */
    private static void documentsIn(User user, Permission permission, Collection<Long> folderIds)
            throws PersistenceException, PermissionException {
        DocumentDAO dao = DocumentDAO.get();
        String groups = "select ld_groupid from ld_usergroup where ld_userid = %d".formatted(user.getId());
        for (List<Long> part : CollectionUtil.partition(new ArrayList<>(folderIds), IN_LIST_LIMIT)) {
            String folders = CollectionUtil.join(part);
            for (Long folderId : dao.queryForList("""
                    select distinct D.ld_folderid from ld_document D
                     where D.ld_deleted = 0 and D.ld_folderid in (%s)
                       and not exists (select A.ld_docid from ld_document_acl A where A.ld_docid = D.ld_id and A.ld_groupid in (%s))
                    """.formatted(folders, groups), Long.class))
                folder(user, permission, folderId);
            for (Long docId : dao.queryForList("""
                    select distinct D.ld_id from ld_document D, ld_document_acl A
                     where A.ld_docid = D.ld_id and D.ld_deleted = 0 and D.ld_folderid in (%s) and A.ld_groupid in (%s)
                    """.formatted(folders, groups), Long.class))
                document(user, permission, docId);
        }
    }
}
