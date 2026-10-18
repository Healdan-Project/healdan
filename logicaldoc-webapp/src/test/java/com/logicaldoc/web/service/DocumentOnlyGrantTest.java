package com.logicaldoc.web.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
import com.logicaldoc.gui.common.client.beans.GUIAccessControlEntry;
import com.logicaldoc.gui.common.client.beans.GUIDocument;
import com.logicaldoc.gui.common.client.beans.GUIVersion;
import com.logicaldoc.util.io.ResourceUtil;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.util.servlet.MockServletResponse;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.ConvertPdf;
import com.logicaldoc.web.DownloadServlet;
import com.logicaldoc.web.data.VersionsDataServlet;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;

/**
 * Roadmap M1: a document can be granted to people who can't see its folder, as
 * a one-off exception (decision D7). From the hit a doc # lookup gives them,
 * they can open the document, preview it, download it and see its versions
 * (spec §8.1), with what the document's own ACL grants them, while its folder
 * stays out of their reach.
 * <p>
 * Alice isn't in IT. A PDF in Secured Documents, a folder only IT can read,
 * gives Alice READ, DOWNLOAD and PREVIEW in its own ACL. Bob has no grant at
 * all. The doc # lookup itself is Healdan's, so healdan-core's
 * {@code DocumentOnlyGrantLookupTest} covers it.
 * <p>
 * Two tests, one per user, because each test builds its database and its
 * document again, which takes seconds.
 */
public class DocumentOnlyGrantTest extends AbstractWPTestCase {

    /** The password of the users this test creates */
    private static final String PASSWORD = "Kx7!Rmq-Tz8vW";

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
    public void testAliceReachesTheDocument() throws Exception {
        long alice = UserDAO.get().findByUsername("alice").getId();
        assertFalse(FolderDAO.get().isReadAllowed(secured.getId(), alice));
        assertEquals(EnumSet.of(Permission.READ, Permission.DOWNLOAD, Permission.PREVIEW),
                DocumentDAO.get().getAllowedPermissions(doc.getId(), alice));
        // The admin, whose session setUp opened, gets the folder
        assertNotNull(new FolderServiceImpl().getFolder(secured.getId(), false, false, false));

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
        assertEquals(secured.getId(), opened.getFolder().getId());
        assertEquals(Set.of("READ", "DOWNLOAD", "PREVIEW"), allowed(opened.getAllowedPermissions()));
        assertEquals(Set.of("READ", "DOWNLOAD", "PREVIEW"),
                allowed(documents.getAllowedPermissions(List.of(doc.getId()))));

        // The Versions tab lists /data/versions.xml?docId=, and comparing two versions calls getVersionsById
        String xml = versionsXml();
        assertTrue(xml, xml.contains("<docid>%d</docid>".formatted(doc.getId())));
        assertTrue(xml, xml.contains("<version>1.0</version>"));
        List<GUIVersion> versions = documents.getVersionsById(version.getId(), version.getId());
        assertEquals(doc.getId(), versions.get(0).getDocId());
        assertEquals("1.0", versions.get(0).getVersion());
        assertEquals("1.0", versions.get(0).getFileVersion());

        // Download, as Util.downloadURL(docId, fileVersion) asks for it
        MemoryResponse download = download();
        assertNull(download.error);
        assertEquals(200, download.getStatus());
        assertArrayEquals(content, download.body.toByteArray());

        // PreviewPanel loads prev/index.jsp?docId=&fileVersion=, which shows the viewer only if
        // the document grants READ and PREVIEW (lines 37-38), and the viewer loads the PDF from
        // /convertpdf with the same query
        assertTrue(DocumentDAO.get().isReadAllowed(doc.getId(), alice)
                && DocumentDAO.get().isPreviewAllowed(doc.getId(), alice));
        assertArrayEquals(content, preview().body.toByteArray());

        // The folder stays out of reach. The GUI asks for it when a hit is double-clicked
        // or right-clicked (D7).
        assertNull(new FolderServiceImpl().getFolder(secured.getId(), false, false, false));
    }

