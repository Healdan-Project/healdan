// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Device;
import com.logicaldoc.core.security.DeviceDAO;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.menu.Menu;
import com.logicaldoc.core.security.menu.MenuDAO;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.WorkingTime;
import com.logicaldoc.core.sequence.SequenceDAO;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIAccessControlEntry;
import com.logicaldoc.gui.common.client.beans.GUIMenu;
import com.logicaldoc.gui.common.client.beans.GUISequence;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.io.FileUtil;
import com.logicaldoc.util.spring.Context;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.AuthorMenus;
import com.logicaldoc.web.UploadServlet;

/**
 * Checks who may call the RPCs in {@link SecurityServiceImpl} that act on
 * other users or on the system. Killing a session needs the menu of the
 * Sessions tab, as the sessions data servlet's kill already does. Signing out
 * kills the user's own session, so it doesn't.
 * <p>
 * Saving, deleting or reading a custom action needs GUI settings, whose Custom
 * actions tab manages them, and any other menu needs Access control; reading a
 * custom action is also open to whoever may run it. The RPCs of Access
 * control's screens need Access control, the blocked sign-ins need the Brute
 * force prevention menu, and a trusted device is changed only by its owner.
 */
public class SecurityServiceImplAuthorizationTest extends AbstractWPTestCase {

    // The fixture's admin and author users, and the author group
    private static final long ADMIN_ID = 1L;

    private static final long AUTHOR_ID = 4L;

    private static final long AUTHOR_GROUP = 2L;

    // Settings > GUI settings, which the fixture gives the author's own group
    private static final long GUI_SETTINGS = 102L;

    // The fixture's counter of the author's failed sign-ins
    private static final long AUTHOR_SIGNIN_FAILURES = -29556736L;

    private static final String GEOLOCATION_KEY = "security.geolocation.apikey";

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
    public void testSavingABuiltInMenuNeedsAccessControl() throws ServerException, PersistenceException {
        // Giving the author group Access control's menu would let the author
        // add themselves to the admin group
        GUIMenu accessControl = testSubject.getMenu(Menu.ACCESS_CONTROL, "en");
        accessControl.getAccessControlList().add(ace(AUTHOR_GROUP));
        GUIMenu builtIn = testSubject.getMenu(-102L, "en");

        // GUI settings, which the author has, lets them change custom actions only
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.saveMenu(accessControl, "en"));
        assertThrows(AccessDeniedException.class, () -> testSubject.saveMenus(List.of(accessControl), "en"));
        // Sending it as a custom action changes nothing: the stored menu counts
        accessControl.setType(Menu.TYPE_CUSTOM_ACTION);
        accessControl.setParentId(Menu.CUSTOM_ACTIONS);
        assertThrows(AccessDeniedException.class, () -> testSubject.saveMenu(accessControl, "en"));
        assertFalse(MenuDAO.get().isReadAllowed(Menu.ACCESS_CONTROL, AUTHOR_ID));
        assertThrows(AccessDeniedException.class, () -> testSubject.deleteMenu(-103L));
        assertNotNull(MenuDAO.get().findById(-103L));

