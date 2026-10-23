package com.logicaldoc.core;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.user.Group;

/**
 * Healdan's checks on the operations whose side effects a listener veto can't
 * undo. Each method is called at the top of the CE method it guards, before
 * any side effect (the after-hook at its very end), so a
 * {@link HealdanVetoException} it throws refuses the operation before anything
 * is written. The document and folder DAOs roll back on that exception, not on
 * other {@link PersistenceException}s. {@link #countPages} is the one hook that
 * isn't a check: it corrects a value CE works out. Every method is a no-op by
 * default, and {@link #get()} returns a no-op until healdan-core registers its
 * implementation, so CE behaves as before without Healdan.
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
