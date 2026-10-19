package com.logicaldoc.web.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.beans.GUIAccessControlEntry;
import com.logicaldoc.gui.common.client.beans.GUIDocument;
import com.logicaldoc.gui.common.client.beans.GUIFolder;
import com.logicaldoc.gui.common.client.beans.GUIVersion;
import com.logicaldoc.util.io.ResourceUtil;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.ConvertPdf;
import com.logicaldoc.web.DownloadServlet;
import com.logicaldoc.web.MemoryResponse;
import com.logicaldoc.web.data.VersionsDataServlet;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Roadmap M1: a document can be granted to people who can't see its folder, as
 * a one-off exception (decision D7). From the hit a doc # lookup gives them,
 * they can open the document, preview it, download it and see its versions
 * (spec §8.1), with what the document's own ACL grants them, while its folder
 * stays out of their reach.
 * <p>
 * Alice isn't in IT. A PDF in Secured Documents, a folder only IT can read,
 * gives Alice READ, DOWNLOAD and PREVIEW in its own ACL, Carol (not in IT
 * either) READ and WRITE, and IT READ, WRITE, DOWNLOAD and PREVIEW. Bob has no
 * grant at all. The doc # lookup itself is Healdan's, so healdan-core's
 * {@code DocumentOnlyGrantLookupTest} covers it.
 * <p>
 * Reading the document, listing its versions and saving its properties need
 * READ, READ and WRITE on the document (T19-c), and a new file name RENAME
 * (T19-f): CE checked none of them. A grantee who can't read the folder sees
 * only its id and name (T19-e).
 * <p>
 * Two tests, because each test builds its database and its document again,
 * which takes seconds.
 */
public class DocumentOnlyGrantTest extends AbstractWPTestCase {

    /** The password of the users this test creates */
    private static final String PASSWORD = "Kx7!Rmq-Tz8vW";

    /** A fixture document, in a folder Bob can't read, that Bob is given WRITE on */
    private static final long OWN_DOC = 4L;

    private Folder secured;

    private Document doc;

    private Version version;

    /** The PDF's bytes */
    private byte[] content;

