package com.logicaldoc.web.service;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentLink;
import com.logicaldoc.core.document.DocumentLinkDAO;
import com.logicaldoc.core.document.DocumentNote;
import com.logicaldoc.core.document.DocumentNoteDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.menu.MenuDAO;
import com.logicaldoc.core.ticket.Ticket;
import com.logicaldoc.core.ticket.TicketDAO;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIDocumentNote;
import com.logicaldoc.util.CollectionUtil;

/**
 * Healdan: the permission checks CE's RPCs lacked (M1 plan, "Follow-ups from
 * PR C" and "Follow-ups found while closing PR C's"; principle 4). Each RPC
 * calls one of these before it reads or writes, so a refused call writes
 * nothing, and a list is checked whole before any of it is written.
 * <p>
 * A document's permissions are its effective ones: the entries of its own ACL
 * for the user's groups, else its folder's. A document-only grant is enough
 * (D7), and a document's narrower ACL holds where its folder grants more.
 * Admins keep CE's bypass, which the DAOs give them.
 */
public final class HealdanRpcChecks {

    private HealdanRpcChecks() {
    }

    /**
     * Refuses unless the user has the permission on the document; a missing or
     * deleted document is refused
     *
     * @param session the user's session
     * @param permission the permission needed
     * @param docId the document
     *
     * @throws PersistenceException error at data layer
     * @throws AccessDeniedException the refusal
     */
    public static void document(Session session, Permission permission, long docId)
            throws PersistenceException, AccessDeniedException {
        if (!DocumentDAO.get().isPermissionAllowed(permission, docId, session.getUserId()))
            throw documentRefusal(session, permission, docId);
    }