        AuthorMenus.grant(Menu.ACCESS_CONTROL);
        builtIn.setDescription("changed");
        assertEquals("changed", testSubject.saveMenu(builtIn, "en").getDescription());
        assertEquals("changed", MenuDAO.get().findById(-102L).getDescription());
        testSubject.deleteMenu(-103L);
        assertNull(MenuDAO.get().findById(-103L));
    }

    @Test
    public void testCustomActionsNeedTheGuiSettingsMenu() throws ServerException, PersistenceException {
        addCustomActionsMenu();
        long stampId = testSubject.saveMenu(customAction("Stamp"), "en").getId();
        GUIMenu builtIn = testSubject.getMenu(-102L, "en");

        prepareSession("author", "admin");
        AuthorMenus.revoke(GUI_SETTINGS);
        GUIMenu sign = customAction("Sign");
        assertThrows(AccessDeniedException.class, () -> testSubject.saveMenu(sign, "en"));
        assertThrows(AccessDeniedException.class, () -> testSubject.saveMenus(List.of(sign), "en"));
        assertTrue(MenuDAO.get().findByName("Sign").isEmpty());
        assertThrows(AccessDeniedException.class, () -> testSubject.deleteMenu(stampId));
        assertNotNull(MenuDAO.get().findById(stampId));

        AuthorMenus.grant(GUI_SETTINGS);
        GUIMenu saved = testSubject.saveMenu(sign, "en");
        assertEquals("Sign", MenuDAO.get().findById(saved.getId()).getName());
        saved.setDescription("changed");
        testSubject.saveMenus(List.of(saved), "en");
        assertEquals("changed", MenuDAO.get().findById(saved.getId()).getDescription());

        // A list holding a built-in menu is refused whole, before any is saved
        saved.setDescription("again");
        assertThrows(AccessDeniedException.class, () -> testSubject.saveMenus(List.of(saved, builtIn), "en"));
        assertEquals("changed", MenuDAO.get().findById(saved.getId()).getDescription());

        testSubject.deleteMenu(stampId);
        assertNull(MenuDAO.get().findById(stampId));
    }

    @Test
    public void testReadingAMenuNeedsWhatChangingItNeedsOrTheRightToRunIt()
            throws ServerException, PersistenceException {
        addCustomActionsMenu();
        GUIMenu runnable = customAction("Runnable");
        runnable.getAccessControlList().add(ace(AUTHOR_GROUP));
        long runnableId = testSubject.saveMenu(runnable, "en").getId();
        long otherId = testSubject.saveMenu(customAction("Other"), "en").getId();

        prepareSession("author", "admin");
        AuthorMenus.revoke(GUI_SETTINGS);
        // The context menus read an action the user may run before running it
        assertEquals("Runnable", testSubject.getMenu(runnableId, "en").getName());
        assertThrows(AccessDeniedException.class, () -> testSubject.getMenu(otherId, "en"));
        assertThrows(AccessDeniedException.class, () -> testSubject.getMenu(Menu.ACCESS_CONTROL, "en"));

        AuthorMenus.grant(GUI_SETTINGS);
        assertEquals("Other", testSubject.getMenu(otherId, "en").getName());
        assertThrows(AccessDeniedException.class, () -> testSubject.getMenu(Menu.ACCESS_CONTROL, "en"));

        // Access control's Menus tab reads the built-in menus
        AuthorMenus.grant(Menu.ACCESS_CONTROL);
        assertEquals(Menu.ACCESS_CONTROL, testSubject.getMenu(Menu.ACCESS_CONTROL, "en").getId());
    }

    @Test
    public void testReplicateUsersSettingsNeedsAccessControl() throws ServerException, PersistenceException {
        UserDAO dao = UserDAO.get();
        prepareSession("author", "admin");
        // Copying the admin's groups onto the author would make them an admin
        assertThrows(AccessDeniedException.class,
                () -> testSubject.replicateUsersSettings(ADMIN_ID, List.of(AUTHOR_ID), false, true));
        assertFalse(dao.findById(AUTHOR_ID, true).isMemberOf(Group.GROUP_ADMIN));

        AuthorMenus.grant(Menu.ACCESS_CONTROL);
        testSubject.replicateUsersSettings(ADMIN_ID, List.of(2L), false, true);
        assertTrue(dao.findById(2L, true).isMemberOf(Group.GROUP_ADMIN));
    }

    @Test
    public void testBlockedEntitiesNeedTheBruteForceMenu() throws ServerException, PersistenceException {
        SequenceDAO dao = SequenceDAO.get();
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.loadBlockedEntities());
        // Deleting their own counter would lift a block on the author
        assertThrows(AccessDeniedException.class,
                () -> testSubject.removeBlockedEntities(List.of(AUTHOR_SIGNIN_FAILURES)));
        assertNotNull(dao.findById(AUTHOR_SIGNIN_FAILURES));

        // CE's database has no row for the menu, so only administrators pass
        prepareSession("admin", "admin");
        List<GUISequence> blocked = testSubject.loadBlockedEntities();
        assertTrue(blocked.stream().anyMatch(s -> s.getId() == AUTHOR_SIGNIN_FAILURES));
        testSubject.removeBlockedEntities(List.of(AUTHOR_SIGNIN_FAILURES));
        assertNull(dao.findById(AUTHOR_SIGNIN_FAILURES));
    }

    @Test
    public void testSyncGeolocationDBNeedsAccessControl() throws ServerException, PersistenceException, IOException {
        ContextProperties config = Context.get().getConfig();
        String key = config.getProperty(GEOLOCATION_KEY, "");
        try {
            // A key with spaces makes the download's address invalid, so the
            // call fails before it connects to MaxMind
            prepareSession("author", "admin");
            assertThrows(AccessDeniedException.class, () -> testSubject.syncGeolocationDB("not a key"));
            assertEquals(key, config.getProperty(GEOLOCATION_KEY, ""));

            AuthorMenus.grant(Menu.ACCESS_CONTROL);
            assertThrows(IllegalArgumentException.class, () -> testSubject.syncGeolocationDB("not a key"));
            assertEquals("not a key", config.getProperty(GEOLOCATION_KEY));
        } finally {
            config.setProperty(GEOLOCATION_KEY, key);
            config.write();
        }
    }

    @Test
    public void testTrustedDevicesNeedTheirOwner() throws ServerException, PersistenceException {
        DeviceDAO dao = DeviceDAO.get();
        Device adminDevice = device(ADMIN_ID, "admin's");
        Device authorDevice = device(AUTHOR_ID, "author's");

        // Account > Trusted devices lists only the user's own, so not even an
        // administrator changes another user's
        assertThrows(AccessDeniedException.class, () -> testSubject.updateDeviceLabel(authorDevice.getId(), "admin's"));
        assertThrows(AccessDeniedException.class,
                () -> testSubject.deleteTrustedDevices(List.of(authorDevice.getId())));
        assertEquals("author's", dao.findById(authorDevice.getId()).getLabel());

        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.updateDeviceLabel(adminDevice.getId(), "author's"));
        assertEquals("admin's", dao.findById(adminDevice.getId()).getLabel());
        // A list holding another user's device is refused whole
        assertThrows(AccessDeniedException.class,
                () -> testSubject.deleteTrustedDevices(List.of(authorDevice.getId(), adminDevice.getId())));
        assertNotNull(dao.findById(authorDevice.getId()));
        assertNotNull(dao.findById(adminDevice.getId()));
        // A device that doesn't exist is refused the same way
        assertThrows(AccessDeniedException.class, () -> testSubject.updateDeviceLabel(999999L, "author's"));

        testSubject.updateDeviceLabel(authorDevice.getId(), "renamed");
        assertEquals("renamed", dao.findById(authorDevice.getId()).getLabel());
        testSubject.deleteTrustedDevices(List.of(authorDevice.getId()));
        assertNull(dao.findById(authorDevice.getId()));
    }

    @Test
    public void testAvatarsNeedTheirUserOrAccessControl() throws ServerException, PersistenceException, IOException {
        UserDAO dao = UserDAO.get();
        User admin = dao.findById(ADMIN_ID, true);
        admin.setAvatar("admin's");
        dao.store(admin);

        File avatarFile = new File("target/avatar.png");
        FileUtil.copyResource("avatar.png", avatarFile);
        try {
            prepareSession("author", "admin");
            assertThrows(AccessDeniedException.class, () -> testSubject.resetAvatar(ADMIN_ID));
            upload(avatarFile);
            assertThrows(AccessDeniedException.class, () -> testSubject.saveAvatar(ADMIN_ID));
            assertEquals("admin's", dao.findById(ADMIN_ID).getAvatar());

            // The user's own, from Account > Profile
            upload(avatarFile);
            testSubject.saveAvatar(AUTHOR_ID);
            testSubject.resetAvatar(AUTHOR_ID);

            // Another user's, from Access control > Users
            AuthorMenus.grant(Menu.ACCESS_CONTROL);
            testSubject.resetAvatar(ADMIN_ID);
            assertNotEquals("admin's", dao.findById(ADMIN_ID).getAvatar());
        } finally {
            FileUtil.delete(avatarFile);
        }
    }

    @Test
    public void testCloneWorkTimesNeedsAccessControl() throws ServerException, PersistenceException {
        UserDAO dao = UserDAO.get();
        User admin = dao.findById(ADMIN_ID, true);
        admin.getWorkingTimes().add(new WorkingTime(2, 9, 0));
        dao.store(admin);

        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class,
                () -> testSubject.cloneWorkTimes(ADMIN_ID, List.of(AUTHOR_ID), null));
        assertTrue(dao.findById(AUTHOR_ID, true).getWorkingTimes().isEmpty());

        AuthorMenus.grant(Menu.ACCESS_CONTROL);
        testSubject.cloneWorkTimes(ADMIN_ID, List.of(AUTHOR_ID), null);
        assertEquals(1, dao.findById(AUTHOR_ID, true).getWorkingTimes().size());
    }

    /**
     * Adds Custom actions' menu (1300), the custom actions' parent, which
     * LogicalDOC's paid edition has and CE's database lacks
     */
    private static void addCustomActionsMenu() throws PersistenceException {
        MenuDAO.get().jdbcUpdate("""
                insert into ld_menu (ld_id, ld_lastmodified, ld_creation, ld_deleted, ld_name, ld_parentid, ld_icon,
                                     ld_type, ld_tenantid, ld_recordversion, ld_position, ld_enabled)
                values (1300, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 'customactions', 1, 'menu.png', 1, 1, 1, 1, 1)
                """);
    }

    /**
     * A new custom action, as the Custom actions tab adds one
     */
    private static GUIMenu customAction(String name) {
        GUIMenu action = new GUIMenu();
        action.setName(name);
        action.setType(Menu.TYPE_CUSTOM_ACTION);
        action.setEnabled(true);
        action.setParentId(Menu.CUSTOM_ACTIONS);
        return action;
    }

    private static GUIAccessControlEntry ace(long groupId) {
        GUIAccessControlEntry ace = new GUIAccessControlEntry();
        ace.setEntityId(groupId);
        return ace;
    }

    private static Device device(long userId, String label) throws PersistenceException {
        Device device = new Device();
        device.setUserId(userId);
        device.setTrusted(true);
        device.setLabel(label);
        DeviceDAO.get().store(device);
        return device;
    }

    /**
     * Puts the file among the current session's uploads, as the upload
     * servlet does
     */
    private void upload(File file) {
        Map<String, File> uploads = new HashMap<>();
        uploads.put(file.getName(), file);
        session.getDictionary().put(UploadServlet.UPLOADS, uploads);
    }
}
