// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.apikey.ApiKey;
import com.logicaldoc.core.security.apikey.ApiKeyDAO;
import com.logicaldoc.core.security.menu.Menu;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.AuthorMenus;

/**
 * Checks who may call the session RPCs in {@link SecurityServiceImpl}. Killing a
 * session needs the menu of the Sessions tab, as the sessions data servlet's
 * kill already does. Signing out kills the user's own session, so it doesn't.
 * An API key is changed or deleted only by its owner, as Account > API keys
 * lists only the user's own.
 */
public class SecurityServiceImplAuthorizationTest extends AbstractWPTestCase {

    // The fixture's admin and author users
    private static final long ADMIN_ID = 1L;

    private static final long AUTHOR_ID = 4L;

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

    @Test
    public void testDeleteApiKeyNeedsTheKeysOwner() throws ServerException, PersistenceException {
        ApiKeyDAO dao = ApiKeyDAO.get();
        ApiKey adminKey = new ApiKey(ADMIN_ID, "admin's");
        dao.store(adminKey);
        ApiKey authorKey = new ApiKey(AUTHOR_ID, "author's");
        dao.store(authorKey);

        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.deleteApiKey(adminKey.getId()));
        assertEquals(adminKey.getId(), dao.findById(adminKey.getId()).getId());
        // Nor can an administrator delete another user's, which no screen lists
        prepareSession("admin", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.deleteApiKey(authorKey.getId()));
        assertEquals(authorKey.getId(), dao.findById(authorKey.getId()).getId());

        testSubject.deleteApiKey(adminKey.getId());
        assertNull(dao.findById(adminKey.getId()));
        prepareSession("author", "admin");
        testSubject.deleteApiKey(authorKey.getId());
        assertNull(dao.findById(authorKey.getId()));
    }

    @Test
    public void testUpdateApiKeyNeedsTheKeysOwner() throws ServerException, PersistenceException {
        ApiKeyDAO dao = ApiKeyDAO.get();
        ApiKey adminKey = new ApiKey(ADMIN_ID, "admin's");
        dao.store(adminKey);

        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.updateApiKey(adminKey.getId(), "author's"));
        assertEquals("admin's", dao.findById(adminKey.getId()).getName());
        // A key that doesn't exist is refused the same way
        assertThrows(AccessDeniedException.class, () -> testSubject.updateApiKey(999999L, "author's"));

        prepareSession("admin", "admin");
        testSubject.updateApiKey(adminKey.getId(), "renamed");
        assertEquals("renamed", dao.findById(adminKey.getId()).getName());
    }
}
