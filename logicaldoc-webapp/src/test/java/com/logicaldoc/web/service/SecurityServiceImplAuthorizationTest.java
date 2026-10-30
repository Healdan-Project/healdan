package com.logicaldoc.web.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.menu.Menu;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.AuthorMenus;

/**
 * Checks who may call the session RPCs in {@link SecurityServiceImpl}. Killing a
 * session needs the menu of the Sessions tab, as the sessions data servlet's
 * kill already does. Signing out kills the user's own session, so it doesn't.
 */
public class SecurityServiceImplAuthorizationTest extends AbstractWPTestCase {

    private SecurityServiceImpl testSubject = new SecurityServiceImpl();

    @Test
    public void testKillNeedsTheSessionsMenu() throws ServerException, PersistenceException {
        String adminSid = session.getSid();
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.kill(adminSid));
        assertTrue(SessionManager.get().isOpen(adminSid));

        AuthorMenus.grant(Menu.ADMIN_SESSIONS);
        testSubject.kill(adminSid);
        assertFalse(SessionManager.get().isOpen(adminSid));
    }

    @Test
    public void testLogoutNeedsNoMenu() throws ServerException {
        // Signing out kills the user's own session, which needs no menu
        prepareSession("author", "admin");
        String sid = session.getSid();
        testSubject.logout();
        assertFalse(SessionManager.get().isOpen(sid));
    }
}