    @Override
    protected List<String> getPluginArchives() {
        return List.of("/logicaldoc-core-plugin.jar");
    }

    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        try {
            createCase();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Test
    public void testGranteesReachTheDocument() throws Exception {
        long alice = UserDAO.get().findByUsername("alice").getId();
        long carol = UserDAO.get().findByUsername("carol").getId();
        assertFalse(FolderDAO.get().isReadAllowed(secured.getId(), alice));
        assertEquals(EnumSet.of(Permission.READ, Permission.DOWNLOAD, Permission.PREVIEW),
                DocumentDAO.get().getAllowedPermissions(doc.getId(), alice));
        assertFalse(FolderDAO.get().isReadAllowed(secured.getId(), carol));
        assertEquals(EnumSet.of(Permission.READ, Permission.WRITE),
                DocumentDAO.get().getAllowedPermissions(doc.getId(), carol));
        // The admin, whose session setUp opened, gets the folder, and the whole of it with the document
        assertNotNull(new FolderServiceImpl().getFolder(secured.getId(), false, false, false));
        GUIFolder whole = new DocumentServiceImpl().getById(doc.getId()).getFolder();
        assertEquals("For IT only", whole.getDescription());
        assertEquals(List.of("it@example.com"), whole.getQuotaAlertRecipients());
        assertNotNull(whole.getCreator());

        prepareSession("alice", PASSWORD);

        // How SearchServiceImpl.search turns a document hit into the row the GUI shows
        GUIDocument hit = DocumentServiceImpl.fromDocument(DocumentDAO.get().findById(doc.getId(), true), null,
                null);
        assertEquals(doc.getId(), hit.getId());
        assertEquals("secured.pdf", hit.getFileName());
        assertEquals(secured.getId(), hit.getFolder().getId());
        assertEquals("/Default/Secured Documents", hit.getPathExtended());

        // Selecting the hit: SearchPanel.onSelectedDocumentHit calls getById, then
        // DocumentDetailsPanel.setDocument calls getAllowedPermissions to set up its tabs
        DocumentServiceImpl documents = new DocumentServiceImpl();
        GUIDocument opened = documents.getById(doc.getId());
        assertEquals(doc.getId(), opened.getId());
        assertEquals("secured.pdf", opened.getFileName());
        assertEquals(Set.of("READ", "DOWNLOAD", "PREVIEW"), allowed(opened.getAllowedPermissions()));
        assertEquals(Set.of("READ", "DOWNLOAD", "PREVIEW"),
                allowed(documents.getAllowedPermissions(List.of(doc.getId()))));
        // Of the folder, only its id and name (T19-e)
        GUIFolder stub = opened.getFolder();
        assertEquals(secured.getId(), stub.getId());
        assertEquals("Secured Documents", stub.getName());
        assertNull(stub.getDescription());
        assertNull(stub.getCreator());
        assertNull(stub.getCreatorId());
        assertTrue(stub.getQuotaAlertRecipients().isEmpty());
        assertTrue(allowed(stub.getAllowedPermissions()).isEmpty());

        // The Versions tab lists /data/versions.xml?docId=, and comparing two versions calls getVersionsById
        MemoryResponse versionsXml = versionsXml();
        assertNull(versionsXml.error());
        String xml = versionsXml.text();
        assertTrue(xml, xml.contains("<docid>%d</docid>".formatted(doc.getId())));
        assertTrue(xml, xml.contains("<version>1.0</version>"));
        List<GUIVersion> versions = documents.getVersionsById(version.getId(), version.getId());
        assertEquals(doc.getId(), versions.get(0).getDocId());
        assertEquals("1.0", versions.get(0).getVersion());
        assertEquals("1.0", versions.get(0).getFileVersion());

        // Download, as Util.downloadURL(docId, fileVersion) asks for it
        MemoryResponse download = download();
        assertNull(download.error());
        assertEquals(200, download.getStatus());
        assertArrayEquals(content, download.bytes());

        // PreviewPanel loads prev/index.jsp?docId=&fileVersion=, which shows the viewer only if
        // the document grants READ and PREVIEW (lines 37-38), and the viewer loads the PDF from
        // /convertpdf with the same query
        assertTrue(DocumentDAO.get().isReadAllowed(doc.getId(), alice)
                && DocumentDAO.get().isPreviewAllowed(doc.getId(), alice));
        assertArrayEquals(content, preview().bytes());

        // The folder stays out of reach. The GUI asks for it when a hit is double-clicked
        // or right-clicked (D7).
        assertNull(new FolderServiceImpl().getFolder(secured.getId(), false, false, false));

        // Amending the properties (spec 8.1): Carol's WRITE comes only from the document's own ACL
        prepareSession("carol", PASSWORD);
        GUIDocument edited = new DocumentServiceImpl().getById(doc.getId());
        assertTrue(allowed(edited.getFolder().getAllowedPermissions()).isEmpty());
        edited.setColor("red");
        assertEquals("red", new DocumentServiceImpl().save(edited).getColor());
        assertEquals("red", DocumentDAO.get().findById(doc.getId()).getColor());
    }

    @Test
    public void testOthersAreRefused() throws Exception {
        long bob = UserDAO.get().findByUsername("bob").getId();
        assertFalse(FolderDAO.get().isReadAllowed(secured.getId(), bob));
        assertTrue(DocumentDAO.get().getAllowedPermissions(doc.getId(), bob).isEmpty());
        GUIDocument edited = new DocumentServiceImpl().getById(doc.getId());
        edited.setColor("red");

        // Bob, who has no grant
        prepareSession("bob", PASSWORD);

        DocumentServiceImpl documents = new DocumentServiceImpl();
        assertTrue(allowed(documents.getAllowedPermissions(List.of(doc.getId()))).isEmpty());
        assertThrows(AccessDeniedException.class, () -> documents.getById(doc.getId()));

        MemoryResponse versionsXml = versionsXml();
        assertEquals(500, versionsXml.getStatus());
        assertEquals("Document %d is not accessible by user bob".formatted(doc.getId()), versionsXml.error());
        assertEquals("", versionsXml.text());
        assertThrows(AccessDeniedException.class,
                () -> documents.getVersionsById(version.getId(), version.getId()));

        assertThrows(AccessDeniedException.class, () -> documents.save(edited));
        assertUnchanged();

        MemoryResponse download = download();
        assertEquals(500, download.getStatus());
        assertEquals("You don't have the DOWNLOAD permission", download.error());
        assertEquals(0, download.bytes().length);

        assertFalse(DocumentDAO.get().isReadAllowed(doc.getId(), bob)
                || DocumentDAO.get().isPreviewAllowed(doc.getId(), bob));
        try (InputStream in = ResourceUtil.getInputStream("pdf/notavailable.pdf")) {
            assertArrayEquals(in.readAllBytes(), preview().bytes());
        }

        assertNull(new FolderServiceImpl().getFolder(secured.getId(), false, false, false));

        // Bob, with WRITE on another document, can't pass it off as a shortcut to the secured one
        Document own = DocumentDAO.get().findById(OWN_DOC, true);
        DocumentAccessControlEntry ownGrant = new DocumentAccessControlEntry(
                UserDAO.get().findByUsername("bob").getUserGroup().getId());
        ownGrant.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE));
        own.addAccessControlEntry(ownGrant);
        DocumentDAO.get().store(own);
        GUIDocument forged = documents.getById(OWN_DOC);
        forged.setDocRef(doc.getId());
        // With the target's own custom id, which CE's uniqueness check wants (a doc # is easy to guess)
        forged.setCustomId(doc.getCustomId());
        forged.setTags(List.of("forged"));
        forged.setColor("blue");
        assertThrows(AccessDeniedException.class, () -> documents.save(forged));
        assertUnchanged();
        Document ownNow = DocumentDAO.get().findById(OWN_DOC, true);
        assertNull(ownNow.getDocRef());
        assertNull(ownNow.getColor());
        assertTrue(ownNow.getTagsAsWords().isEmpty());

