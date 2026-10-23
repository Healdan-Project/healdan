package com.logicaldoc.web.websockets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gwt.user.client.rpc.SerializationException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.communication.EventCollector;
import com.logicaldoc.core.communication.EventListener;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.spring.LDAuthenticationToken;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.gui.common.client.beans.GUIDocument;
import com.logicaldoc.gui.common.client.beans.GUIFolder;
import com.logicaldoc.gui.common.client.websockets.WebsocketMessage;

import jakarta.websocket.Session;

/**
 * Healdan: who gets each message of {@code /wk-event}, the old GUI's live
 * updates, which CE sent to every connected peer, whoever its user was
 * (the capability map's section 6). A recipient never learns anything about an object they
 * couldn't read:
 * <ul>
 * <li>Every message goes only to peers whose CE session is open, in the
 * message's tenant. A peer's user is the session its handshake was
 * authenticated with, which CE's security filters give as the principal.</li>
 * <li>A document event goes to the users with READ on the document, and on
 * its target if it is a shortcut, whose details the message shows. A
 * document-only grant is enough (D7). Those who may not read the folder get
 * its id and name and nothing more of it, as {@code getById} gives them
 * (T19-e).</li>
 * <li>A folder event goes to the users with READ on the folder.</li>
 * <li>A deleted document or folder goes to those who could read it. CE's
 * checks refuse anything deleted, so its ACL, which a soft delete keeps, is
 * read here: the document's own entries for the user's groups, else its
 * folder's; a folder's, or its security reference's. Unlike CE's reading,
 * own entries that grant nothing at all don't fall back to the folder's: a
 * deletion fails closed.</li>
 * <li>A system message, which carries its text, goes to its recipient only. A
 * login, logout or timeout goes to that user and the tenant's admins.</li>
 * <li>An automation command goes to the session it was sent for.</li>
 * <li>Anything else goes to nobody, such as LogicalDOC's chat, which CE
 * lacks.</li>
 * </ul>
 * A message is serialized at most twice, in full and with the folder hidden,
 * and each user's permissions are read once per message, however many peers
 * the user has.
 */
public final class HealdanEventDelivery {

    private static final Logger log = LoggerFactory.getLogger(HealdanEventDelivery.class);

    /** The event of {@code WebsocketTool}'s commands */
    private static final String COMMAND = "command";

    private static final String USER_EVENTS = "event.user.";

    private static final String FOLDER_EVENTS = "event.folder.";

    private static final Set<String> SESSION_EVENTS = Set.of(UserEvent.LOGIN.toString(),
            UserEvent.LOGOUT.toString(), UserEvent.TIMEOUT.toString());

    /** What a recipient gets of a message */
    private enum View {
        NONE, FULL, FOLDER_HIDDEN
    }

    /** Writes a message for the GUI */
    @FunctionalInterface
    public interface Serializer {
        String serialize(WebsocketMessage message) throws SerializationException;
    }

    /** The event collector an endpoint listens to, if one does */
    private static EventCollector listened;

    private HealdanEventDelivery() {
    }

    /**
     * Makes the endpoint listen to the events, unless another one does already.
     * Tomcat makes an endpoint for each peer, and each one that listened sent
     * every event to every peer again.
     *
     * @param endpoint the endpoint
     */
    public static synchronized void listen(EventListener endpoint) {
        EventCollector collector = EventCollector.get();
        if (collector != listened) {
            collector.addListener(endpoint);
            listened = collector;
        }
    }

    /**
     * Decides who gets the message
     *
     * @param message the message
     * @param peers the connected peers
     * @param serializer writes a message for the GUI
     *
     * @return each peer that gets the message, with the text to send it
     *
     * @throws SerializationException the message can't be written
     */
    public static Map<Session, String> deliveries(WebsocketMessage message, Set<Session> peers,
            Serializer serializer) throws SerializationException {
        List<Session> connected;
        synchronized (peers) {
            connected = new ArrayList<>(peers);
        }

        Map<Long, View> views = new HashMap<>();
        Map<View, String> texts = new HashMap<>();
        Map<Session, String> deliveries = new LinkedHashMap<>();
        for (Session peer : connected) {
            com.logicaldoc.core.security.Session session = session(peer);
            if (session == null || session.getUser() == null || session.getTenantId() != message.getTenantId())
                continue;

            View view;
            if (COMMAND.equals(message.getEvent()))
                view = session.getSid().equals(message.getSid()) ? View.FULL : View.NONE;
            else
                view = views.computeIfAbsent(session.getUserId(), id -> view(message, session.getUser()));
            if (view == View.NONE)
                continue;

            String text = texts.get(view);
            if (text == null) {
                text = view == View.FULL ? serializer.serialize(message) : folderHidden(message, serializer);
                texts.put(view, text);
            }
            deliveries.put(peer, text);
        }
        return deliveries;
    }

