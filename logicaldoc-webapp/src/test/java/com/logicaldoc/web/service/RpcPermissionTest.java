package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentLinkDAO;
import com.logicaldoc.core.document.DocumentNote;
import com.logicaldoc.core.document.DocumentNoteDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.document.NoteAccessControlEntry;
import com.logicaldoc.core.document.RatingDAO;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.ticket.Ticket;
import com.logicaldoc.core.ticket.TicketDAO;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIAccessControlEntry;
import com.logicaldoc.gui.common.client.beans.GUIBookmark;
import com.logicaldoc.gui.common.client.beans.GUIDocument;
import com.logicaldoc.gui.common.client.beans.GUIDocumentNote;
import com.logicaldoc.gui.common.client.beans.GUIRating;
import com.logicaldoc.util.io.FileUtil;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.MemoryResponse;
import com.logicaldoc.web.UploadServlet;
import com.logicaldoc.web.data.VersionsDataServlet;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * CE RPCs that checked no permission on what they read or write, listed under
 * "Follow-ups from PR C" in the M1 plan (principle 4: the server enforces
 * every rule on every route). Each call is refused to a user without the
 * permission it needs and writes nothing, and goes through for a user who has
 * it. A document's permissions are its effective ones, so a grant in the
 * document's own ACL is enough (D7). Admins keep CE's bypass, which
 * {@code DocumentServiceImplTest} and {@code FolderServiceImplTest} exercise.
 * <p>
 * The fixture's documents are in a folder where the author's groups have READ
 * and boss has nothing. Three tests, because each one builds its database
 * again.
 */
public class RpcPermissionTest extends AbstractWPTestCase {

    /** The password of the fixture's users */
    private static final String PASSWORD = "admin";

    private static final long ADMIN = 1L;

    private static final long AUTHOR = 4L;

    /** The author's own group */
    private static final long AUTHOR_GROUP = -4L;

    /** A fixture document, which has versions 1 and 2, notes 1 and 2, links 1 to 4 and tickets 1 and 3 */
    private static final long DOC = 1L;

    /** A shortcut to {@link #DOC} */
    private static final long SHORTCUT = 2L;

    /** A fixture document linked to {@link #DOC} */
    private static final long LINKED = 3L;

    /** A fixture document with note 3, by another user */
    private static final long OTHER = 4L;

    /** A fixture document with version 1.0, which the tests check in */
    private static final long CHECKIN = 7L;

    /** A tree of the fixture: 1200, its child 1201 and its grandchild 1202 */
    private static final long TREE = 1200L;

    private static final long CHILD = 1201L;

    private static final long GRANDCHILD = 1202L;

    /** A folder where the author's groups have READ, WRITE, DELETE, RENAME and DOWNLOAD */
    private static final long AUTHORS_FOLDER = 6L;

    /** A folder of the fixture, deleted by nobody in particular */
    private static final long TRASHED_FOLDER = 8L;

    private DocumentDAO docDao;

