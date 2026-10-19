package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.communication.EMailSender;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentNote;
import com.logicaldoc.core.document.DocumentNoteDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.document.NoteAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIContact;
import com.logicaldoc.gui.common.client.beans.GUIDocument;
import com.logicaldoc.gui.common.client.beans.GUIDocumentNote;
import com.logicaldoc.gui.common.client.beans.GUIEmail;
import com.logicaldoc.gui.frontend.client.clipboard.Clipboard;
import com.logicaldoc.util.io.FileUtil;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.MemoryResponse;
import com.logicaldoc.web.UploadServlet;
import com.logicaldoc.web.data.DeletedDocsDataServlet;
import com.logicaldoc.web.data.DeletedFoldersDataServlet;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;

/**
 * More CE calls that checked no permission, or checked the folder where a
 * document is read or written, listed under "Follow-ups found while closing PR
 * C's" in the M1 plan (principle 4). As in {@link RpcPermissionTest}, each call
 * is refused to a user without the permission it needs and writes nothing, and
 * goes through for a user who has it. A grant in a document's own ACL is
 * enough (D7), and a document's narrower ACL holds where its folder grants
 * more. Admins keep CE's bypass.
 * <p>
 * The fixture's documents are in the root, where the author's groups have READ
 * and boss has nothing; in folder 6 the author's groups have READ, WRITE, ADD,
 * DELETE, RENAME, MOVE, DOWNLOAD and EMAIL. The core plugin gives CE its format
 * converters. Three tests, because each one builds its database again.
 */
public class RpcPermissionGapsTest extends AbstractWPTestCase {

    /** The password of the fixture's users */
    private static final String PASSWORD = "admin";

    /** The password the tests put on a document */
    private static final String DOC_PASSWORD = "Kx7!Rmq-Tz8vW";

    /** The author's own group */
    private static final long AUTHOR_GROUP = -4L;

    /** Sebastian's own group, which the author isn't in */
    private static final long OTHER_GROUP = -3L;

    /** A fixture document with a PDF file, notes 1 and 2 and a shortcut */
    private static final long DOC = 1L;

    /** A fixture document with a PDF file */
    private static final long LINKED = 3L;

    /** A fixture document */
    private static final long OTHER = 4L;

    /** A fixture document, an email, which the tests send */
    private static final long EMAILED = 5L;

    /** A fixture document, an email with version 1.0, which the tests convert and check in */
    private static final long CHECKIN = 7L;

    /** A tree of the fixture: 1200, its child 1201 and its grandchild 1202 */
    private static final long TREE = 1200L;

    private static final long CHILD = 1201L;

    private static final long GRANDCHILD = 1202L;

    /** A folder where the author's groups have READ, WRITE, ADD, DELETE, RENAME, MOVE, DOWNLOAD and EMAIL */
    private static final long AUTHORS_FOLDER = 6L;

    private DocumentDAO docDao;

    private FolderDAO folderDao;

