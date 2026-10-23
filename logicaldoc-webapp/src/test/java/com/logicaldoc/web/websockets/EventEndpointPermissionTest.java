package com.logicaldoc.web.websockets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.security.Principal;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

import com.logicaldoc.core.communication.EventCollector;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.spring.LDAuthenticationToken;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.gui.common.client.websockets.WebsocketMessage;
import com.logicaldoc.util.io.ResourceUtil;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.web.AbstractWPTestCase;

import jakarta.websocket.RemoteEndpoint.Async;
import jakarta.websocket.RemoteEndpoint.Basic;
import jakarta.websocket.Session;

/**
 * Healdan: {@code /wk-event}, the old GUI's live updates, sent every document,
 * folder and user event, with the document's and its folder's details, to
 * every connected peer, whoever its user was (the capability map's section 6). A peer now
 * gets a message only if its user may read what the message is about:
 * <ul>
 * <li>a document event, READ on the document, which a document-only grant
 * gives (D7). The folder's details go only to those who may read the folder;
 * the others get its id and name, as {@code getById} gives them (T19-e);</li>
 * <li>a folder event, READ on the folder;</li>
 * <li>a deletion, READ as it was before, from the ACL a soft delete
 * keeps;</li>
 * <li>a system message, only its recipient; a login or logout, the user and
 * the admins;</li>
 * <li>an automation command, only the session it was sent for.</li>
 * </ul>
 * A peer whose session has ended, or that has none, gets nothing.
 * <p>
 * Alice isn't in IT, and a document-only grant gives her READ on a PDF in
 * Secured Documents, a folder only IT can read. Carol is in IT. Bob has no
 * grant. Each user has one peer; the admin has one too.
 * <p>
 * Background events from building the case can reach the peers too, but
 * through the same rules, so every assertion picks its event's messages out by
 * a marker of its own.
 */
public class EventEndpointPermissionTest extends AbstractWPTestCase {

    /** The password of the users this test creates */
    private static final String PASSWORD = "Kx7!Rmq-Tz8vW";

    /** What the folder's details say, which only its readers may see */
    private static final String FOLDER_DESCRIPTION = "For IT only";

    /** The ids of the events this test sends, well above those the case's own events get */
    private long nextEventId = 900_000L;

    /** Opens and closes the peers, as Tomcat's endpoint for each would */
    private final EventEndpoint endpoint = new EventEndpoint();

    /** Sends the events, as the endpoint that listens to the event collector would */
    private final EventEndpoint events = new EventEndpoint();

    private final Map<String, Session> peers = new HashMap<>();

    /** What each peer was sent */
    private final Map<String, List<String>> received = new HashMap<>();

    private final Map<String, com.logicaldoc.core.security.Session> sessions = new HashMap<>();

    private Folder secured;

