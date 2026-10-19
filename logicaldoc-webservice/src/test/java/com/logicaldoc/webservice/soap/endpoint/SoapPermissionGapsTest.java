package com.logicaldoc.webservice.soap.endpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.webservice.AbstractWebserviceTestCase;
import com.logicaldoc.webservice.WebserviceException;
import com.logicaldoc.webservice.model.WSDocument;

import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;

/**
 * The web-service calls that checked no permission, or checked the folder
 * where a document is read or written, listed under "Follow-ups found while
 * closing the gaps" in the M1 plan (principle 4). REST's endpoints extend
 * these, so this covers both. As in the webapp's {@code RpcPermissionGapsTest},
 * each call is refused to a user without the permission it needs and writes
 * nothing, and goes through for a user who has it. A grant in a document's own
 * ACL is enough (D7), and a document's narrower ACL holds where its folder
 * grants more. Admins keep CE's bypass.
 * <p>
 * The services check real sessions here, not the admin that CE's own tests
 * stand in for every caller. In the fixture, folder 80 gives the author's own
 * group READ, WRITE, ADD, DOWNLOAD, MOVE and EMAIL; the tree under 1200 is for
 * admins only, and boss has nothing anywhere. Three tests, because each one
 * builds its database again.
 */
public class SoapPermissionGapsTest extends AbstractWebserviceTestCase {

    /** The password of the fixture's users */
    private static final String PASSWORD = "admin";

    /** The password the tests put on a document */
    private static final String DOC_PASSWORD = "Kx7!Rmq-Tz8vW";

    /** The author's own group */
    private static final long AUTHOR_GROUP = -4L;

    /** A folder where the author's groups have READ, WRITE, ADD, DOWNLOAD, MOVE and EMAIL */
    private static final long AUTHORS_FOLDER = 80L;

    /** A folder in the root for admins only, which a test opens to the author to merge into */
    private static final long MERGE_TARGET = 99L;

    /**
     * A tree for admins only: 1200, its child 1201, the child's children 1202
     * and 1203, and 1204, a folder alias in 1200
     */
    private static final long TREE = 1200L;

    private static final long CHILD = 1201L;

    /** The child's children, and the folder alias in the tree */
    private static final List<Long> REST_OF_TREE = List.of(1202L, 1203L, 1204L);

    private SoapDocumentService documents;

    private SoapFolderService folders;

    private DocumentDAO docDao;

    private FolderDAO folderDao;