        // Alice, who may read but not write
        prepareSession("alice", PASSWORD);
        assertThrows(AccessDeniedException.class, () -> new DocumentServiceImpl().save(edited));
        assertUnchanged();

        // Carol, who may write but not rename (T19-f)
        prepareSession("carol", PASSWORD);
        GUIDocument renamed = new DocumentServiceImpl().getById(doc.getId());
        renamed.setFileName("renamed.pdf");
        renamed.setColor("green");
        assertThrows(AccessDeniedException.class, () -> new DocumentServiceImpl().save(renamed));
        assertUnchanged();

        // A shortcut's name is checked like a document's: Carol has WRITE on the shortcut but not RENAME
        prepareSession("admin", "admin");
        DocumentHistory aliasing = new DocumentHistory();
        aliasing.setSession(session);
        Document shortcut = DocumentManager.get().createAlias(DocumentDAO.get().findById(doc.getId(), true),
                FolderDAO.get().findById(Folder.DEFAULTWORKSPACEID), null, aliasing);
        shortcut = DocumentDAO.get().findById(shortcut.getId(), true);
        shortcut.getAccessControlList().clear();
        DocumentAccessControlEntry shortcutGrant = new DocumentAccessControlEntry(
                UserDAO.get().findByUsername("carol").getUserGroup().getId());
        shortcutGrant.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE));
        shortcut.addAccessControlEntry(shortcutGrant);
        DocumentDAO.get().store(shortcut);
        long shortcutId = shortcut.getId();
        String shortcutName = shortcut.getFileName();
        assertNotNull(shortcut.getDocRef());

        prepareSession("carol", PASSWORD);
        GUIDocument guiShortcut = new DocumentServiceImpl().getById(shortcutId);
        guiShortcut.setFileName("");
        guiShortcut.setColor("green");
        new DocumentServiceImpl().save(guiShortcut);
        assertEquals(shortcutName, DocumentDAO.get().findById(shortcutId).getFileName());

        GUIDocument renamedShortcut = new DocumentServiceImpl().getById(shortcutId);
        renamedShortcut.setFileName("other.pdf");
        renamedShortcut.setColor("red");
        assertThrows(AccessDeniedException.class, () -> new DocumentServiceImpl().save(renamedShortcut));
        Document shortcutNow = DocumentDAO.get().findById(shortcutId);
        assertEquals(shortcutName, shortcutNow.getFileName());
        assertNotEquals("red", shortcutNow.getColor());
        assertUnchanged();
    }

    /**
     * Asserts that no refused save changed the secured document
     */
    private void assertUnchanged() throws PersistenceException {
        Document current = DocumentDAO.get().findById(doc.getId(), true);
        assertEquals("secured.pdf", current.getFileName());
        assertNull(current.getColor());
        assertTrue(current.getTagsAsWords().isEmpty());
    }

    private void createCase() throws Exception {
        Group it = new Group();
        it.setName("IT");
        it.setTenantId(Tenant.DEFAULT_ID);
        GroupDAO.get().store(it);
        User alice = user("alice");
        user("bob");
        User carol = user("carol");

        // A folder only IT can read
        Folder vo = new Folder("Secured Documents");
        vo.setTenantId(Tenant.DEFAULT_ID);
        secured = FolderDAO.get().create(FolderDAO.get().findById(Folder.DEFAULTWORKSPACEID), vo, false,
                folderHistory());
        secured = FolderDAO.get().findById(secured.getId(), true);
        secured.setDescription("For IT only");
        secured.setQuotaAlertRecipients("it@example.com");
        secured.setSecurityRef(null);
        secured.getAccessControlList().clear();
        FolderAccessControlEntry itFolder = new FolderAccessControlEntry(it.getId());
        itFolder.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE, Permission.DOWNLOAD,
                Permission.PREVIEW, Permission.ADD));
        secured.addAccessControlEntry(itFolder);
        FolderDAO.get().store(secured, folderHistory());

        // A PDF in it, uploaded by the admin
        try (InputStream in = ResourceUtil.getInputStream("pdf1.pdf")) {
            content = in.readAllBytes();
        }
        Document docVO = new Document();
        docVO.setFileName("secured.pdf");
        docVO.setFolder(secured);
        docVO.setTenantId(Tenant.DEFAULT_ID);
        docVO.setLanguage("en");
        DocumentHistory transaction = new DocumentHistory();
        transaction.setSession(session);
        doc = DocumentManager.get().create(ResourceUtil.getInputStream("pdf1.pdf"), docVO, transaction)
                .getDocument();
        version = awaitFirstVersion(doc.getId());

        // Its own ACL, as its Security tab saves it: what the folder grants, Alice and Carol
        Document current = DocumentDAO.get().findById(doc.getId(), true);
        DocumentAccessControlEntry itDocument = new DocumentAccessControlEntry(it.getId());
        itDocument.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE, Permission.DOWNLOAD,
                Permission.PREVIEW));
        current.addAccessControlEntry(itDocument);
        DocumentAccessControlEntry grant = new DocumentAccessControlEntry(alice.getUserGroup().getId());
        grant.grantPermissions(EnumSet.of(Permission.READ, Permission.DOWNLOAD, Permission.PREVIEW));
        current.addAccessControlEntry(grant);
        DocumentAccessControlEntry writer = new DocumentAccessControlEntry(carol.getUserGroup().getId());
        writer.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE));
        current.addAccessControlEntry(writer);
        DocumentHistory permission = new DocumentHistory();
        permission.setSession(session);
        permission.setEvent(DocumentEvent.PERMISSION);
        DocumentDAO.get().store(current, permission);
        doc = DocumentDAO.get().findById(doc.getId());
    }

    private MemoryResponse versionsXml() throws Exception {
        MockServletRequest request = new MockServletRequest(servletSession);
        request.setParameter("docId", Long.toString(doc.getId()));
        MemoryResponse response = new MemoryResponse();
        // HttpServlet's public entry, which calls the data servlet's own service method
        new VersionsDataServlet().service((ServletRequest) request, (ServletResponse) response);
        return response;
    }

    private MemoryResponse download() {
        MockServletRequest request = new MockServletRequest(servletSession);
        request.setParameter("docId", Long.toString(doc.getId()));
        request.setParameter("fileVersion", version.getFileVersion());
        MemoryResponse response = new MemoryResponse();
        new DownloadServlet().doGet(request, response);
        return response;
    }

    private MemoryResponse preview() {
        MockServletRequest request = new MockServletRequest(servletSession);
        request.setParameter("docId", Long.toString(doc.getId()));
        request.setParameter("fileVersion", version.getFileVersion());
        request.setParameter("control", "preview");
        MemoryResponse response = new MemoryResponse();
        new ConvertPdf().doGet(request, response);
        return response;
    }

    private FolderHistory folderHistory() {
        FolderHistory history = new FolderHistory();
        history.setSession(session);
        return history;
    }

    private static Set<String> allowed(GUIAccessControlEntry ace) {
        return Set.copyOf(ace.getAllowedPermissions());
    }

    private static User user(String username) throws Exception {
        User user = new User();
        user.setUsername(username);
        user.setName(username);
        user.setFirstName(username);
        user.setEmail(username + "@example.com");
        user.setDecodedPassword(PASSWORD);
        user.setTenantId(Tenant.DEFAULT_ID);
        user.setType(User.Type.DEFAULT);
        UserDAO.get().store(user);
        return UserDAO.get().findByUsername(username);
    }

    /**
     * @return the document's first version, once CE has saved it in the
     *         background
     */
    private static Version awaitFirstVersion(long docId) throws PersistenceException, InterruptedException {
        long deadline = System.currentTimeMillis() + 20000L;
        while (true) {
            List<Version> versions = VersionDAO.get().findByDocId(docId);
            if (!versions.isEmpty())
                return versions.get(0);
            if (System.currentTimeMillis() > deadline)
                fail("The first version wasn't saved");
            Thread.sleep(50L);
        }
    }
}
