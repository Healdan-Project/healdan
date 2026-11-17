// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.core;

import java.util.Collection;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;

/**
 * Healdan's checks on the operations whose side effects a listener veto can't
 * undo. Each method is called at the top of the CE method it guards, before
 * any side effect (the after-hook at its very end), so a
 * {@link HealdanVetoException} it throws refuses the operation before anything
 * is written. The document and folder DAOs roll back on that exception, not on
 * other {@link PersistenceException}s. {@link #countPages} and
 * {@link #folderRestoreScope} aren't only checks: the first corrects a value
 * CE works out, the second what CE's restore sets live. Every method is a
 * no-op by default, and {@link #get()} returns a no-op until healdan-core
 * registers its implementation, so CE behaves as before without Healdan.
 * <p>
 * Healdan addition: lives in its own file so upstream merges don't touch it.
 */
public interface HealdanHooks {

    /**
     * Called in {@code DocumentManager.moveToFolder} once the target is known to
     * differ from the current folder, before the hit is removed from the index
     * and the aliases are marked for reindexing
     *
     * @param document the document to move
     * @param target the target folder
     * @param transaction the history entry, possibly {@code null}
     *
     * @throws PersistenceException to refuse the move
     */
    default void beforeDocumentMove(Document document, Folder target, DocumentHistory transaction)
            throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code DocumentManager.rename}, before the renamed version
     * is scheduled for saving in the background
     *
     * @param docId the document to rename
     * @param newName the new file name
     * @param transaction the history entry, possibly {@code null}
     *
     * @throws PersistenceException to refuse the rename
     */
    default void beforeDocumentRename(long docId, String newName, DocumentHistory transaction)
            throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code DocumentManager.update}, before a language change
     * removes the hit from the index
     *
     * @param document the document to update, possibly {@code null}
     * @param docVO the new metadata, possibly {@code null}
     * @param transaction the history entry, possibly {@code null}
     *
     * @throws PersistenceException to refuse the update
     */
    default void beforeDocumentUpdate(Document document, Document docVO, DocumentHistory transaction)
            throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code HibernateDocumentDAO.restore}, before the rows are
     * undeleted with JDBC
     *
     * @param docId the document to restore
     * @param folderId the folder to restore it into
     * @param transaction the history entry, possibly {@code null}
     *
     * @throws PersistenceException to refuse the restore
     */
    default void beforeDocumentRestore(long docId, long folderId, DocumentHistory transaction)
            throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code HibernateFolderDAO.deleteTree}, the entry shared by
     * all its overloads, before anything is marked deleted
     *
     * @param folderId the root of the tree to delete ({@code 0} when no folder
     *        was given)
     * @param transaction the history entry, possibly {@code null}
     *
     * @throws PersistenceException to refuse the delete
     */
    default void beforeFolderDeleteTree(long folderId, FolderHistory transaction) throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code HibernateFolderDAO.merge}, and again for each
     * clashing subfolder it merges, before any document is moved: each move
     * removes the document's hit from the index and saves a version in the
     * background, which a later veto can't undo
     *
     * @param source the folder to merge, possibly {@code null}
     * @param target the folder to merge it into, possibly {@code null}
     * @param transaction the history entry, possibly {@code null}
     *
     * @throws PersistenceException to refuse the merge
     */
    default void beforeFolderMerge(Folder source, Folder target, FolderHistory transaction)
            throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code HibernateFolderDAO.copy}, once per copy, before
     * any folder or document is created: each copied document writes its file
     * to the store and schedules its first version's save in the background,
     * which a later veto can't undo
     *
     * @param source the folder to copy, possibly {@code null}
     * @param target the folder to copy it into, possibly {@code null}
     * @param newName the name of the copy, {@code null} for the source's name
     * @param foldersOnly true if only the folder tree is copied
     * @param securityOption how the copies get their security: {@code null} or
     *        {@code none}, {@code inherit} or {@code replicate}
     * @param transaction the history entry, possibly {@code null}
     *
     * @throws PersistenceException to refuse the copy
     */
    default void beforeFolderCopy(Folder source, Folder target, String newName, boolean foldersOnly,
            String securityOption, FolderHistory transaction) throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code HibernateFolderDAO.restore}, before the rows are
     * undeleted with JDBC
     *
     * @param folderId the folder to restore
     * @param parentId the requested parent
     * @param transaction the history entry, possibly {@code null}
     *
     * @throws PersistenceException to refuse the restore
     */
    default void beforeFolderRestore(long folderId, long parentId, FolderHistory transaction)
            throws PersistenceException {
        // No check by default
    }

    /**
     * The folders and documents a folder's restore sets live, besides the
     * folder itself, which CE always does
     *
     * @param folderIds the folders, in the folder's tree or not
     * @param documentIds the documents
     */
    record RestoreScope(Collection<Long> folderIds, Collection<Long> documentIds) {
    }

    /**
     * Called in {@code HibernateFolderDAO.restore} after
     * {@link #beforeFolderRestore}, in its transaction, before any row is
     * undeleted, so the folder's row still shows whether it was deleted or
     * removed. CE then runs
     * {@code update ... set ld_deleted = 0 ... where ld_deleted <> 0 and ld_id in (...)}
     * for each list of the scope instead of its own two updates.
     *
     * @param folderId the folder to restore
     * @param treeIds the folder and every folder below it, deleted or not
     * @param transaction the history entry, possibly {@code null}
     *
     * @return the folders and documents the restore sets live; {@code null}
     *         keeps CE's: every folder and document at 1 in the tree
     *
     * @throws PersistenceException to refuse the restore
     */
    default RestoreScope folderRestoreScope(long folderId, Collection<Long> treeIds, FolderHistory transaction)
            throws PersistenceException {
        return null;
    }

    /**
     * Called last in {@code HibernateFolderDAO.restore}, in its transaction,
     * once the folder, its subtree and their documents are undeleted
     *
     * @param folderId the restored folder
     * @param parentId the requested parent
     * @param transaction the history entry, possibly {@code null}
     *
     * @throws PersistenceException if the restored tree can't be completed
     */
    default void afterFolderRestore(long folderId, long parentId, FolderHistory transaction)
            throws PersistenceException {
        // Nothing to do by default
    }

    /**
     * Called first in {@code HibernateFolderDAO.applySecurityToTree}, before the
     * descendants' own ACLs are deleted
     *
     * @param rootId the folder whose security is applied to its tree
     * @param transaction the history entry, possibly {@code null}
     *
     * @throws PersistenceException to refuse the change
     */
    default void beforeApplySecurityToTree(long rootId, FolderHistory transaction) throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code DocumentServiceImpl.bulkUpdate}, before any
     * document is changed, with the permission rows it will give each one: its
     * loop logs a refusal and goes on
     *
     * @param rows the rows, as {@code saveACL} makes them
     *
     * @throws PersistenceException to refuse the update
     */
    default void beforeDocumentPermissions(Collection<DocumentAccessControlEntry> rows) throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code HibernateGroupDAO.store} and {@code insert},
     * before the group is written, so the database still holds its old name
     *
     * @param group the group to store
     *
     * @throws PersistenceException to refuse the store
     */
    default void beforeGroupStore(Group group) throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code HibernateGroupDAO.delete}, before the group is
     * renamed and marked deleted, and in {@code SecurityServiceImpl.deleteGroup}
     * before it takes the members out. CE leaves the deleted group's
     * memberships in place, and its permission checks still count them.
     *
     * @param group the group to delete
     *
     * @throws PersistenceException to refuse the delete
     */
    default void beforeGroupDelete(Group group) throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code HibernateAttributeSetDAO.store}, before the set
     * or its attributes are written, so the database still holds its old
     * attributes
     *
     * @param set the attribute set to store, with the attributes it is to have
     *
     * @throws PersistenceException to refuse the store
     */
    default void beforeAttributeSetStore(AttributeSet set) throws PersistenceException {
        // No check by default
    }

    /**
     * Called first in {@code HibernateAttributeSetDAO.delete}, before the set is
     * renamed and marked deleted and its options are deleted
     *
     * @param set the attribute set to delete
     *
     * @throws PersistenceException to refuse the delete
     */
    default void beforeAttributeSetDelete(AttributeSet set) throws PersistenceException {
        // No check by default
    }

    /**
     * Called by the Classic Interface's Delete from the Trash, after its own
     * checks, before it changes any row: Healdan's removal of the listed rows
     * (and, for a folder, what its item took with it) to Removed.
     *
     * @param table {@code ld_document} or {@code ld_folder}
     * @param ids the rows the person chose
     * @param user who removes them
     *
     * @return {@code true} when Healdan removed them, so that CE's own update is
     *         skipped
     *
     * @throws PersistenceException error at data layer
     */
    default boolean removeFromTrash(String table, Collection<Long> ids, User user) throws PersistenceException {
        return false;
    }

    /**
     * Called by the Classic Interface's Empty Trash, before it changes any row
     *
     * @param user whose trash is emptied
     *
     * @return {@code true} when Healdan emptied it, so that CE's own updates are
     *         skipped
     *
     * @throws PersistenceException error at data layer
     */
    default boolean emptyTrash(User user) throws PersistenceException {
        return false;
    }

    /**
     * Called last in {@code DocumentManager.countPages(Document)}, CE's recount
     * of a stored document (the Properties tab's recount, automation's
     * {@code DocTool.countPages}), with the count the parser gave. Not a check:
     * it lets Healdan give the count it stores, which a parser can't always
     * work out from the file alone.
     *
     * @param document the document, possibly a shortcut
     * @param pages the parser's count
     *
     * @return the count to give
     *
     * @throws PersistenceException if the count can't be worked out
     */
    default int countPages(Document document, int pages) throws PersistenceException {
        return pages;
    }

    /**
     * Gets the registered hooks
     *
     * @return the registered hooks, never {@code null}: a no-op until something
     *         registers
     */
    static HealdanHooks get() {
        return Registry.hooks;
    }

    /**
     * Registers the hooks the core calls
     *
     * @param hooks the hooks, {@code null} restores the no-op
     */
    static void register(HealdanHooks hooks) {
        Registry.hooks = hooks != null ? hooks : Registry.NONE;
    }

    /**
     * Holds the registered hooks
     */
    final class Registry {

        private static final HealdanHooks NONE = new HealdanHooks() {
        };

        private static volatile HealdanHooks hooks = NONE;

        private Registry() {
        }
    }
}