    /**
     * @return the open CE session the peer's handshake was authenticated with,
     *         if any
     */
    private static com.logicaldoc.core.security.Session session(Session peer) {
        if (!(peer.getUserPrincipal() instanceof LDAuthenticationToken token))
            return null;
        SessionManager manager = SessionManager.get();
        return manager.isOpen(token.getSid()) ? manager.get(token.getSid()) : null;
    }

    private static View view(WebsocketMessage message, User user) {
        String event = message.getEvent();
        try {
            if (event == null)
                return View.NONE;
            if (event.startsWith(USER_EVENTS))
                return userEvent(message, user);
            if (event.startsWith(FOLDER_EVENTS))
                return message.getFolderId() != null && readsFolder(user, message.getFolderId()) ? View.FULL
                        : View.NONE;
            if (message.getDocId() != null)
                return documentEvent(message, user);
            return View.NONE;
        } catch (PersistenceException e) {
            log.warn("Cannot tell whether user {} may get the websocket message {}", user.getUsername(), event, e);
            return View.NONE;
        }
    }

    private static View userEvent(WebsocketMessage message, User user) {
        boolean concerned = Objects.equals(message.getUserId(), user.getId());
        if (UserEvent.MESSAGE_RECEIVED.toString().equals(message.getEvent()))
            return concerned ? View.FULL : View.NONE;
        if (SESSION_EVENTS.contains(message.getEvent()))
            return concerned || user.isAdmin() ? View.FULL : View.NONE;
        return View.NONE;
    }

    private static View documentEvent(WebsocketMessage message, User user) throws PersistenceException {
        if (!readsDocument(user, message.getDocId()))
            return View.NONE;
        GUIDocument document = message.getDocument();
        if (document != null && document.getDocRef() != null && !readsDocument(user, document.getDocRef()))
            return View.NONE;

        // Every folder the message shows, which is the document's
        Set<Long> folderIds = new LinkedHashSet<>();
        if (message.getFolderId() != null)
            folderIds.add(message.getFolderId());
        if (message.getFolder() != null)
            folderIds.add(message.getFolder().getId());
        if (document != null && document.getFolder() != null)
            folderIds.add(document.getFolder().getId());
        for (long folderId : folderIds)
            if (!readsFolder(user, folderId))
                return View.FOLDER_HIDDEN;
        return View.FULL;
    }

    private static boolean readsDocument(User user, long docId) throws PersistenceException {
        DocumentDAO dao = DocumentDAO.get();
        if (dao.findById(docId) != null)
            return dao.isReadAllowed(docId, user.getId());

        // Deleted
        if (user.isAdmin())
            return true;
        List<Integer> reads = dao.queryForList(
                "select ld_read from ld_document_acl where ld_docid = %d and ld_groupid in (%s)".formatted(docId,
                        groups(user)),
                Integer.class);
        if (!reads.isEmpty())
            return reads.contains(1);
        List<Long> folderIds = dao
                .queryForList("select ld_folderid from ld_document where ld_id = %d".formatted(docId), Long.class);
        return !folderIds.isEmpty() && readsFolder(user, folderIds.get(0));
    }

    private static boolean readsFolder(User user, long folderId) throws PersistenceException {
        FolderDAO dao = FolderDAO.get();
        if (dao.findById(folderId) != null)
            return dao.isReadAllowed(folderId, user.getId());

        // Deleted
        if (user.isAdmin())
            return true;
        List<Long> securityIds = dao.queryForList(
                "select coalesce(ld_securityref, ld_id) from ld_folder where ld_id = %d".formatted(folderId), Long.class);
        return !securityIds.isEmpty() && dao.queryForInt(
                "select count(*) from ld_folder_acl where ld_folderid = %d and ld_read = 1 and ld_groupid in (%s)"
                        .formatted(securityIds.get(0), groups(user))) > 0;
    }

    private static String groups(User user) {
        return "select ld_groupid from ld_usergroup where ld_userid = %d".formatted(user.getId());
    }

    /**
     * Writes the message with its folder's id and name, and nothing more of it
     */
    private static String folderHidden(WebsocketMessage message, Serializer serializer)
            throws SerializationException {
        GUIFolder folder = message.getFolder();
        GUIDocument document = message.getDocument();
        GUIFolder documentFolder = document != null ? document.getFolder() : null;
        message.setFolder(stub(folder));
        if (document != null)
            document.setFolder(stub(documentFolder));
        try {
            return serializer.serialize(message);
        } finally {
            message.setFolder(folder);
            if (document != null)
                document.setFolder(documentFolder);
        }
    }

    private static GUIFolder stub(GUIFolder folder) {
        if (folder == null)
            return null;
        GUIFolder stub = new GUIFolder(folder.getId());
        stub.setName(folder.getName());
        return stub;
    }
}