    @Test
    public void testBobIsRefused() throws Exception {
        long bob = UserDAO.get().findByUsername("bob").getId();
        assertFalse(FolderDAO.get().isReadAllowed(secured.getId(), bob));
        assertTrue(DocumentDAO.get().getAllowedPermissions(doc.getId(), bob).isEmpty());

        prepareSession("bob", PASSWORD);

        assertTrue(allowed(new DocumentServiceImpl().getAllowedPermissions(List.of(doc.getId()))).isEmpty());

        MemoryResponse download = download();
        assertEquals(500, download.getStatus());
        assertEquals("You don't have the DOWNLOAD permission", download.error);
        assertEquals(0, download.body.size());

        assertFalse(DocumentDAO.get().isReadAllowed(doc.getId(), bob)
                || DocumentDAO.get().isPreviewAllowed(doc.getId(), bob));
        try (InputStream in = ResourceUtil.getInputStream("pdf/notavailable.pdf")) {
            assertArrayEquals(in.readAllBytes(), preview().body.toByteArray());
        }

        assertNull(new FolderServiceImpl().getFolder(secured.getId(), false, false, false));
    }

    private void createCase() throws Exception {
        Group it = new Group();
        it.setName("IT");
        it.setTenantId(Tenant.DEFAULT_ID);
        GroupDAO.get().store(it);
        User alice = user("alice");
        user("bob");

        // A folder only IT can read
        Folder vo = new Folder("Secured Documents");
        vo.setTenantId(Tenant.DEFAULT_ID);
        secured = FolderDAO.get().create(FolderDAO.get().findById(Folder.DEFAULTWORKSPACEID), vo, false,
                folderHistory());
        secured = FolderDAO.get().findById(secured.getId(), true);
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

        // Its own ACL, as its Security tab saves it: what the folder grants, and Alice
        Document current = DocumentDAO.get().findById(doc.getId(), true);
        DocumentAccessControlEntry itDocument = new DocumentAccessControlEntry(it.getId());
        itDocument.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE, Permission.DOWNLOAD,
                Permission.PREVIEW));
        current.addAccessControlEntry(itDocument);
        DocumentAccessControlEntry grant = new DocumentAccessControlEntry(alice.getUserGroup().getId());
        grant.grantPermissions(EnumSet.of(Permission.READ, Permission.DOWNLOAD, Permission.PREVIEW));
        current.addAccessControlEntry(grant);
        DocumentHistory permission = new DocumentHistory();
        permission.setSession(session);
        permission.setEvent(DocumentEvent.PERMISSION);
        DocumentDAO.get().store(current, permission);
        doc = DocumentDAO.get().findById(doc.getId());
    }

    private String versionsXml() throws Exception {
        MockServletRequest request = new MockServletRequest(servletSession);
        request.setParameter("docId", Long.toString(doc.getId()));
        MemoryResponse response = new MemoryResponse();
        // HttpServlet's public entry, which calls the data servlet's own service method
        new VersionsDataServlet().service((ServletRequest) request, (ServletResponse) response);
        assertNull(response.error);
        return response.text();
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

    /**
     * A response kept in memory, with the error it was sent if any. The data
     * servlet tests' {@code MockServletResponse} writes to a file instead.
     */
    private static class MemoryResponse extends MockServletResponse {

        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        private PrintWriter writer;

        private Integer status;

        private String error;

        MemoryResponse() {
            // Never written: both ways to write are overridden
            super(new File("target/unused-response"));
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {

                @Override
                public void write(int b) {
                    body.write(b);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    // Nothing to do
                }
            };
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null)
                writer = new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8), true);
            return writer;
        }

        @Override
        public void sendError(int sc, String message) {
            status = sc;
            error = message;
        }

        @Override
        public void sendError(int sc) {
            status = sc;
        }

        @Override
        public void setStatus(int sc) {
            status = sc;
        }

        @Override
        public int getStatus() {
            return status != null ? status : 200;
        }

        String text() {
            if (writer != null)
                writer.flush();
            return body.toString(StandardCharsets.UTF_8);
        }
    }
}