    /**
     * Refuses unless the user has the permission on every document
     *
     * @param session the user's session
     * @param permission the permission needed
     * @param docIds the documents, each checked as it is, a shortcut on itself
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void documents(Session session, Permission permission, Collection<Long> docIds)
            throws ServerException {
        try {
            for (Long docId : docIds)
                if (docId != null)
                    document(session, permission, docId);
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Refuses unless the user has every one of the permissions on every
     * document
     *
     * @param session the user's session
     * @param docIds the documents, each checked as it is, a shortcut on itself
     * @param permissions the permissions needed
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void documents(Session session, Collection<Long> docIds, Permission... permissions)
            throws ServerException {
        try {
            DocumentDAO dao = DocumentDAO.get();
            for (Long docId : docIds) {
                if (docId == null)
                    continue;
                // One read of the document's permissions for all of them
                Set<Permission> allowed = dao.getAllowedPermissions(docId, session.getUserId());
                for (Permission permission : permissions)
                    if (!allowed.contains(permission))
                        throw documentRefusal(session, permission, docId);
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Refuses unless the user has the permission on every document that
     * exists; missing or deleted documents are left to the call, which skips
     * them
     *
     * @param session the user's session
     * @param permission the permission needed
     * @param docIds the documents, each checked as it is, a shortcut on itself
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void existingDocuments(Session session, Permission permission, Collection<Long> docIds)
            throws ServerException {
        try {
            for (Long docId : docIds)
                if (docId != null && DocumentDAO.get().findById(docId) != null)
                    document(session, permission, docId);
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Refuses unless the user has the permission on every document a call
     * works on: a shortcut's target, which is what CE writes. Missing documents
     * are left to the call, which skips them.
     *
     * @param session the user's session
     * @param permission the permission needed
     * @param docIds the documents or shortcuts
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void targets(Session session, Permission permission, Collection<Long> docIds)
            throws ServerException {
        try {
            for (Long docId : docIds) {
                Document target = docId != null ? DocumentDAO.get().findDocument(docId) : null;
                if (target != null)
                    document(session, permission, target.getId());
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * A check-in needs WRITE on the document and, as CE's GUI asks, the user's
     * own checkout of it (admins excepted)
     *
     * @param session the user's session
     * @param docId the document checked in, a shortcut's target
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void checkin(Session session, long docId) throws ServerException {
        try {
            document(session, Permission.WRITE, docId);
            Document document = DocumentDAO.get().findById(docId);
            if (!session.getUser().isAdmin() && (document.getStatus() != DocumentStatus.CHECKEDOUT
                    || !Objects.equals(document.getLockUserId(), session.getUserId())))
                throw new AccessDeniedException(
                        "Document %d is not checked out by user %s".formatted(docId, session.getUsername()));
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Refuses unless the user has the permission on the document of every
     * version; missing versions are left to the call
     *
     * @param session the user's session
     * @param permission the permission needed
     * @param versionIds the versions
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void versions(Session session, Permission permission, Collection<Long> versionIds)
            throws ServerException {
        try {
            for (Long versionId : versionIds) {
                Version version = versionId != null ? VersionDAO.get().findById(versionId) : null;
                if (version != null)
                    document(session, permission, version.getDocId());
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Purging by id takes what {@code emptyTrash} takes: items in the trash
     * that the user deleted; an admin's, any item in the trash. The caller's
     * update also keeps to items in the trash, so a live one is never purged.
     *
     * @param session the user's session
     * @param table {@code ld_document} or {@code ld_folder}
     * @param ids the items to purge
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void trash(Session session, String table, Collection<Long> ids) throws ServerException {
        if (session.getUser().isAdmin())
            return;
        Set<Long> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty())
            return;
        try {
            long own = DocumentDAO.get()
                    .queryForLong("select count(ld_id) from %s where ld_deleted = 1 and ld_deleteuserid = %d and ld_id in (%s)"
                            .formatted(table, session.getUserId(), CollectionUtil.join(wanted)));
            if (own < wanted.size())
                throw new AccessDeniedException(
                        "User %s can only purge what they deleted from the trash".formatted(session.getUsername()));
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * A link needs WRITE on one of its two documents and READ on the other, as
     * pasting one does: CE's GUI asks WRITE on the documents pasted onto
     *
     * @param session the user's session
     * @param docId1 one document
     * @param docId2 the other
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void link(Session session, long docId1, long docId2) throws ServerException {
        try {
            DocumentDAO dao = DocumentDAO.get();
            Set<Permission> first = dao.getAllowedPermissions(docId1, session.getUserId());
            Set<Permission> second = dao.getAllowedPermissions(docId2, session.getUserId());
            if (!(first.contains(Permission.WRITE) && second.contains(Permission.READ))
                    && !(second.contains(Permission.WRITE) && first.contains(Permission.READ)))
                throw new AccessDeniedException(
                        "User %s doesn't have permission WRITE on document %d or %d and READ on the other"
                                .formatted(session.getUsername(), docId1, docId2));
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Each link changed or deleted needs what creating it needs; missing links
     * are left to the call
     *
     * @param session the user's session
     * @param linkIds the links
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void links(Session session, Collection<Long> linkIds) throws ServerException {
        try {
            for (Long linkId : linkIds) {
                DocumentLink link = linkId != null ? DocumentLinkDAO.get().findById(linkId) : null;
                if (link != null)
                    link(session, link.getDocument1().getId(), link.getDocument2().getId());
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Bookmarking needs READ on each document, or on each folder that exists
     * (CE skips a missing one)
     *
     * @param session the user's session
     * @param ids the documents or folders
     * @param type a {@link Bookmark.Type} ordinal
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void bookmarkTargets(Session session, Collection<Long> ids, int type) throws ServerException {
        try {
            for (Long id : ids) {
                if (id == null)
                    continue;
                if (type == Bookmark.Type.FOLDER.ordinal()) {
                    if (FolderDAO.get().findById(id) != null)
                        folder(session, Permission.READ, id);
                } else if (DocumentDAO.get().findById(id) != null) {
                    document(session, Permission.READ, id);
                }
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * A bookmark is changed or deleted only by its owner; missing bookmarks are
     * left to the call
     *
     * @param session the user's session
     * @param bookmarkIds the bookmarks
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void bookmarks(Session session, Collection<Long> bookmarkIds) throws ServerException {
        try {
            for (Long bookmarkId : bookmarkIds) {
                Bookmark bookmark = bookmarkId != null ? BookmarkDAO.get().findById(bookmarkId) : null;
                if (bookmark != null && bookmark.getUserId() != session.getUserId())
                    throw new AccessDeniedException("Bookmark %d is not of user %s".formatted(bookmarkId,
                            session.getUsername()));
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * A new note needs WRITE on its document, as CE's GUI asks. An existing
     * note must be the document's, and needs READ on the document and the
     * note's own WRITE, and its SECURITY if its ACL changes.
     *
     * @param session the user's session
     * @param document the document the note is saved on, null if missing
     * @param guiNote the note as the client sends it
     * @param note the stored note, null for a new one
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void note(Session session, Document document, GUIDocumentNote guiNote, DocumentNote note)
            throws ServerException {
        try {
            long docId = document != null ? document.getId() : guiNote.getDocId();
            if (note == null) {
                document(session, Permission.WRITE, docId);
                return;
            }
            if (note.getDocId() != docId)
                throw new AccessDeniedException("Note %d is not on document %d".formatted(note.getId(), docId));
            document(session, Permission.READ, docId);
            Set<Permission> allowed = DocumentNoteDAO.get().getAllowedPermissions(note.getId(), session.getUserId());
            notePermission(session, allowed, Permission.WRITE, note.getId());
            Set<String> stored = note.getAccessControlList().stream()
                    .map(ace -> acl(ace.getGroupId(), ace.isRead(), ace.isWrite(), ace.isDelete(), ace.isSecurity()))
                    .collect(Collectors.toSet());
            Set<String> sent = guiNote.getAccessControlList().stream()
                    .map(ace -> acl(ace.getEntityId(), ace.isRead(), ace.isWrite(), ace.isDelete(), ace.isSecurity()))
                    .collect(Collectors.toSet());
            if (!stored.equals(sent))
                notePermission(session, allowed, Permission.SECURITY, note.getId());
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Saving a document's notes in one go, as the annotations window does,
     * needs READ on the document and what {@link #note} needs for each note.
     * The call also deletes the caller's notes left out of the list; they are
     * the caller's own, or an admin's call, so READ on the document is what
     * deleting them needs.
     *
     * @param session the user's session
     * @param document the document
     * @param guiNotes the notes as the client sends them
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void notes(Session session, Document document, Collection<GUIDocumentNote> guiNotes)
            throws ServerException {
        try {
            document(session, Permission.READ, document.getId());
            DocumentNoteDAO dao = DocumentNoteDAO.get();
            for (GUIDocumentNote guiNote : guiNotes)
                note(session, document, guiNote, dao.findById(guiNote.getId(), true));
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Deleting a note needs READ on its document and the note's own DELETE;
     * missing notes are left to the call
     *
     * @param session the user's session
     * @param noteIds the notes
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void deletedNotes(Session session, Collection<Long> noteIds) throws ServerException {
        try {
            DocumentNoteDAO dao = DocumentNoteDAO.get();
            for (Long noteId : noteIds) {
                DocumentNote note = noteId != null ? dao.findById(noteId) : null;
                if (note == null)
                    continue;
                document(session, Permission.READ, note.getDocId());
                notePermission(session, dao.getAllowedPermissions(noteId, session.getUserId()), Permission.DELETE,
                        noteId);
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Reading a note needs READ on its document and the note's own READ, as
     * CE's listing of a document's notes reads it: the user's own note, any
     * note for an admin, a note without an ACL, or one whose ACL lets the user
     * read it. A missing note is left to the call.
     *
     * @param session the user's session
     * @param noteId the note
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void readNote(Session session, long noteId) throws ServerException {
        try {
            DocumentNoteDAO dao = DocumentNoteDAO.get();
            DocumentNote note = dao.findById(noteId, true);
            if (note == null)
                return;
            document(session, Permission.READ, note.getDocId());
            // The listing's rule on this note alone: its permissions give an admin and the note's author everything
            if (!note.getAccessControlList().isEmpty())
                notePermission(session, dao.getAllowedPermissions(noteId, session.getUserId()), Permission.READ,
                        noteId);
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * A ticket is changed or deleted by its creator or an admin; missing
     * tickets are left to the call
     *
     * @param session the user's session
     * @param ticketIds the tickets
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void tickets(Session session, Collection<Long> ticketIds) throws ServerException {
        if (session.getUser().isAdmin())
            return;
        try {
            for (Long ticketId : ticketIds) {
                Ticket ticket = ticketId != null ? TicketDAO.get().findById(ticketId) : null;
                if (ticket != null && ticket.getUserId() != session.getUserId())
                    throw new AccessDeniedException(
                            "Ticket %d was not created by user %s".formatted(ticketId, session.getUsername()));
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Refuses unless the user has the permission on every folder
     *
     * @param session the user's session
     * @param permission the permission needed
     * @param folderIds the folders
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void folders(Session session, Permission permission, Collection<Long> folderIds)
            throws ServerException {
        try {
            for (Long folderId : folderIds)
                if (folderId != null)
                    folder(session, permission, folderId);
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Refuses unless the user has the permission on the folder and on every
     * live folder below it, before a call hands the folder's settings down the
     * tree
     *
     * @param session the user's session
     * @param permission the permission needed
     * @param rootId the tree's root
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void tree(Session session, Permission permission, long rootId) throws ServerException {
        trees(session, List.of(rootId), permission);
    }

    /**
     * Refuses unless the user has each permission on every live folder of each
     * tree
     *
     * @param session the user's session
     * @param rootIds the trees' roots
     * @param permissions the permissions needed
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void trees(Session session, Collection<Long> rootIds, Permission... permissions)
            throws ServerException {
        try {
            FolderDAO dao = FolderDAO.get();
            for (Long rootId : rootIds) {
                if (rootId == null)
                    continue;
                // The tree is read once for all the permissions
                Collection<Long> tree = dao.findById(rootId) != null ? dao.findFolderIdInTree(rootId, false) : null;
                for (Permission permission : permissions)
                    if (tree == null || !dao
                            .findFolderIdByUserIdAndPermission(session.getUserId(), permission, rootId, true)
                            .containsAll(tree))
                        throw new AccessDeniedException("User %s doesn't have permission %s on every folder of tree %d"
                                .formatted(session.getUsername(), permission.name(), rootId));
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Deleting a folder deletes everything in it: DELETE on every live folder
     * of each tree and on every live document in them
     *
     * @param session the user's session
     * @param folderIds the folders to delete
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void deletedTrees(Session session, Collection<Long> folderIds) throws ServerException {
        if (session.getUser().isAdmin())
            return;
        try {
            for (Long folderId : folderIds) {
                if (folderId == null)
                    continue;
                tree(session, Permission.DELETE, folderId);
                documentsIn(session, Permission.DELETE, FolderDAO.get().findFolderIdInTree(folderId, false));
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Copying a folder needs READ on every folder the copy walks and, unless
     * only folders are copied, DOWNLOAD on every document it copies, since the
     * copies can be downloaded. CE's copy walks the folder and then, level by
     * level, the children {@code FolderDAO.findChildren(parentId, userId)}
     * gives the user.
     *
     * @param session the user's session
     * @param folderIds the folders to copy
     * @param foldersOnly true if no documents are copied
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void folderCopies(Session session, Collection<Long> folderIds, boolean foldersOnly)
            throws ServerException {
        if (session.getUser().isAdmin())
            return;
        try {
            FolderDAO dao = FolderDAO.get();
            for (Long folderId : folderIds) {
                if (folderId == null)
                    continue;
                // First: findChildren fails on a missing folder
                folder(session, Permission.READ, folderId);
                Set<Long> walked = new LinkedHashSet<>();
                Deque<Long> next = new ArrayDeque<>();
                next.push(folderId);
                while (!next.isEmpty()) {
                    long id = next.pop();
                    if (walked.add(id))
                        for (Folder child : dao.findChildren(id, session.getUserId()))
                            next.push(child.getId());
                }
                // One query for the readable folders of the tree. The root was checked above; a folder the walk
                // reached outside the tree, through an alias, is checked by itself.
                Collection<Long> readable = dao.findFolderIdByUserIdAndPermission(session.getUserId(),
                        Permission.READ, folderId, true);
                for (Long id : walked)
                    if (!folderId.equals(id) && !readable.contains(id))
                        folder(session, Permission.READ, id);
                if (!foldersOnly)
                    documentsIn(session, Permission.DOWNLOAD, walked);
            }
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Pasting shortcuts needs WRITE on the folder (an alias's target), as
     * pasting copies does, and DOWNLOAD on each document, as CE's SOAP
     * {@code createAlias} asks: CE serves a shortcut's target to whoever has
     * DOWNLOAD on the shortcut, so a shortcut hands the content to the folder
     * it is put in
     *
     * @param session the user's session
     * @param docIds the documents
     * @param folderId where the shortcuts go
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void shortcuts(Session session, Collection<Long> docIds, long folderId) throws ServerException {
        try {
            Folder folder = FolderDAO.get().findFolder(folderId);
            folder(session, Permission.WRITE, folder != null ? folder.getId() : folderId);
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
        documents(session, Permission.DOWNLOAD, docIds);
    }

    /**
     * Runs each document's restore check, as the restore itself runs it,
     * before any document is restored, so a refusal restores none
     *
     * @param session the user's session
     * @param docIds the documents to restore
     * @param folderId the folder they are restored into
     *
     * @throws ServerException the refusal, or an error at data layer
     */
    public static void restores(Session session, Collection<Long> docIds, long folderId) throws ServerException {
        DocumentHistory transaction = new DocumentHistory();
        transaction.setSession(session);
        try {
            for (Long docId : docIds)
                if (docId != null)
                    HealdanHooks.get().beforeDocumentRestore(docId, folderId, transaction);
        } catch (PersistenceException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    /**
     * Refuses unless the user is an admin
     *
     * @param session the user's session
     *
     * @throws AccessDeniedException the refusal
     */
    public static void admin(Session session) throws AccessDeniedException {
        if (!session.getUser().isAdmin())
            throw new AccessDeniedException("User %s is not an administrator".formatted(session.getUsername()));
    }

    /**
     * The deleted documents and deleted folders reports list what one user, or
     * everyone, deleted. A user without the report's menu sees only what they
     * deleted themselves.
     *
     * @param session the user's session
     * @param menuId the report's menu
     * @param userId the user whose deletions are asked for, null for everyone's
     *
     * @return the user whose deletions are listed, null for everyone's
     *
     * @throws IOException the refusal of another user's deletions
     */
    public static Long trashUser(Session session, long menuId, Long userId) throws IOException {
        if (MenuDAO.get().isReadAllowed(menuId, session.getUserId()))
            return userId;
        if (userId != null && userId != session.getUserId())
            throw new IOException("User %s can only list what they deleted".formatted(session.getUsername()));
        return session.getUserId();
    }

    private static void folder(Session session, Permission permission, long folderId)
            throws PersistenceException, AccessDeniedException {
        if (!FolderDAO.get().isPermissionAllowed(permission, folderId, session.getUserId()))
            throw new AccessDeniedException("User %s doesn't have permission %s on folder %s"
                    .formatted(session.getUsername(), permission.name(), folderId));
    }

    /**
     * Refuses unless the user has the permission on every live document in the
     * folders. As CE's {@code DocumentDAO.getAllowedPermissions} reads it, a
     * document whose own ACL has no entry for the user's groups has its
     * folder's permissions, so its folder is checked once. A document whose
     * entries grant the permission has it; any other is checked by itself,
     * since entries that grant nothing at all leave it its folder's.
     * <p>
     * The permission must be one a document's ACL has a column for. The
     * folders go in lists of at most 1000, as Oracle takes no more.
     */
    private static void documentsIn(Session session, Permission permission, Collection<Long> folderIds)
            throws PersistenceException, AccessDeniedException {
        DocumentDAO dao = DocumentDAO.get();
        String groups = "select ld_groupid from ld_usergroup where ld_userid = %d".formatted(session.getUserId());
        for (List<Long> chunk : CollectionUtil.partition(new ArrayList<>(folderIds), 1000)) {
            String folders = CollectionUtil.join(chunk);
            for (Long folderId : dao.queryForList("""
                    select distinct D.ld_folderid from ld_document D
                     where D.ld_deleted = 0 and D.ld_folderid in (%s)
                       and not exists (select A.ld_docid from ld_document_acl A where A.ld_docid = D.ld_id and A.ld_groupid in (%s))
                    """.formatted(folders, groups), Long.class))
                folder(session, permission, folderId);
            for (Long docId : dao.queryForList("""
                    select distinct D.ld_id from ld_document D, ld_document_acl A
                     where A.ld_docid = D.ld_id and D.ld_deleted = 0 and D.ld_folderid in (%s) and A.ld_groupid in (%s)
                       and not exists (select B.ld_docid from ld_document_acl B
                                        where B.ld_docid = D.ld_id and B.ld_groupid in (%s) and B.ld_%s = 1)
                    """.formatted(folders, groups, groups, permission.name().toLowerCase()), Long.class))
                document(session, permission, docId);
        }
    }

    private static AccessDeniedException documentRefusal(Session session, Permission permission, long docId) {
        return new AccessDeniedException("User %s doesn't have permission %s on document %s"
                .formatted(session.getUsername(), permission.name(), docId));
    }

    private static void notePermission(Session session, Set<Permission> allowed, Permission permission, long noteId)
            throws AccessDeniedException {
        if (!allowed.contains(permission))
            throw new AccessDeniedException("User %s doesn't have permission %s on note %d"
                    .formatted(session.getUsername(), permission.name(), noteId));
    }

    private static String acl(long groupId, boolean read, boolean write, boolean delete, boolean security) {
        return "%d:%b:%b:%b:%b".formatted(groupId, read, write, delete, security);
    }
}