    @Override
    protected List<String> getPluginArchives() {
        return List.of("/logicaldoc-core-plugin.jar");
    }

    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        docDao = DocumentDAO.get();
        folderDao = FolderDAO.get();
    }

    @Override
    public void tearDown() throws IOException {
        DocumentServiceImpl.setEmailSender(null);
        SearchEngine.get().unlock();
        SearchEngine.get().close();
        super.tearDown();
    }

    @Test
    public void testDocumentContentNeedsItsPermission() throws Exception {
        // Files for documents 5 and 7, both in the author's folder, where document 7's own ACL gives the author READ
        storeFile("Joyce Jinks shared the Bruce Duo post.eml", EMAILED);
        storeFile("New error indexing documents.eml", CHECKIN);
        move(EMAILED, AUTHORS_FOLDER);
        move(CHECKIN, AUTHORS_FOLDER);
        grant(CHECKIN, Permission.READ);
        // ARCHIVE on the author's folder, a password on document 4, and note 1 for another group only
        grantFolder(AUTHORS_FOLDER, Permission.READ, Permission.ARCHIVE);
        new DocumentServiceImpl().setPassword(OTHER, DOC_PASSWORD);
        restrictNote(1L, OTHER_GROUP);
        EMailSender sender = mock(EMailSender.class);
        DocumentServiceImpl.setEmailSender(sender);
        long fileSize = fileSize(DOC);
        int inAuthorsFolder = docDao.findByFolder(AUTHORS_FOLDER, null).size();

        // Boss has no grant; the author reads the root, and document 7 in spite of the folder
        for (String username : List.of("boss", "author")) {
            as(username);
            DocumentServiceImpl documents = new DocumentServiceImpl();
            upload();
            refused(() -> documents.delete(List.of(DOC)));
            refused(() -> documents.delete(List.of(CHECKIN)));
            refused(() -> documents.replaceFile(DOC, "1.0", "replaced"));
            refused(() -> documents.checkinContent(CHECKIN, "checked in"));
            refused(() -> documents.promoteVersion(CHECKIN, "1.0"));
            refused(() -> documents.convert(CHECKIN, "1.0", "pdf"));
            refused(() -> documents.unsetPassword(OTHER, DOC_PASSWORD));
            refused(() -> documents.archiveDocuments(List.of(CHECKIN), "archived"));
            refused(() -> documents.sendAsEmail(email(CHECKIN), "en"));
            refused(() -> documents.getNote(1L));

            assertNotNull(docDao.findById(DOC));
            assertNotNull(docDao.findById(CHECKIN));
            assertEquals(fileSize, fileSize(DOC));
            assertEquals("1.0", docDao.findById(CHECKIN).getVersion());
            assertEquals(DocumentStatus.UNLOCKED, docDao.findById(CHECKIN).getStatus());
            assertEquals(inAuthorsFolder, docDao.findByFolder(AUTHORS_FOLDER, null).size());
            assertNotNull(docDao.findById(OTHER).getPassword());
            verify(sender, never()).send(any(EMail.class));
        }

        as("boss");
        refused(() -> new DocumentServiceImpl().getNotes(DOC, null, null));
        refused(() -> new DocumentServiceImpl().unprotect(OTHER, DOC_PASSWORD));
        assertFalse(session.getUnprotectedDocs().containsKey(OTHER));

        // What needs READ: the notes CE lists to the author, without note 1, and trying a password
        as("author");
        DocumentServiceImpl documents = new DocumentServiceImpl();
        assertEquals(List.of(2L), documents.getNotes(DOC, null, null).stream().map(GUIDocumentNote::getId).toList());
        assertEquals("message for note 2", documents.getNote(2L).getMessage());
        assertTrue(documents.unprotect(OTHER, DOC_PASSWORD));

        // Document 5 takes the folder's DOWNLOAD, EMAIL and ARCHIVE; document 7's own ACL holds
        refused(() -> documents.sendAsEmail(email(EMAILED, CHECKIN), "en"));
        verify(sender, never()).send(any(EMail.class));
        assertEquals("ok", documents.sendAsEmail(email(EMAILED), "en"));
        verify(sender, times(1)).send(any(EMail.class));
        refused(() -> documents.archiveDocuments(List.of(EMAILED, CHECKIN), "archived"));
        assertNotEquals(DocumentStatus.ARCHIVED, docDao.findById(EMAILED).getStatus());
        documents.archiveDocuments(List.of(EMAILED), "archived");
        assertEquals(DocumentStatus.ARCHIVED, docDao.findById(EMAILED).getStatus());

        // Grants in the documents' own ACLs (D7)
        as("admin");
        grant(DOC, Permission.READ, Permission.WRITE, Permission.DELETE);
        grant(CHECKIN, Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
        grant(OTHER, Permission.READ, Permission.PASSWORD);
        as("author");
        DocumentServiceImpl writer = new DocumentServiceImpl();

        GUIDocument conversion = writer.convert(CHECKIN, "1.0", "pdf");
        assertTrue(conversion.getFileName().endsWith(".pdf"));
        assertEquals(AUTHORS_FOLDER, docDao.findById(conversion.getId()).getFolder().getId());

        // A check-in needs the user's own checkout too (R59)
        refused(() -> writer.checkinContent(CHECKIN, "checked in"));
        writer.checkout(List.of(CHECKIN));
        writer.checkinContent(CHECKIN, "checked in");
        assertEquals("1.1", docDao.findById(CHECKIN).getVersion());
        writer.promoteVersion(CHECKIN, "1.0");
        assertEquals("1.2", docDao.findById(CHECKIN).getVersion());

        upload();
        writer.replaceFile(DOC, "1.0", "replaced");
        assertNotEquals(fileSize, fileSize(DOC));

        writer.unsetPassword(OTHER, DOC_PASSWORD);
        assertNull(docDao.findById(OTHER).getPassword());

        // A document that isn't there is skipped, as CE skips it
        writer.delete(List.of(DOC, 987654L));
        assertNull(docDao.findById(DOC));
    }

    @Test
    public void testCopiesNeedTheirPermission() throws Exception {
        // Document 1 becomes a PDF to merge, with the file version the fixture lacks; document 3 goes into the tree's child
        Document pdf = docDao.findById(DOC, true);
        pdf.setFileName("one.pdf");
        pdf.setFileVersion("1.0");
        docDao.store(pdf);
        move(LINKED, CHILD);
        // A shortcut to document 1 in the author's folder, whose own ACL gives the author READ
        DocumentHistory transaction = new DocumentHistory();
        transaction.setSession(session);
        Document alias = DocumentManager.get().createAlias(docDao.findById(DOC), folderDao.findById(AUTHORS_FOLDER),
                null, transaction);
        grant(alias.getId(), Permission.READ);
        int inAuthorsFolder = docDao.findByFolder(AUTHORS_FOLDER, null).size();

        for (String username : List.of("boss", "author")) {
            as(username);
            DocumentServiceImpl documents = new DocumentServiceImpl();
            FolderServiceImpl folders = new FolderServiceImpl();
            refused(() -> documents.merge(List.of(DOC), AUTHORS_FOLDER, "merged"));
            refused(() -> documents.replaceAlias(alias.getId()));
            refused(() -> folders.pasteAsAlias(List.of(DOC), AUTHORS_FOLDER, null));
            refused(() -> folders.copyFolders(List.of(TREE), AUTHORS_FOLDER, false, "inherit", null));

            assertEquals(inAuthorsFolder, docDao.findByFolder(AUTHORS_FOLDER, null).size());
            assertNotNull(docDao.findById(alias.getId()));
            assertTrue(folderDao.findByNameAndParentId("test", AUTHORS_FOLDER).isEmpty());
        }
        // The author may write the folder, but can't download document 1
        as("author");
        refused(() -> new FolderServiceImpl().paste(List.of(DOC), AUTHORS_FOLDER, Clipboard.COPY, false, false,
                false));
        assertEquals(inAuthorsFolder, docDao.findByFolder(AUTHORS_FOLDER, null).size());

        as("admin");
        grant(DOC, Permission.READ, Permission.DOWNLOAD);
        grant(alias.getId(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
        grantFolder(TREE, Permission.READ);
        grantFolder(CHILD, Permission.READ);
        as("author");
        DocumentServiceImpl documents = new DocumentServiceImpl();
        FolderServiceImpl folders = new FolderServiceImpl();

        // Merging needs DOWNLOAD on each document and WRITE on the folder the PDF goes in
        refused(() -> documents.merge(List.of(DOC, LINKED), AUTHORS_FOLDER, "merged"));
        refused(() -> documents.merge(List.of(DOC), TREE, "merged"));
        assertEquals(inAuthorsFolder, docDao.findByFolder(AUTHORS_FOLDER, null).size());
        assertEquals("merged.pdf", documents.merge(List.of(DOC), AUTHORS_FOLDER, "merged").getFileName());
        assertEquals(inAuthorsFolder + 1, docDao.findByFolder(AUTHORS_FOLDER, null).size());

        // The shortcut becomes a copy of document 1
        GUIDocument replaced = documents.replaceAlias(alias.getId());
        assertNull(docDao.findById(alias.getId()));
        assertNull(docDao.findById(replaced.getId()).getDocRef());

        // A shortcut needs WRITE on the folder too; a copy needs the folder's WRITE, as in CE
        refused(() -> folders.pasteAsAlias(List.of(DOC), TREE, null));
        folders.pasteAsAlias(List.of(DOC), AUTHORS_FOLDER, null);
        folders.paste(List.of(DOC), AUTHORS_FOLDER, Clipboard.COPY, false, false, false);
        assertEquals(inAuthorsFolder + 3, docDao.findByFolder(AUTHORS_FOLDER, null).size());

        // Copying a folder: document 3, in the child, takes the child's READ only
        refused(() -> folders.copyFolders(List.of(TREE), AUTHORS_FOLDER, false, "inherit", null));
        assertTrue(folderDao.findByNameAndParentId("test", AUTHORS_FOLDER).isEmpty());
        // Folders only need READ; the grandchild, which the author can't read, isn't copied, as in CE
        folders.copyFolders(List.of(TREE), AUTHORS_FOLDER, true, "inherit", null);
        Folder copy = folderDao.findByNameAndParentId("test", AUTHORS_FOLDER).get(0);
        Folder copiedChild = folderDao.findByNameAndParentId("ABC", copy.getId()).get(0);
        assertTrue(folderDao.findByNameAndParentId("xyz", copiedChild.getId()).isEmpty());
        assertTrue(docDao.findByFolder(copiedChild.getId(), null).isEmpty());

        as("admin");
        grant(LINKED, Permission.READ, Permission.DOWNLOAD);
        as("author");
        String copies = "select count(*) from ld_document where ld_deleted = 0 and ld_filename = 'test.zip'";
        assertEquals(1, docDao.queryForInt(copies));
        new FolderServiceImpl().copyFolders(List.of(TREE), AUTHORS_FOLDER, false, "inherit", null);
        assertEquals(2, docDao.queryForInt(copies));
    }

    @Test
    public void testFolderTreesAndTrashNeedTheirPermission() throws Exception {
        // Document 4 in the tree's child; the author may move and delete the tree but its grandchild
        move(OTHER, CHILD);
        grantFolder(TREE, Permission.READ, Permission.MOVE, Permission.DELETE);
        grantFolder(CHILD, Permission.READ, Permission.MOVE, Permission.DELETE);
        // In the admin's trash
        new DocumentServiceImpl().delete(List.of(LINKED));

        for (String username : List.of("boss", "author")) {
            as(username);
            FolderServiceImpl folders = new FolderServiceImpl();
            refused(() -> folders.delete(List.of(TREE)));
            refused(() -> folders.merge(List.of(TREE), AUTHORS_FOLDER));

            assertEquals(0, deleted("ld_folder", TREE));
            assertEquals(TREE, folderDao.findById(CHILD).getParentId());
            assertEquals(0, deleted("ld_document", OTHER));

            // The trash reports list what the user deleted, and nobody else's
            for (HttpServlet report : List.of(new DeletedDocsDataServlet(), new DeletedFoldersDataServlet())) {
                MemoryResponse others = trash(report, "1");
                assertEquals(500, others.getStatus());
                assertEquals("User %s can only list what they deleted".formatted(username), others.error());
            }
            assertFalse(trash(new DeletedDocsDataServlet(), null).text().contains("<id>3</id>"));
            assertFalse(trash(new DeletedFoldersDataServlet(), null).text().contains("<id>8</id>"));
        }
        // The admin has the reports' menus
        as("admin");
        assertTrue(trash(new DeletedDocsDataServlet(), null).text().contains("<id>3</id>"));
        assertTrue(trash(new DeletedFoldersDataServlet(), null).text().contains("<id>8</id>"));

        // DELETE on the grandchild too, but document 4's own ACL gives the author READ
        grantFolder(GRANDCHILD, Permission.READ, Permission.MOVE, Permission.DELETE);
        grant(OTHER, Permission.READ);
        as("author");
        FolderServiceImpl folders = new FolderServiceImpl();
        refused(() -> folders.delete(List.of(TREE)));
        assertEquals(0, deleted("ld_folder", TREE));
        assertEquals(0, deleted("ld_document", OTHER));

        // A merge moves the documents, and needs nothing on them
        folders.merge(List.of(TREE), AUTHORS_FOLDER);
        assertEquals(AUTHORS_FOLDER, folderDao.findById(CHILD).getParentId());
        assertEquals(1, deleted("ld_folder", TREE));

        // Folder 6 now holds the child, with document 4
        refused(() -> folders.delete(List.of(AUTHORS_FOLDER)));
        assertEquals(0, deleted("ld_folder", AUTHORS_FOLDER));
        as("admin");
        grant(OTHER, Permission.READ, Permission.DELETE);
        as("author");
        new FolderServiceImpl().delete(List.of(AUTHORS_FOLDER));
        assertEquals(1, deleted("ld_folder", AUTHORS_FOLDER));
        assertEquals(1, deleted("ld_document", OTHER));
        assertTrue(trash(new DeletedFoldersDataServlet(), null).text().contains("<id>6</id>"));
        String ownDocuments = trash(new DeletedDocsDataServlet(), null).text();
        assertTrue(ownDocuments.contains("<id>4</id>"));
        assertFalse(ownDocuments.contains("<id>3</id>"));

        // A refused restore reaches the user; CE logged it and returned
        as("admin");
        HealdanHooks previous = HealdanHooks.get();
        HealdanHooks.register(new HealdanHooks() {
            @Override
            public void beforeDocumentRestore(long docId, long folderId, DocumentHistory transaction)
                    throws PersistenceException {
                throw new HealdanVetoException("Not into this folder.");
            }
        });
        try {
            ServerException e = assertThrows(ServerException.class,
                    () -> new DocumentServiceImpl().restore(List.of(LINKED), Folder.DEFAULTWORKSPACEID));
            assertEquals("Not into this folder.", e.getMessage());
        } finally {
            HealdanHooks.register(previous);
        }
        assertEquals(1, deleted("ld_document", LINKED));
    }

    private void as(String username) throws ServerException {
        prepareSession(username, PASSWORD);
    }

    /**
     * Puts a file in the session's uploads, as UploadServlet does before a
     * file is replaced
     */
    private void upload() throws IOException {
        File file = new File(tempDir, "upload-%s.pdf".formatted(session.getUsername()));
        FileUtil.copyResource("pdf2.pdf", file);
        Map<String, File> uploads = new HashMap<>();
        uploads.put("pdf2.pdf", file);
        session.getDictionary().put(UploadServlet.UPLOADS, uploads);
    }

    /**
     * Puts a resource in the Store as version 1.0 of the document's file, which
     * the fixture lacks
     */
    private void storeFile(String resource, long docId) throws IOException {
        File file = new File(repositoryDir, "docs/%d/doc/1.0".formatted(docId));
        file.getParentFile().mkdirs();
        FileUtil.copyResource(resource, file);
    }

    private long fileSize(long docId) {
        return Store.get().size(StoreResource.builder().docId(docId).fileVersion("1.0").build());
    }

    private void move(long docId, long folderId) throws PersistenceException {
        Document doc = docDao.findById(docId, true);
        doc.setFolder(folderDao.findById(folderId));
        docDao.store(doc);
    }

    /**
     * Gives the author's own group exactly these permissions in the
     * document's ACL
     */
    private void grant(long docId, Permission... permissions) throws PersistenceException {
        Document doc = docDao.findById(docId, true);
        doc.removeAccessControlEntries(AUTHOR_GROUP);
        DocumentAccessControlEntry entry = new DocumentAccessControlEntry(AUTHOR_GROUP);
        entry.grantPermissions(EnumSet.copyOf(List.of(permissions)));
        doc.addAccessControlEntry(entry);
        docDao.store(doc);
    }

    /**
     * Gives the author's own group exactly these permissions in the folder's
     * ACL
     */
    private void grantFolder(long folderId, Permission... permissions) throws PersistenceException {
        Folder folder = folderDao.findById(folderId, true);
        folder.removeAccessControlEntries(AUTHOR_GROUP);
        FolderAccessControlEntry entry = new FolderAccessControlEntry(AUTHOR_GROUP);
        entry.grantPermissions(EnumSet.copyOf(List.of(permissions)));
        folder.addAccessControlEntry(entry);
        folderDao.store(folder);
    }

    /**
     * Lets only the group read the note: CE lists a note without an ACL to
     * whoever reads its document
     */
    private void restrictNote(long noteId, long groupId) throws PersistenceException {
        DocumentNoteDAO noteDao = DocumentNoteDAO.get();
        DocumentNote note = noteDao.findById(noteId, true);
        NoteAccessControlEntry entry = new NoteAccessControlEntry(groupId);
        entry.grantPermissions(EnumSet.of(Permission.READ));
        note.addAccessControlEntry(entry);
        noteDao.store(note);
    }

    private int deleted(String table, long id) throws PersistenceException {
        return docDao.queryForInt("select ld_deleted from %s where ld_id = %d".formatted(table, id));
    }

    private MemoryResponse trash(HttpServlet report, String userId) throws Exception {
        MockServletRequest request = new MockServletRequest(servletSession);
        if (userId != null)
            request.setParameter("userId", userId);
        MemoryResponse response = new MemoryResponse();
        report.service((ServletRequest) request, (ServletResponse) response);
        return response;
    }

    private static GUIEmail email(Long... docIds) {
        GUIEmail email = new GUIEmail();
        email.setDocIds(List.of(docIds));
        email.setTos(List.of(new GUIContact("Kenneth", "Botterill", "ken-botterill@acme.com")));
        email.setSubject("Documents");
        email.setMessage("Here they are");
        return email;
    }

    private static void refused(ThrowingRunnable call) {
        assertThrows(AccessDeniedException.class, call);
    }
}