    private FolderDAO folderDao;

    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        docDao = DocumentDAO.get();
        folderDao = FolderDAO.get();
    }

    @Override
    public void tearDown() throws IOException {
        SearchEngine.get().unlock();
        SearchEngine.get().close();
        super.tearDown();
    }

    @Test
    public void testDocumentChangesNeedTheirPermission() throws Exception {
        // Doc 4 archived, and the GUI's copy of the document to check in
        Document archived = docDao.findById(OTHER, true);
        archived.setStatus(DocumentStatus.ARCHIVED);
        docDao.store(archived);
        GUIDocument toCheckin = new DocumentServiceImpl().getById(CHECKIN);

        // Boss has no grant, the author only READ
        for (String username : List.of("boss", "author")) {
            as(username);
            DocumentServiceImpl documents = new DocumentServiceImpl();
            upload();
            refused(() -> documents.checkout(List.of(DOC)));
            refused(() -> documents.checkout(List.of(SHORTCUT)));
            refused(() -> documents.lock(List.of(DOC), "lock"));
            refused(() -> documents.checkin(toCheckin, false));
            refused(() -> documents.makeImmutable(List.of(DOC), "immutable"));
            refused(() -> documents.markUnindexable(List.of(DOC)));
            refused(() -> documents.markIndexable(List.of(DOC), IndexingStatus.TO_INDEX.ordinal()));
            refused(() -> documents.indexDocuments(List.of(DOC)));
            refused(() -> documents.deleteVersions(List.of(1L)));
            refused(() -> documents.unarchiveDocuments(List.of(OTHER)));
            refused(() -> documents.saveSummary(DOC, "1.0", "A summary"));
            refused(() -> documents.enforceFilesIntoFolderStore(TREE));

            Document doc = docDao.findById(DOC);
            assertEquals(DocumentStatus.UNLOCKED, doc.getStatus());
            assertFalse(doc.isImmutable());
            assertEquals(IndexingStatus.INDEXED, doc.getIndexed());
            assertNotNull(VersionDAO.get().findById(1L));
            assertEquals(DocumentStatus.ARCHIVED, docDao.findById(OTHER).getStatus());
            assertEquals("1.0", docDao.findById(CHECKIN).getVersion());
            assertEquals(DocumentStatus.UNLOCKED, docDao.findById(CHECKIN).getStatus());
        }
        as("admin");
        assertEquals("", new DocumentServiceImpl().getSummary(DOC, "1.0"));

        // Grants in the documents' own ACLs; the folder still gives the author READ only (D7)
        grant(DOC, Permission.READ, Permission.WRITE, Permission.DELETE, Permission.RENAME, Permission.PASSWORD);
        grant(OTHER, Permission.READ, Permission.ARCHIVE, Permission.IMMUTABLE);
        grant(CHECKIN, Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
        as("author");
        DocumentServiceImpl documents = new DocumentServiceImpl();
        upload();

        // A list is checked whole: one document without WRITE, and none is checked out
        refused(() -> documents.checkout(List.of(CHECKIN, OTHER)));
        assertEquals(DocumentStatus.UNLOCKED, docDao.findById(CHECKIN).getStatus());

        // A check-in needs the checkout too
        refused(() -> documents.checkin(documents.getById(CHECKIN), false));
        documents.checkout(List.of(CHECKIN));
        assertEquals(Long.valueOf(AUTHOR), docDao.findById(CHECKIN).getLockUserId());
        documents.checkin(documents.getById(CHECKIN), false);
        assertEquals("1.1", docDao.findById(CHECKIN).getVersion());
        assertEquals(DocumentStatus.UNLOCKED, docDao.findById(CHECKIN).getStatus());
        assertNotNull(documents.getContentAsString(CHECKIN));

        documents.lock(List.of(DOC), "lock");
        assertEquals(DocumentStatus.LOCKED, docDao.findById(DOC).getStatus());
        documents.unlock(List.of(DOC));
        documents.markUnindexable(List.of(DOC));
        assertEquals(IndexingStatus.SKIP, docDao.findById(DOC).getIndexed());
        documents.markIndexable(List.of(DOC), IndexingStatus.TO_INDEX.ordinal());
        assertEquals(IndexingStatus.TO_INDEX, docDao.findById(DOC).getIndexed());
        documents.indexDocuments(List.of(DOC));
        documents.saveSummary(DOC, "1.0", "A summary");
        assertEquals("A summary", documents.getSummary(DOC, "1.0"));
        documents.deleteVersions(List.of(1L));
        assertNull(VersionDAO.get().findById(1L));
        documents.unarchiveDocuments(List.of(OTHER));
        assertEquals(DocumentStatus.UNLOCKED, docDao.findById(OTHER).getStatus());
        documents.makeImmutable(List.of(OTHER), "immutable");
        assertTrue(docDao.findById(OTHER).isImmutable());

        // Bulk update, rename and password read the document's own permissions, not the folder's
        GUIDocument model = new GUIDocument();
        model.setColor("red");
        assertEquals(1, documents.bulkUpdate(List.of(DOC), model, true).size());
        assertEquals("red", docDao.findById(DOC).getColor());
        documents.rename(DOC, "renamed.pdf");
        assertEquals("renamed.pdf", docDao.findById(DOC).getFileName());
        documents.setPassword(DOC, "Kx7!Rmq-Tz8vW");
        assertTrue(documents.isPasswordProtected(DOC));

        // ...so a document's narrower ACL holds even where the folder grants more
        as("admin");
        Document narrowed = docDao.findById(5L, true);
        narrowed.setFolder(folderDao.findById(AUTHORS_FOLDER));
        docDao.store(narrowed);
        grant(5L, Permission.READ);
        as("author");
        assertTrue(folderDao.isPermissionAllowed(Permission.RENAME, AUTHORS_FOLDER, AUTHOR));
        refused(() -> new DocumentServiceImpl().rename(5L, "renamed.eml"));
        refused(() -> new DocumentServiceImpl().getContentAsString(5L));
        assertNotEquals("renamed.eml", docDao.findById(5L).getFileName());

        // A shortcut's content is its target's, so DOWNLOAD on the shortcut isn't enough
        as("admin");
        grant(SHORTCUT, Permission.READ, Permission.DOWNLOAD);
        as("author");
        refused(() -> new DocumentServiceImpl().getContentAsString(SHORTCUT));
    }

    @Test
    public void testDocumentExtrasNeedTheirPermission() throws Exception {
        // The admin's vote and bookmark on the document, and a ticket of the author's
        DocumentServiceImpl adminDocuments = new DocumentServiceImpl();
        adminDocuments.saveRating(rating(DOC, ADMIN, 5));
        adminDocuments.addBookmarks(List.of(DOC), Bookmark.Type.DOCUMENT.ordinal());
        Bookmark adminBookmark = BookmarkDAO.get().findByUserIdAndDocId(ADMIN, DOC);
        Ticket ticket = new Ticket();
        ticket.setTenantId(Tenant.DEFAULT_ID);
        ticket.setTicketId("authors-ticket");
        ticket.setDocId(DOC);
        ticket.setUserId(AUTHOR);
        TicketDAO.get().store(ticket);
        long authorsTicket = ticket.getId();
        // A note of the author's on a document the author can only read
        DocumentNote authorsNote = new DocumentNote();
        authorsNote.setTenantId(Tenant.DEFAULT_ID);
        authorsNote.setDocId(OTHER);
        authorsNote.setUserId(AUTHOR);
        authorsNote.setUsername("author");
        authorsNote.setMessage("the author's");
        authorsNote.setFileVersion("1.0");
        authorsNote.setDate(new Date());
        DocumentNoteDAO.get().store(authorsNote);

        // Boss can't read the document; the author can, but can't write it
        for (String username : List.of("boss", "author")) {
            as(username);
            DocumentServiceImpl documents = new DocumentServiceImpl();
            refused(() -> documents.saveNote(newNote(DOC, username)));
            refused(() -> documents.saveNotes(DOC, null, List.of(newNote(DOC, username)), null));
            refused(() -> documents.saveNote(note(1L, DOC, "edited by " + username)));
            refused(() -> documents.deleteNotes(List.of(1L)));
            refused(() -> documents.linkDocuments(List.of(DOC), List.of(LINKED)));
            refused(() -> documents.updateLink(1L, username));
            refused(() -> documents.deleteLinks(List.of(1L)));
            refused(() -> documents.deleteBookmarks(List.of(adminBookmark.getId())));
            refused(() -> documents.updateBookmark(bookmark(adminBookmark.getId(), username)));
            refused(() -> documents.setTicketPassword(1L, "Kx7!Rmq-Tz8vW"));
            refused(() -> documents.disableTickets(List.of(1L)));
            refused(() -> documents.enableTickets(List.of(1L)));
            refused(() -> documents.deleteTickets(List.of(1L)));
        }

        as("boss");
        DocumentServiceImpl bosses = new DocumentServiceImpl();
        refused(() -> bosses.addBookmarks(List.of(DOC), Bookmark.Type.DOCUMENT.ordinal()));
        refused(() -> bosses.addBookmarks(List.of(AUTHORS_FOLDER), Bookmark.Type.FOLDER.ordinal()));
        refused(() -> bosses.getRating(DOC));
        refused(() -> bosses.getUserRating(DOC));
        refused(() -> bosses.saveRating(rating(DOC, 2L, 1)));
        refused(() -> bosses.isPasswordProtected(DOC));
        refused(() -> bosses.countDocuments(List.of(Folder.ROOTID), 0));
        refused(() -> bosses.updatePages(DOC));
        refused(() -> bosses.getSummary(DOC, "1.0"));
        refused(() -> bosses.setTicketPassword(authorsTicket, "Kx7!Rmq-Tz8vW"));
        refused(() -> bosses.saveNotes(DOC, null, List.of(), null));

        // Nothing was written
        DocumentNoteDAO noteDao = DocumentNoteDAO.get();
        assertEquals(2, noteDao.findByDocId(DOC, ADMIN, "1.0").size());
        assertEquals("message for note 1", message(1L));
        assertEquals("test", DocumentLinkDAO.get().findById(1L).getType());
        assertNull(DocumentLinkDAO.get().findByDocIdsAndType(DOC, LINKED, "default"));
        assertEquals(adminBookmark.getTitle(), BookmarkDAO.get().findById(adminBookmark.getId()).getTitle());
        assertNull(BookmarkDAO.get().findByUserIdAndDocId(2L, DOC));
        assertNull(RatingDAO.get().findByDocIdAndUserId(DOC, 2L));
        assertTrue(TicketDAO.get().findById(1L).isEnabled());
        assertNull(TicketDAO.get().findById(1L).getPassword());
        assertNull(TicketDAO.get().findById(authorsTicket).getPassword());

        // What needs READ goes through for the author
        as("author");
        DocumentServiceImpl documents = new DocumentServiceImpl();
        // saveNotes checks every note before it deletes the ones left out
        refused(() -> documents.saveNotes(OTHER, null, List.of(newNote(OTHER, "new")), null));
        assertNotNull(noteDao.findById(authorsNote.getId()));
        documents.addBookmarks(List.of(DOC), Bookmark.Type.DOCUMENT.ordinal());
        documents.addBookmarks(List.of(AUTHORS_FOLDER), Bookmark.Type.FOLDER.ordinal());
        Bookmark own = BookmarkDAO.get().findByUserIdAndDocId(AUTHOR, DOC);
        assertEquals(docDao.findById(DOC).getFileName(), own.getTitle());
        assertNotNull(BookmarkDAO.get().findByUserIdAndFolderId(AUTHOR, AUTHORS_FOLDER));
        as("admin");
        refused(() -> new DocumentServiceImpl().updateBookmark(bookmark(own.getId(), "the admin's")));
        as("author");
        documents.updateBookmark(bookmark(own.getId(), "mine"));
        assertEquals("mine", BookmarkDAO.get().findById(own.getId()).getTitle());
        documents.deleteBookmarks(List.of(own.getId()));
        assertNull(BookmarkDAO.get().findById(own.getId()));

        assertEquals(Integer.valueOf(1), documents.getRating(DOC).getCount());
        // A vote is the caller's own, whatever user the client names
        documents.saveRating(rating(DOC, ADMIN, 2));
        assertEquals(5, RatingDAO.get().findByDocIdAndUserId(DOC, ADMIN).getVote());
        assertEquals(2, documents.getUserRating(DOC).getVote());

        assertFalse(documents.isPasswordProtected(DOC));
        assertEquals(7L, documents.countDocuments(List.of(Folder.ROOTID), 0));
        assertTrue(documents.updatePages(DOC) > 0);
        assertEquals("", documents.getSummary(DOC, "1.0"));

        // A ticket is managed by its creator
        documents.disableTickets(List.of(authorsTicket));
        assertFalse(TicketDAO.get().findById(authorsTicket).isEnabled());
        documents.enableTickets(List.of(authorsTicket));
        assertTrue(TicketDAO.get().findById(authorsTicket).isEnabled());
        assertTrue(documents.setTicketPassword(authorsTicket, "Kx7!Rmq-Tz8vW").isEmpty());
        assertNotNull(TicketDAO.get().findById(authorsTicket).getPassword());
        documents.deleteTickets(List.of(authorsTicket));
        assertNull(TicketDAO.get().findById(authorsTicket));

        // WRITE on the document, and on notes 1 and 3 their own WRITE but not DELETE or SECURITY
        as("admin");
        grant(DOC, Permission.READ, Permission.WRITE);
        grant(6L, Permission.PRINT);
        for (long noteId : List.of(1L, 3L)) {
            DocumentNote note = noteDao.findById(noteId, true);
            NoteAccessControlEntry noteGrant = new NoteAccessControlEntry(AUTHOR_GROUP);
            noteGrant.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE));
            note.addAccessControlEntry(noteGrant);
            noteDao.store(note);
        }
        // and on note 2 only READ
        DocumentNote readOnly = noteDao.findById(2L, true);
        NoteAccessControlEntry readGrant = new NoteAccessControlEntry(AUTHOR_GROUP);
        readGrant.grantPermissions(EnumSet.of(Permission.READ));
        readOnly.addAccessControlEntry(readGrant);
        noteDao.store(readOnly);

        as("author");
        DocumentServiceImpl writer = new DocumentServiceImpl();
        GUIDocumentNote mine = writer.saveNote(newNote(DOC, "mine"));
        assertEquals(AUTHOR, noteDao.findById(mine.getId()).getUserId());
        mine.setMessage("mine, edited");
        writer.saveNote(mine);
        assertEquals("mine, edited", noteDao.findById(mine.getId()).getMessage());
        // The annotations window saves through saveNotes, and sends back every note it shows: note 2, which the
        // author only reads, is left as it is stored
        mine.setMessage("mine, annotated");
        GUIDocumentNote theirs = writer.getNote(2L);
        theirs.setMessage("changed by author");
        writer.saveNotes(DOC, null, List.of(mine, theirs), null);
        assertEquals("mine, annotated", noteDao.findById(mine.getId()).getMessage());
        assertEquals("message for note 2", message(2L));

        GUIDocumentNote edited = writer.getNote(1L);
        edited.setMessage("edited by author");
        writer.saveNote(edited);
        assertEquals("edited by author", message(1L));
        // Changing the note's ACL needs its SECURITY
        GUIDocumentNote widened = writer.getNote(1L);
        widened.setMessage("widened by author");
        widened.getAccessControlList().add(new GUIAccessControlEntry(-2L, Permission.READ.name()));
        refused(() -> writer.saveNote(widened));
        assertEquals("edited by author", message(1L));
        // Note 3 belongs to another document, which the author can't write, so it can't be passed off as DOC's
        refused(() -> writer.saveNote(note(3L, DOC, "moved by author")));
        assertEquals("message for note 3", message(3L));
        refused(() -> writer.deleteNotes(List.of(1L)));
        assertNotNull(noteDao.findById(1L));
        // Last: deleting a note updates the document in the background, which can undo a note change made meanwhile
        writer.deleteNotes(List.of(mine.getId()));
        assertNull(noteDao.findById(mine.getId()));

        // A link needs WRITE on one of its documents and READ on the other
        refused(() -> writer.linkDocuments(List.of(6L), List.of(DOC)));
        assertNull(DocumentLinkDAO.get().findByDocIdsAndType(6L, DOC, "default"));
        writer.linkDocuments(List.of(LINKED), List.of(DOC));
        assertNotNull(DocumentLinkDAO.get().findByDocIdsAndType(LINKED, DOC, "default"));
        writer.updateLink(1L, "author");
        assertEquals("author", DocumentLinkDAO.get().findById(1L).getType());
        writer.deleteLinks(List.of(1L));
        assertNull(DocumentLinkDAO.get().findById(1L));
    }

    @Test
    public void testFolderCallsNeedTheirPermission() throws Exception {
        // The tree's root has a grid, a tag and a template to hand down
        Folder root = folderDao.findById(TREE, true);
        root.setGrid("grid");
        root.addTag("pippo");
        root.setTemplate(TemplateDAO.get().findById(-1L));
        root.setValue("source", "test");
        folderDao.store(root);
        // In the admin's trash
        new DocumentServiceImpl().delete(List.of(OTHER));

        for (String username : List.of("boss", "author")) {
            as(username);
            FolderServiceImpl folders = new FolderServiceImpl();
            refused(() -> folders.applyGridLayout(TREE));
            refused(() -> folders.applyTags(TREE));
            refused(() -> folders.applyMetadata(TREE));
            refused(() -> folders.applyCapture(TREE));
            refused(() -> folders.computeStats(TREE));
            refused(() -> folders.deleteFromTrash(List.of(TRASHED_FOLDER)));
            refused(() -> new DocumentServiceImpl().deleteFromTrash(List.of(OTHER)));
            refused(() -> new DocumentServiceImpl().countDocuments(List.of(TREE), 0));

            MemoryResponse archive = archiveVersions();
            assertEquals(500, archive.getStatus());
            assertEquals("User %s cannot access the menu 8".formatted(username), archive.error());

            assertUnapplied(CHILD);
            assertEquals(1, folderDao.queryForInt("select ld_deleted from ld_folder where ld_id = " + TRASHED_FOLDER));
            assertEquals(1, docDao.queryForInt("select ld_deleted from ld_document where ld_id = " + OTHER));
        }

        // WRITE on every folder the tree hands down to
        as("admin");
        grantFolder(TREE, Permission.READ, Permission.WRITE);
        grantFolder(CHILD, Permission.READ, Permission.WRITE);
        as("author");
        refused(() -> new FolderServiceImpl().applyGridLayout(TREE));
        assertUnapplied(CHILD);

        as("admin");
        grantFolder(GRANDCHILD, Permission.READ, Permission.WRITE);
        // An alias in the tree takes its target's permissions; what the tree hands down stays on the alias
        long alias = new FolderServiceImpl().createAlias(CHILD, 7L).getId();
        assertFalse(folderDao.isPermissionAllowed(Permission.WRITE, 7L, AUTHOR));
        as("author");
        FolderServiceImpl folders = new FolderServiceImpl();
        folders.applyGridLayout(TREE);
        folders.applyTags(TREE);
        folders.applyMetadata(TREE);
        folders.applyCapture(TREE);
        assertEquals("grid", folderDao.findById(alias, true).getGrid());
        assertUnapplied(7L);
        for (long folderId : List.of(CHILD, GRANDCHILD)) {
            Folder folder = folderDao.findById(folderId, true);
            assertEquals("grid", folder.getGrid());
            assertTrue(folder.getTagsAsWords().contains("pippo"));
            assertEquals(-1L, folder.getTemplate().getId());
        }
        assertEquals(3, folders.computeStats(TREE).size());
        assertEquals(0L, new DocumentServiceImpl().countDocuments(List.of(TREE), 0));

        // The trash: what the user deleted
        folders.delete(List.of(AUTHORS_FOLDER));
        folders.deleteFromTrash(List.of(AUTHORS_FOLDER));
        assertEquals(2, folderDao.queryForInt("select ld_deleted from ld_folder where ld_id = " + AUTHORS_FOLDER));
        refused(() -> new FolderServiceImpl().deleteFromTrash(List.of(TRASHED_FOLDER)));

        as("admin");
        grant(LINKED, Permission.READ, Permission.DELETE);
        as("author");
        DocumentServiceImpl documents = new DocumentServiceImpl();
        documents.delete(List.of(LINKED));
        refused(() -> documents.deleteFromTrash(List.of(LINKED, 5L)));
        assertEquals(1, docDao.queryForInt("select ld_deleted from ld_document where ld_id = " + LINKED));
        documents.deleteFromTrash(List.of(LINKED));
        assertEquals(2, docDao.queryForInt("select ld_deleted from ld_document where ld_id = " + LINKED));
        // A live document isn't the user's trash
        refused(() -> documents.deleteFromTrash(List.of(5L)));

        // The admin purges any trashed item, but no live one
        as("admin");
        new DocumentServiceImpl().deleteFromTrash(List.of(OTHER, 5L));
        assertEquals(2, docDao.queryForInt("select ld_deleted from ld_document where ld_id = " + OTHER));
        assertEquals(0, docDao.queryForInt("select ld_deleted from ld_document where ld_id = 5"));
        new FolderServiceImpl().deleteFromTrash(List.of(TRASHED_FOLDER, CHILD));
        assertEquals(2, folderDao.queryForInt("select ld_deleted from ld_folder where ld_id = " + TRASHED_FOLDER));
        assertEquals(0, folderDao.queryForInt("select ld_deleted from ld_folder where ld_id = " + CHILD));
    }

    /**
     * Asserts that nothing the tree's root hands down reached the folder
     */
    private void assertUnapplied(long folderId) throws PersistenceException {
        Folder folder = folderDao.findById(folderId, true);
        assertNull(folder.getGrid());
        assertTrue(folder.getTagsAsWords().isEmpty());
        assertNull(folder.getTemplate());
    }

    /**
     * @return the note's message as stored, read with JDBC: CE's second-level
     *         cache can hold a note an RPC has just changed
     */
    private String message(long noteId) throws PersistenceException {
        return DocumentNoteDAO.get().queryForList("select ld_message from ld_note where ld_id = " + noteId, String.class)
                .get(0);
    }

    private void as(String username) throws ServerException {
        prepareSession(username, PASSWORD);
    }

    /**
     * Puts a file in the session's uploads, as UploadServlet does before a
     * check-in
     */
    private void upload() throws IOException {
        File file = new File(tempDir, "upload-%s.pdf".formatted(session.getUsername()));
        FileUtil.copyResource("pdf2.pdf", file);
        Map<String, File> uploads = new HashMap<>();
        uploads.put("pdf2.pdf", file);
        session.getDictionary().put(UploadServlet.UPLOADS, uploads);
    }

    /**
     * Adds an entry for the author's own group to the document's ACL
     */
    private void grant(long docId, Permission... permissions) throws PersistenceException {
        Document doc = docDao.findById(docId, true);
        DocumentAccessControlEntry entry = new DocumentAccessControlEntry(AUTHOR_GROUP);
        entry.grantPermissions(EnumSet.copyOf(List.of(permissions)));
        doc.addAccessControlEntry(entry);
        docDao.store(doc);
    }

    /**
     * Adds an entry for the author's own group to the folder's ACL
     */
    private void grantFolder(long folderId, Permission... permissions) throws PersistenceException {
        Folder folder = folderDao.findById(folderId, true);
        FolderAccessControlEntry entry = new FolderAccessControlEntry(AUTHOR_GROUP);
        entry.grantPermissions(EnumSet.copyOf(List.of(permissions)));
        folder.addAccessControlEntry(entry);
        folderDao.store(folder);
    }

    private MemoryResponse archiveVersions() throws Exception {
        MockServletRequest request = new MockServletRequest(servletSession);
        request.setParameter("archiveId", "1");
        MemoryResponse response = new MemoryResponse();
        new VersionsDataServlet().service((ServletRequest) request, (ServletResponse) response);
        return response;
    }

    private static void refused(ThrowingRunnable call) {
        assertThrows(AccessDeniedException.class, call);
    }

    private static GUIDocumentNote newNote(long docId, String message) {
        GUIDocumentNote note = new GUIDocumentNote();
        note.setDocId(docId);
        note.setMessage(message);
        return note;
    }

    private static GUIDocumentNote note(long noteId, long docId, String message) {
        GUIDocumentNote note = newNote(docId, message);
        note.setId(noteId);
        return note;
    }

    private static GUIBookmark bookmark(long bookmarkId, String name) {
        GUIBookmark bookmark = new GUIBookmark();
        bookmark.setId(bookmarkId);
        bookmark.setName(name);
        bookmark.setDescription(name);
        return bookmark;
    }

    private static GUIRating rating(long docId, long userId, int vote) {
        GUIRating rating = new GUIRating();
        rating.setDocId(docId);
        rating.setUserId(userId);
        rating.setVote(vote);
        return rating;
    }
}
