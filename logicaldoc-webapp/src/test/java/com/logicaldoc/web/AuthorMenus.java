package com.logicaldoc.web;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.AccessControlEntry;
import com.logicaldoc.core.security.menu.Menu;
import com.logicaldoc.core.security.menu.MenuDAO;

/**
 * Gives the fixture's author user a menu or takes it away, so a test can check
 * who may call an RPC. The author isn't an administrator, and only a menu's
 * own access control list decides who opens it.
 */
public final class AuthorMenus {

    // The author user's groups: author, and the user's own group
    private static final long AUTHOR_GROUP = 2L;

    private static final long AUTHOR_USER_GROUP = -4L;

    private AuthorMenus() {
    }

    /**
     * Lets the author open the menu, through the author group
     *
     * @param menuId identifier of the menu
     *
     * @throws PersistenceException error in the database
     */
    public static void grant(long menuId) throws PersistenceException {
        MenuDAO dao = MenuDAO.get();
        Menu menu = dao.findById(menuId, true);
        menu.addAccessControlEntry(new AccessControlEntry(AUTHOR_GROUP));
        dao.store(menu);
    }

    /**
     * Takes the menu from both of the author's groups, since the fixture gives
     * some menus to the user's own group
     *
     * @param menuId identifier of the menu
     *
     * @throws PersistenceException error in the database
     */
    public static void revoke(long menuId) throws PersistenceException {
        MenuDAO dao = MenuDAO.get();
        Menu menu = dao.findById(menuId, true);
        menu.removeAccessControlEntries(AUTHOR_GROUP);
        menu.removeAccessControlEntries(AUTHOR_USER_GROUP);
        dao.store(menu);
    }
}
