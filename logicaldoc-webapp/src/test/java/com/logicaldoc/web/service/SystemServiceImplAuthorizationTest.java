package com.logicaldoc.web.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import com.logicaldoc.gui.common.client.beans.GUITask;
import com.logicaldoc.web.AbstractWPTestCase;

/**
 * Checks who may call the RPCs in {@link SystemServiceImpl}. Each one needs a
 * menu that opens a screen calling it, so a screen delegated through menu
 * security keeps working and nobody else gets in.
 * <p>
 * The history and API call searches' results hold every user's session IDs,
 * and a session ID sent in the <code>sid</code> header logs in as that
 * session, so only users who can open the sessions get the session IDs, as in
 * the history data servlets.
 */
public class SystemServiceImplAuthorizationTest extends AbstractWPTestCase {

    // Reports > Last changes, which runs search()
    private static final long LAST_CHANGES = -2L;

    // Reports > API calls, which runs searchApiCalls()
    private static final long API_CALLS = -8L;

    // Settings > Protocols, whose API calls tab runs searchApiCalls()
    private static final long PROTOCOLS = 3L;

    // System, whose Scheduled tasks screen manages the tasks and jobs
    private static final long SYSTEM = 80L;

    // System > General, whose tabs show the statistics and the plugins
    private static final long GENERAL = 70L;

    // The author user's group. The author isn't an administrator.
    private static final long AUTHOR_GROUP = 2L;

    private static final String TASK = "IndexerTask";

    private SystemServiceImpl testSubject = new SystemServiceImpl();

    @Override
    protected List<String> getPluginArchives() {
        // The core plugin declares the tasks
        return List.of("/logicaldoc-core-plugin.jar");
    }

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
        revoke(API_CALLS, AUTHOR_GROUP);
        revoke(PROTOCOLS, AUTHOR_GROUP);
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

    @Test
    public void testTaskReadsNeedTheSystemMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.loadTasks("en"));
        assertThrows(AccessDeniedException.class, () -> testSubject.getTaskByName(TASK, "en"));

        grant(SYSTEM);
        assertFalse(testSubject.loadTasks("en").isEmpty());
        assertNotNull(testSubject.getTaskByName(TASK, "en"));
    }

    @Test
    public void testTaskChangesNeedTheSystemMenu() throws ServerException, PersistenceException {
        GUITask task = testSubject.getTaskByName(TASK, "en");
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.saveTask(task, "en"));
        assertThrows(AccessDeniedException.class, () -> testSubject.disableTask(TASK));
        assertThrows(AccessDeniedException.class, () -> testSubject.enableTask(TASK));

        grant(SYSTEM);
        testSubject.saveTask(task, "en");
        assertTrue(testSubject.disableTask(TASK));
        assertTrue(testSubject.enableTask(TASK));
    }

    @Test
    public void testStartAndStopNeedTheSystemMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.startTask(TASK));
        assertThrows(AccessDeniedException.class, () -> testSubject.stopTask(TASK));

        // No task has this name, so nothing actually runs
        grant(SYSTEM);
        testSubject.startTask("unexisting");
        testSubject.stopTask("unexisting");
    }

    @Test
    public void testUnscheduleJobsNeedsTheSystemMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.unscheduleJobs(List.of()));

        grant(SYSTEM);
        testSubject.unscheduleJobs(List.of());
    }

    @Test
    public void testGetStatisticsNeedsTheGeneralMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.getStatistics("en"));

        grant(GENERAL);
        assertFalse(testSubject.getStatistics("en").isEmpty());
    }

    @Test
    public void testGetPluginsNeedsTheGeneralMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, testSubject::getPlugins);

        grant(GENERAL);
        assertFalse(testSubject.getPlugins().isEmpty());
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

    private static void revoke(long menuId, long groupId) throws PersistenceException {
        MenuDAO dao = MenuDAO.get();
        Menu menu = dao.findById(menuId, true);
        menu.removeAccessControlEntries(groupId);
        dao.store(menu);
    }
}