    private Document doc;

    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        try {
            createCase();
            connect("admin", "admin", "admin");
            for (String user : List.of("alice", "bob", "carol"))
                connect(user, user, PASSWORD);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void tearDown() throws IOException {
        // The peers are static: the next test must not find them
        for (Session peer : peers.values())
            endpoint.onClose(peer);
        super.tearDown();
    }

    @Test
    public void testDocumentEventReachesItsReaders() throws Exception {
        events.newEvent(documentEvent(DocumentEvent.CHECKEDOUT, "checked-out"));

        assertEquals(1, received("admin", "checked-out").size());
        assertEquals(1, received("carol", "checked-out").size());
        // A document-only grant is enough
        assertEquals(1, received("alice", "checked-out").size());
        assertTrue(received("bob", "checked-out").isEmpty());

        // The folder's details go to its readers only
        assertTrue(received("carol", "checked-out").get(0).contains(FOLDER_DESCRIPTION));
        String alices = received("alice", "checked-out").get(0);
        assertTrue(alices, alices.contains("secured.pdf"));
        assertTrue(alices, alices.contains("Secured Documents"));
        assertFalse(alices, alices.contains(FOLDER_DESCRIPTION));
    }

    @Test
    public void testDeletedDocumentReachesItsFormerReaders() throws Exception {
        DocumentHistory deletion = new DocumentHistory();
        deletion.setSession(session);
        DocumentDAO.get().delete(doc.getId(), deletion);

        events.newEvent(documentEvent(DocumentEvent.DELETED, "doc-deleted"));

        assertEquals(1, received("admin", "doc-deleted").size());
        assertEquals(1, received("carol", "doc-deleted").size());
        assertEquals(1, received("alice", "doc-deleted").size());
        assertTrue(received("bob", "doc-deleted").isEmpty());
        assertFalse(received("alice", "doc-deleted").get(0).contains(FOLDER_DESCRIPTION));
    }

    @Test
    public void testFolderEventReachesItsReaders() throws Exception {
        events.newEvent(folderEvent(FolderEvent.CHANGED, "folder-changed"));

        assertEquals(1, received("admin", "folder-changed").size());
        assertEquals(1, received("carol", "folder-changed").size());
        // Alice may read a document in it, not the folder
        assertTrue(received("alice", "folder-changed").isEmpty());
        assertTrue(received("bob", "folder-changed").isEmpty());

        FolderHistory deletion = new FolderHistory();
        deletion.setSession(session);
        FolderDAO.get().delete(secured.getId(), deletion);

        // CE never builds this message: the endpoint's setFolder fails on a deleted folder, which
        // FolderServiceImpl.fromFolder gives as null. So it is sent as CE would build it.
        WebsocketMessage deleted = new WebsocketMessage(session.getSid(), FolderEvent.DELETED.toString());
        deleted.setId(nextEventId++);
        deleted.setTenantId(Tenant.DEFAULT_ID);
        deleted.setFolderId(secured.getId());
        deleted.setComment("folder-deleted");
        EventEndpoint.distributeMessage(deleted);

        assertEquals(1, received("admin", "folder-deleted").size());
        assertEquals(1, received("carol", "folder-deleted").size());
        assertTrue(received("alice", "folder-deleted").isEmpty());
        assertTrue(received("bob", "folder-deleted").isEmpty());
    }

    @Test
    public void testSystemMessageReachesOnlyItsRecipient() throws Exception {
        UserHistory message = userEvent(UserEvent.MESSAGE_RECEIVED, "alice", "a note for alice");
        message.setAuthor("Bob");
        events.newEvent(message);

        assertEquals(1, received("alice", "a note for alice").size());
        for (String other : List.of("admin", "bob", "carol"))
            assertTrue(other, received(other, "a note for alice").isEmpty());
    }

    @Test
    public void testLoginReachesTheUserAndTheAdmins() throws Exception {
        events.newEvent(userEvent(UserEvent.LOGIN, "bob", "bob-login"));
        events.newEvent(userEvent(UserEvent.LOGOUT, "bob", "bob-logout"));

        for (String marker : List.of("bob-login", "bob-logout")) {
            assertEquals(marker, 1, received("bob", marker).size());
            assertEquals(marker, 1, received("admin", marker).size());
            assertTrue(marker, received("alice", marker).isEmpty());
            assertTrue(marker, received("carol", marker).isEmpty());
        }
    }

    @Test
    public void testCommandReachesOnlyItsSession() throws Exception {
        // Alice in a second browser
        connect("alice-other", "alice", PASSWORD);

        new WebsocketTool().showMessage(sessions.get("alice"), "hello-alice", "info");

        assertEquals(1, received("alice", "hello-alice").size());
        for (String other : List.of("alice-other", "admin", "bob", "carol"))
            assertTrue(other, received(other, "hello-alice").isEmpty());
    }

    @Test
    public void testEndedSessionReceivesNothing() throws Exception {
        SessionManager.get().kill(sessions.get("carol").getSid());
        // A peer whose principal names no session
        endpoint.onOpen(peer("anonymous", null));

        events.newEvent(documentEvent(DocumentEvent.CHECKEDIN, "checked-in"));

        assertEquals(1, received("alice", "checked-in").size());
        assertTrue(received("carol", "checked-in").isEmpty());
        assertTrue(received("anonymous", "checked-in").isEmpty());
    }

    @Test
    public void testOneEndpointListensForAllPeers() throws Exception {
        // Tomcat makes an endpoint for each peer. Each one that listened to the collector sent every event to every
        // peer again, and every copy would now cost a permission check per peer.
        new EventEndpoint().onOpen(peer("second", null));
        new EventEndpoint().onOpen(peer("third", null));

        Field field = EventCollector.class.getDeclaredField("listeners");
        field.setAccessible(true);
        Set<?> listeners = (Set<?>) field.get(EventCollector.get());
        assertEquals(1, listeners.stream().filter(EventEndpoint.class::isInstance).count());
    }

    /**
     * @return the messages the peer was sent that carry the marker
     */
    private List<String> received(String peer, String marker) {
        return received.get(peer).stream().filter(text -> text.contains(marker)).toList();
    }

    private DocumentHistory documentEvent(DocumentEvent event, String marker) throws Exception {
        DocumentHistory history = new DocumentHistory();
        history.setId(nextEventId++);
        history.setSession(session);
        history.setEvent(event);
        history.setComment(marker);
        history.setDocId(doc.getId());
        history.setDocument(doc);
        history.setFolderId(secured.getId());
        history.setFolder(secured);
        return history;
    }

    private FolderHistory folderEvent(FolderEvent event, String marker) {
        FolderHistory history = new FolderHistory();
        history.setId(nextEventId++);
        history.setSession(session);
        history.setEvent(event);
        history.setComment(marker);
        history.setFolderId(secured.getId());
        history.setFolder(secured);
        return history;
    }

    private UserHistory userEvent(UserEvent event, String username, String marker) throws Exception {
        UserHistory history = new UserHistory();
        history.setId(nextEventId++);
        history.setUser(UserDAO.get().findByUsername(username));
        history.setEvent(event);
        history.setComment(marker);
        return history;
    }

    /**
     * Opens a session for the user and a peer for it, whose principal is the
     * token CE's security filters give the websocket's handshake request
     */
    private void connect(String peer, String username, String password) throws Exception {
        com.logicaldoc.core.security.Session userSession = SessionManager.get().newSession(username, password,
                new Client("xyz-" + peer, "192.168.2.231", "ghost"));
        sessions.put(peer, userSession);
        LDAuthenticationToken principal = new LDAuthenticationToken(username, "", null);
        principal.setSid(userSession.getSid());
        endpoint.onOpen(peer(peer, principal));
    }

    private Session peer(String name, Principal principal) throws IOException {
        List<String> texts = new CopyOnWriteArrayList<>();
        received.put(name, texts);
        Basic remote = mock(Basic.class);
        doAnswer(call -> texts.add(call.getArgument(0))).when(remote).sendText(anyString());
        Session peer = mock(Session.class);
        when(peer.getId()).thenReturn(name);
        when(peer.getUserPrincipal()).thenReturn(principal);
        when(peer.getAsyncRemote()).thenReturn(mock(Async.class));
        when(peer.getBasicRemote()).thenReturn(remote);
        peers.put(name, peer);
        return peer;
    }

    private void createCase() throws Exception {
        Group it = new Group();
        it.setName("IT");
        it.setTenantId(Tenant.DEFAULT_ID);
        GroupDAO.get().store(it);
        User alice = user("alice");
        user("bob");
        User carol = user("carol");
        carol.addGroup(it);
        UserDAO.get().store(carol);

        // A folder only IT can read
        Folder vo = new Folder("Secured Documents");
        vo.setTenantId(Tenant.DEFAULT_ID);
        secured = FolderDAO.get().create(FolderDAO.get().findById(Folder.DEFAULTWORKSPACEID), vo, false,
                folderHistory());
        secured = FolderDAO.get().findById(secured.getId(), true);
        secured.setDescription(FOLDER_DESCRIPTION);
        secured.setSecurityRef(null);
        secured.getAccessControlList().clear();
        FolderAccessControlEntry itFolder = new FolderAccessControlEntry(it.getId());
        itFolder.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE, Permission.ADD));
        secured.addAccessControlEntry(itFolder);
        FolderDAO.get().store(secured, folderHistory());
        secured = FolderDAO.get().findById(secured.getId(), true);

