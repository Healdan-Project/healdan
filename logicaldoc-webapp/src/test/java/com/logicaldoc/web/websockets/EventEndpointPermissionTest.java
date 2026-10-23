package com.logicaldoc.web.websockets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

import com.google.gwt.user.client.rpc.SerializationException;
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
import com.logicaldoc.gui.common.client.beans.GUIFolder;
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
 * gives (D7), and on its target if it's a shortcut. The folder's details go
 * only to those who may read the folder; the others get its id and name, as
 * {@code getById} gives them (T19-e);</li>
 * <li>a move, READ at the new location;</li>
 * <li>a folder event, READ on the folder;</li>
 * <li>a deletion, READ as it was before, from the ACL a soft delete
 * keeps;</li>
 * <li>a system message, only its recipient; a login or logout, the user and
 * the admins, as they are when it's sent, not at their login;</li>
 * <li>an automation command, only the session it was sent for.</li>
 * </ul>
 * A peer whose session has ended, or that has none, gets nothing. A copy of a
 * message that can't be written costs only the peers it was for.
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

    private Group it;

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

    @Test
    public void testShortcutNeedsReadOnItsTarget() throws Exception {
        // Bob may read the shortcut, through its folder, but not the PDF it points to
        Folder shared = folder("Shared", "Bob, Alice and IT", userGroup("bob"), userGroup("alice"), it.getId());
        DocumentHistory transaction = new DocumentHistory();
        transaction.setSession(session);
        Document shortcut = DocumentManager.get().createAlias(doc, shared, null, transaction);
        assertTrue(DocumentDAO.get().isReadAllowed(shortcut.getId(), userId("bob")));

        events.newEvent(documentEvent(DocumentEvent.CHANGED, shortcut, shared, "shortcut-changed"));

        assertEquals(1, received("admin", "shortcut-changed").size());
        assertEquals(1, received("carol", "shortcut-changed").size());
        assertEquals(1, received("alice", "shortcut-changed").size());
        assertTrue(received("bob", "shortcut-changed").isEmpty());
    }

    @Test
    public void testMoveReachesTheNewLocationsReaders() throws Exception {
        // A PDF with no ACL of its own, which Bob may read through its folder until it moves to Secured Documents
        Folder shared = folder("Shared", "Bob and IT", userGroup("bob"), it.getId());
        Document moving = create("moving.pdf", shared);
        assertTrue(DocumentDAO.get().isReadAllowed(moving.getId(), userId("bob")));
        DocumentHistory transaction = new DocumentHistory();
        transaction.setSession(session);
        DocumentManager.get().moveToFolder(moving, secured, transaction);
        moving = DocumentDAO.get().findById(moving.getId(), true);
        assertFalse(DocumentDAO.get().isReadAllowed(moving.getId(), userId("bob")));

        events.newEvent(documentEvent(DocumentEvent.MOVED, moving, secured, "doc-moved"));

        assertEquals(1, received("admin", "doc-moved").size());
        assertEquals(1, received("carol", "doc-moved").size());
        assertTrue(received("carol", "doc-moved").get(0).contains(FOLDER_DESCRIPTION));
        // The message names the new folder, so Bob, who read it only at the old one, gets nothing
        assertTrue(received("bob", "doc-moved").isEmpty());
        assertTrue(received("alice", "doc-moved").isEmpty());
    }

    @Test
    public void testAdminsAreTheAdminsOfNow() throws Exception {
        // Dave signs in as an admin
        user("dave");
        Group admin = GroupDAO.get().findByName("admin", Tenant.DEFAULT_ID);
        User dave = UserDAO.get().findByUsername("dave", true);
        dave.addGroup(admin);
        UserDAO.get().store(dave);
        connect("dave", "dave", PASSWORD);

        events.newEvent(userEvent(UserEvent.LOGIN, "bob", "while-admin"));
        assertEquals(1, received("dave", "while-admin").size());

        // and then stops being one, still signed in
        dave = UserDAO.get().findByUsername("dave", true);
        dave.removeGroup(admin.getId());
        UserDAO.get().store(dave);

        events.newEvent(userEvent(UserEvent.LOGIN, "bob", "no-longer-admin"));
        assertTrue(received("dave", "no-longer-admin").isEmpty());
        assertEquals(1, received("admin", "no-longer-admin").size());
    }

    @Test
    public void testACopyThatCannotBeWrittenCostsOnlyItsPeers() throws Exception {
        Set<Session> connected = new HashSet<>(peers.values());

        // The copy with the folder hidden fails: those who read the folder still get theirs
        Map<Session, String> deliveries = HealdanEventDelivery.deliveries(checkout(), connected,
                message -> written(message, true));
        assertEquals("full", deliveries.get(peers.get("admin")));
        assertEquals("full", deliveries.get(peers.get("carol")));
        assertFalse(deliveries.containsKey(peers.get("alice")));
        assertFalse(deliveries.containsKey(peers.get("bob")));

        // The full copy fails: Alice still gets the one with the folder hidden
        deliveries = HealdanEventDelivery.deliveries(checkout(), connected, message -> written(message, false));
        assertEquals("folder hidden", deliveries.get(peers.get("alice")));
        assertFalse(deliveries.containsKey(peers.get("admin")));
        assertFalse(deliveries.containsKey(peers.get("carol")));

        // No copy can be written: the endpoint logs it, as CE did
        assertThrows(SerializationException.class, () -> HealdanEventDelivery.deliveries(checkout(), connected,
                message -> {
                    throw new SerializationException("cannot write");
                }));
    }

    /**
     * Writes a message as "full" or "folder hidden", failing on the copy
     * {@code failHidden} names
     */
    private static String written(WebsocketMessage message, boolean failHidden) throws SerializationException {
        boolean hidden = !FOLDER_DESCRIPTION.equals(message.getFolder().getDescription());
        if (hidden == failHidden)
            throw new SerializationException("cannot write");
        return hidden ? "folder hidden" : "full";
    }

    /**
     * @return a check-out of the PDF, as the endpoint builds it
     */
    private WebsocketMessage checkout() {
        WebsocketMessage message = new WebsocketMessage(session.getSid(), DocumentEvent.CHECKEDOUT.toString());
        message.setId(nextEventId++);
        message.setTenantId(Tenant.DEFAULT_ID);
        message.setDocId(doc.getId());
        message.setFolderId(secured.getId());
        GUIFolder folder = new GUIFolder(secured.getId());
        folder.setName(secured.getName());
        folder.setDescription(FOLDER_DESCRIPTION);
        message.setFolder(folder);
        return message;
    }

    /**
     * @return the messages the peer was sent that carry the marker
     */
    private List<String> received(String peer, String marker) {
        return received.get(peer).stream().filter(text -> text.contains(marker)).toList();
    }

    private DocumentHistory documentEvent(DocumentEvent event, String marker) throws Exception {
        return documentEvent(event, doc, secured, marker);
    }

    private DocumentHistory documentEvent(DocumentEvent event, Document document, Folder folder, String marker) {
        DocumentHistory history = new DocumentHistory();
        history.setId(nextEventId++);
        history.setSession(session);
        history.setEvent(event);
        history.setComment(marker);
        history.setDocId(document.getId());
        history.setDocument(document);
        history.setFolderId(folder.getId());
        history.setFolder(folder);
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
        it = new Group();
        it.setName("IT");
        it.setTenantId(Tenant.DEFAULT_ID);
        GroupDAO.get().store(it);
        User alice = user("alice");
        user("bob");
        User carol = user("carol");
        carol.addGroup(it);
        UserDAO.get().store(carol);

        // A folder only IT can read
        secured = folder("Secured Documents", FOLDER_DESCRIPTION, it.getId());

        // A PDF in it, with its own ACL: IT, and Alice's document-only grant
        doc = create("secured.pdf", secured);
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
        assertFalse(DocumentDAO.get().isReadAllowed(doc.getId(), userId("bob")));
    }

    /**
     * @return a new folder in the default workspace, which only the groups may
     *         read
     */
    private Folder folder(String name, String description, long... groupIds) throws Exception {
        Folder vo = new Folder(name);
        vo.setTenantId(Tenant.DEFAULT_ID);
        Folder folder = FolderDAO.get().create(FolderDAO.get().findById(Folder.DEFAULTWORKSPACEID), vo, false,
                folderHistory());
        folder = FolderDAO.get().findById(folder.getId(), true);
        folder.setDescription(description);
        folder.setSecurityRef(null);
        folder.getAccessControlList().clear();
        for (long groupId : groupIds) {
            FolderAccessControlEntry entry = new FolderAccessControlEntry(groupId);
            entry.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE, Permission.ADD));
            folder.addAccessControlEntry(entry);
        }
        FolderDAO.get().store(folder, folderHistory());
        return FolderDAO.get().findById(folder.getId(), true);
    }

    /**
     * @return a new PDF in the folder, with no ACL of its own
     */
    private Document create(String fileName, Folder folder) throws Exception {
        Document docVO = new Document();
        docVO.setFileName(fileName);
        docVO.setFolder(folder);
        docVO.setTenantId(Tenant.DEFAULT_ID);
        docVO.setLanguage("en");
        DocumentHistory transaction = new DocumentHistory();
        transaction.setSession(session);
        try (InputStream in = ResourceUtil.getInputStream("pdf1.pdf")) {
            return DocumentManager.get().create(in, docVO, transaction).getDocument();
        }
    }

    private static long userId(String username) throws Exception {
        return UserDAO.get().findByUsername(username).getId();
    }

    private static long userGroup(String username) throws Exception {
        return UserDAO.get().findByUsername(username).getUserGroup().getId();
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
