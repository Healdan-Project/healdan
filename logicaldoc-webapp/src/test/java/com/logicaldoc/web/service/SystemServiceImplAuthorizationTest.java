package com.logicaldoc.web.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.AccessControlEntry;
import com.logicaldoc.core.security.menu.Menu;
import com.logicaldoc.core.security.menu.MenuDAO;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIHistory;
import com.logicaldoc.web.AbstractWPTestCase;

/**
 * Checks who may run the history and API call searches in
 * {@link SystemServiceImpl}. Their results hold every user's session IDs, and
 * a session ID sent in the <code>sid</code> header logs in as that session, so
 * only users who can open the screens that run them may call them, and only
 * users who can open the sessions get the session IDs, as in the history data
 * servlets.
 */
public class SystemServiceImplAuthorizationTest extends AbstractWPTestCase {

    // Reports > Last changes, which runs search()
    private static final long LAST_CHANGES = -2L;

    // Reports > API calls, which runs searchApiCalls()
    private static final long API_CALLS = -8L;

    // Settings > Protocols, whose API calls tab runs searchApiCalls()
    private static final long PROTOCOLS = 3L;

    // The author user's group. The author isn't an administrator.
    private static final long AUTHOR_GROUP = 2L;

    private SystemServiceImpl testSubject = new SystemServiceImpl();

    @Test
    public void testSearchNeedsTheLastChangesMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, this::search);

        grant(LAST_CHANGES);
        assertFalse(search().isEmpty());
    }

    @Test
    public void testSearchApiCallsNeedsTheApiCallsMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, this::searchApiCalls);

        grant(API_CALLS);
        assertFalse(searchApiCalls().isEmpty());
    }

    @Test
    public void testSearchApiCallsAcceptsTheProtocolsMenu() throws ServerException, PersistenceException {
        grant(PROTOCOLS);
        prepareSession("author", "admin");
        assertFalse(searchApiCalls().isEmpty());
    }

    @Test
    public void testOtherMenusDontOpenTheSearches() throws ServerException, PersistenceException {
        grant(API_CALLS);
        grant(PROTOCOLS);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, this::search);

        grant(LAST_CHANGES);
        MenuDAO dao = MenuDAO.get();
        for (long menuId : List.of(API_CALLS, PROTOCOLS)) {
            Menu menu = dao.findById(menuId, true);
            menu.removeAccessControlEntries(AUTHOR_GROUP);
            dao.store(menu);
        }
        assertThrows(AccessDeniedException.class, this::searchApiCalls);
    }

    @Test
    public void testSearchShowsSessionIdsOnlyWithTheSessionsMenu() throws ServerException, PersistenceException {
        String adminSid = session.getSid();
        grant(LAST_CHANGES);
        prepareSession("author", "admin");

        List<GUIHistory> hits = search();
        assertFalse(hits.isEmpty());
        assertTrue(sessionIds(hits).isEmpty());

        grant(Menu.SESSIONS);
        assertTrue(sessionIds(search()).contains(adminSid));
    }

    @Test
    public void testSearchApiCallsShowsSessionIdsOnlyWithTheSessionsMenu()
            throws ServerException, PersistenceException {
        grant(API_CALLS);
        prepareSession("author", "admin");

        List<GUIHistory> hits = searchApiCalls();
        assertFalse(hits.isEmpty());
        assertTrue(sessionIds(hits).isEmpty());

        grant(Menu.SESSIONS);
        assertTrue(sessionIds(searchApiCalls()).contains("8d7b32e8-fe82-45c4-b0e1-f69083e90133"));
    }

    private List<GUIHistory> search() throws ServerException {
        return testSubject.search(null, null, null, null, 100, null, null, null);
    }

    private List<GUIHistory> searchApiCalls() throws ServerException {
        return testSubject.searchApiCalls(null, null, null, null, null, null, 100);
    }

    private static Set<String> sessionIds(List<GUIHistory> hits) {
        return hits.stream().map(GUIHistory::getSessionId).filter(StringUtils::isNotEmpty).collect(Collectors.toSet());
    }

    private void grant(long menuId) throws PersistenceException {
        MenuDAO dao = MenuDAO.get();
        Menu menu = dao.findById(menuId, true);
        menu.addAccessControlEntry(new AccessControlEntry(AUTHOR_GROUP));
        dao.store(menu);
    }
}