        // A PDF in it, with its own ACL: IT, and Alice's document-only grant
        Document docVO = new Document();
        docVO.setFileName("secured.pdf");
        docVO.setFolder(secured);
        docVO.setTenantId(Tenant.DEFAULT_ID);
        docVO.setLanguage("en");
        DocumentHistory transaction = new DocumentHistory();
        transaction.setSession(session);
        try (InputStream in = ResourceUtil.getInputStream("pdf1.pdf")) {
            doc = DocumentManager.get().create(in, docVO, transaction).getDocument();
        }
        Document current = DocumentDAO.get().findById(doc.getId(), true);
        DocumentAccessControlEntry itDocument = new DocumentAccessControlEntry(it.getId());
        itDocument.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE));
        current.addAccessControlEntry(itDocument);
        DocumentAccessControlEntry grant = new DocumentAccessControlEntry(alice.getUserGroup().getId());
        grant.grantPermissions(EnumSet.of(Permission.READ));
        current.addAccessControlEntry(grant);
        DocumentHistory permission = new DocumentHistory();
        permission.setSession(session);
        permission.setEvent(DocumentEvent.PERMISSION);
        DocumentDAO.get().store(current, permission);
        doc = DocumentDAO.get().findById(doc.getId(), true);

        assertFalse(FolderDAO.get().isReadAllowed(secured.getId(), alice.getId()));
        assertTrue(DocumentDAO.get().isReadAllowed(doc.getId(), alice.getId()));
        assertTrue(FolderDAO.get().isReadAllowed(secured.getId(), carol.getId()));
        assertTrue(DocumentDAO.get().isReadAllowed(doc.getId(), carol.getId()));
        long bob = UserDAO.get().findByUsername("bob").getId();
        assertFalse(DocumentDAO.get().isReadAllowed(doc.getId(), bob));
    }

    private FolderHistory folderHistory() {
        FolderHistory history = new FolderHistory();
        history.setSession(session);
        return history;
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
}