    private final List<String> sids = new ArrayList<>();

    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        docDao = DocumentDAO.get();
        folderDao = FolderDAO.get();
        documents = new SoapDocumentService();
        folders = new SoapFolderService();
    }

    @Override
    public void tearDown() throws IOException {
        for (String sid : sids)
            SessionManager.get().kill(sid);
        super.tearDown();
    }

    @Test
    public void testDocumentCallsNeedTheirPermission() throws Exception {
        // Two documents in the author's folder, which now gives DELETE too; one's own ACL gives the author READ only
        long open = create("open.txt", AUTHORS_FOLDER);
        long narrow = create("narrow.txt", AUTHORS_FOLDER);
        long granted = create("granted.txt", TREE);
        grant(narrow, Permission.READ);
        grantFolder(AUTHORS_FOLDER, Permission.READ, Permission.WRITE, Permission.ADD, Permission.DOWNLOAD,
                Permission.MOVE, Permission.EMAIL, Permission.DELETE);
        int inAuthorsFolder = docDao.findByFolder(AUTHORS_FOLDER, null).size();

        // Boss has no grant; the author reads the narrow document, and can't read the tree
        for (String username : List.of("boss", "author")) {
            String sid = login(username);
            refused(() -> documents.copy(sid, narrow, AUTHORS_FOLDER, false, false, false));
            refused(() -> documents.delete(sid, narrow));
            refused(() -> documents.delete(sid, granted));
            refusedWs(() -> documents.sendEmail(sid, List.of(narrow), "ken@acme.com", "Documents", "Here"));
            refusedWs(() -> documents.sendEmail(sid, List.of(open, narrow), "ken@acme.com", "Documents", "Here"));
            refusedWs(() -> documents.sendEmail(sid, List.of(granted), "ken@acme.com", "Documents", "Here"));

            assertEquals(inAuthorsFolder, docDao.findByFolder(AUTHORS_FOLDER, null).size());
            assertNotNull(docDao.findById(narrow));
            assertNotNull(docDao.findById(granted));
            for (long docId : List.of(open, narrow, granted))
                assertEquals(0L, sent(docId));
        }
        WebserviceException e = assertThrows(WebserviceException.class,
                () -> documents.sendEmail(login("author"), List.of(open, narrow), "ken@acme.com", "Documents",
                        "Here"));
        assertEquals("User author doesn't have permission DOWNLOAD on document %d".formatted(narrow), e.getMessage());

        // Trying a password needs READ
        documents.setPassword(session.getSid(), open, DOC_PASSWORD);
        String boss = login("boss");
        refusedWs(() -> documents.unprotect(boss, open, DOC_PASSWORD));
        assertFalse(SessionManager.get().get(boss).getUnprotectedDocs().containsKey(open));
        assertTrue(documents.unprotect(login("author"), open, DOC_PASSWORD));

        // A grant in the document's own ACL, in a folder the author can't read (D7)
        grant(granted, Permission.READ, Permission.DOWNLOAD, Permission.EMAIL, Permission.DELETE);
        String author = login("author");
        WSDocument copy = documents.copy(author, granted, AUTHORS_FOLDER, false, false, false);
        assertEquals(AUTHORS_FOLDER, copy.getFolderId().longValue());
        assertEquals(inAuthorsFolder + 1, docDao.findByFolder(AUTHORS_FOLDER, null).size());
        documents.sendEmail(author, List.of(open, granted), "ken@acme.com", "Documents", "Here");
        assertEquals(1L, sent(open));
        assertEquals(1L, sent(granted));
        documents.delete(author, granted);
        assertNull(docDao.findById(granted));
    }

    @Test
    public void testFolderCopyNeedsItsPermission() throws Exception {
        long inChild = create("child.txt", CHILD);
        String copies = "select count(*) from ld_document where ld_deleted = 0 and ld_filename = 'child.txt'";

        for (String username : List.of("boss", "author")) {
            String sid = login(username);
            refused(() -> folders.copy(sid, TREE, AUTHORS_FOLDER, 0, "inherit"));
            refused(() -> folders.copy(sid, TREE, AUTHORS_FOLDER, 1, "inherit"));

            assertTrue(folderDao.findByNameAndParentId("test", AUTHORS_FOLDER).isEmpty());
            assertEquals(1, docDao.queryForInt(copies));
        }

        // READ on the tree and its child, whose document takes the child's READ only
        grantFolder(TREE, Permission.READ);
        grantFolder(CHILD, Permission.READ);
        String author = login("author");
        refused(() -> folders.copy(author, TREE, AUTHORS_FOLDER, 0, "inherit"));
        assertTrue(folderDao.findByNameAndParentId("test", AUTHORS_FOLDER).isEmpty());

        // Folders only need READ; what the author can't read isn't copied, as in CE
        folders.copy(author, TREE, AUTHORS_FOLDER, 1, "inherit");
        Folder copy = folderDao.findByNameAndParentId("test", AUTHORS_FOLDER).get(0);
        Folder copiedChild = folderDao.findByNameAndParentId("abc", copy.getId()).get(0);
        assertTrue(folderDao.findByParentId(copiedChild.getId()).isEmpty());
        assertEquals(1, docDao.queryForInt(copies));

        grant(inChild, Permission.READ, Permission.DOWNLOAD);
        folders.copy(author, TREE, AUTHORS_FOLDER, 0, "inherit");
        assertEquals(2, docDao.queryForInt(copies));
    }

    @Test
    public void testFolderDeleteAndMergeNeedTheirPermission() throws Exception {
        long inChild = create("child.txt", CHILD);
        grantFolder(MERGE_TARGET, Permission.READ, Permission.WRITE, Permission.ADD, Permission.DELETE);
        // The author may move and delete the tree and its child, but not the rest of the tree
        grantFolder(TREE, Permission.READ, Permission.MOVE, Permission.DELETE);
        grantFolder(CHILD, Permission.READ, Permission.MOVE, Permission.DELETE);

        // The child is merged, not the tree: CE's merge fails on a tree that holds a folder alias
        for (String username : List.of("boss", "author")) {
            String sid = login(username);
            refused(() -> folders.delete(sid, TREE));
            refused(() -> folders.merge(sid, CHILD, MERGE_TARGET));

            assertEquals(0, deleted("ld_folder", TREE));
            assertEquals(0, deleted("ld_folder", CHILD));
            assertEquals(TREE, folderDao.findById(CHILD).getParentId());
            assertEquals(CHILD, docDao.findById(inChild).getFolder().getId());
            assertEquals(0, deleted("ld_document", inChild));
        }

        // The whole tree now, but the document's own ACL gives the author READ only
        for (long folderId : REST_OF_TREE)
            grantFolder(folderId, Permission.READ, Permission.MOVE, Permission.DELETE);
        grant(inChild, Permission.READ);
        String author = login("author");
        refused(() -> folders.delete(author, TREE));
        assertEquals(0, deleted("ld_folder", TREE));
        assertEquals(0, deleted("ld_document", inChild));

        // A merge moves the documents, and needs nothing on them
        folders.merge(author, CHILD, MERGE_TARGET);
        assertEquals(MERGE_TARGET, docDao.findById(inChild).getFolder().getId());
        assertEquals(1, deleted("ld_folder", CHILD));

        // The folder merged into now holds the document
        refused(() -> folders.delete(author, MERGE_TARGET));
        assertEquals(0, deleted("ld_folder", MERGE_TARGET));
        assertEquals(0, deleted("ld_document", inChild));
        grant(inChild, Permission.READ, Permission.DELETE);
        folders.delete(author, MERGE_TARGET);
        assertEquals(1, deleted("ld_folder", MERGE_TARGET));
        assertEquals(1, deleted("ld_document", inChild));
    }

    /**
     * Opens a session for the user, as a web-service client logs in
     */
    private String login(String username) {
        String sid = SessionManager.get().newSession(username, PASSWORD, (Client) null).getSid();
        sids.add(sid);
        return sid;
    }

    /**
     * Uploads a document as the admin
     */
    private long create(String fileName, long folderId) throws Exception {
        WSDocument document = new WSDocument();
        document.setFolderId(folderId);
        document.setFileName(fileName);
        document.setLanguage("en");
        return documents
                .create(session.getSid(), document, new DataHandler(new FileDataSource(new File("pom.xml"))))
                .getId();
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

    private long sent(long docId) throws PersistenceException {
        return DocumentHistoryDAO.get().findByDocId(docId).stream()
                .filter(h -> DocumentEvent.SENT.toString().equals(h.getEvent())).count();
    }

    private int deleted(String table, long id) throws PersistenceException {
        return docDao.queryForInt("select ld_deleted from %s where ld_id = %d".formatted(table, id));
    }

    private static void refused(ThrowingRunnable call) {
        assertThrows(PermissionException.class, call);
    }

    /**
     * The calls whose signature declares no {@link PermissionException} refuse
     * with a {@link WebserviceException}, as CE's group check does
     */
    private static void refusedWs(ThrowingRunnable call) {
        assertThrows(WebserviceException.class, call);
    }
}
