// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIEmailSettings;
import com.logicaldoc.gui.common.client.beans.GUIParameter;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.AuthorMenus;
import com.logicaldoc.web.ImpersonatingProxy;

/**
 * Checks who may call the RPCs in {@link SettingServiceImpl} that checked only
 * a session. Each one needs the menu of the screen that calls it, so a screen
 * delegated through menu security keeps working and nobody else gets in.
 */
public class SettingServiceImplAuthorizationTest extends AbstractWPTestCase {

    // Settings > Proxy, whose Test button calls testProxy()
    private static final long PROXY = 99L;

    // Settings > Stores, whose Test button calls testStore()
    private static final long STORES = 105L;

    // Administration, the menu of every admin screen, which CE's poweruser
    // group holds
    private static final long ADMINISTRATION = 2L;

    // Settings, the section's menu
    private static final long SETTINGS = 7L;

    // Settings > Protocols
    private static final long PROTOCOLS = 3L;

    // Settings > Parameters
    private static final long PARAMETERS = 100L;

    // Settings > GUI settings, which the fixture gives the author's own group
    private static final long GUI_SETTINGS = 102L;

    // Settings > Outgoing email, which the fixture gives both author groups
    private static final long OUTGOING_EMAIL = 103L;

    // Settings > Auditing
    private static final long AUDITING = 106L;

    // System > General > Run level
    private static final long RUNLEVEL = 73L;

    // Document metadata, whose Tags screen writes the tag settings
    private static final long METADATA = 25L;

    // Some of the keys each screen writes through saveSettings(), none of
    // them written by another screen
    private static final Map<Long, List<String>> SCREEN_KEYS = Map.of(STORES,
            List.of("store.1.dir", "store.write", "conf.userdir"), PROXY, List.of("proxy.host", "proxy.password"),
            AUDITING, List.of("history.document.ttl", "default.history.events"), GUI_SETTINGS,
            List.of("default.gui.welcome", "default.gui.density", "default.upload.disallow", "default.session.timeout",
                    "default.search.hits", "default.charset"),
            PROTOCOLS, List.of("webdav.enabled", "webservice.basicauth.enabled", "cmis.enabled", "ftp.enabled"),
            RUNLEVEL, List.of("runlevel", "runlevel.back", "aspect.uniquenessfilename.default"), METADATA,
            List.of("default.tag.mode", "default.tag.select.maxtags", "default.tagcloud.maxtags"));

    // The settings Settings > Stores reads through loadSettingsByNames()
    private static final List<String> STORES_SETTINGS = List.of("conf.dbdir", "conf.exportdir", "conf.importdir",
            "conf.logdir", "conf.plugindir", "conf.userdir");

    // The settings Settings > Proxy reads through loadSettingsByNames()
    private static final List<String> PROXY_SETTINGS = List.of("proxy.enabled", "proxy.host", "proxy.port",
            "proxy.password", "proxy.username");

    private SettingServiceImpl testSubject = new SettingServiceImpl();

    @Test
    public void testLoadSettingsByNamesNeedsTheStoresMenuForTheFolders()
            throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.loadSettingsByNames(STORES_SETTINGS));

        AuthorMenus.grant(STORES);
        assertEquals(STORES_SETTINGS, names(testSubject.loadSettingsByNames(STORES_SETTINGS)));
        // Stores doesn't open the proxy's settings
        assertThrows(AccessDeniedException.class, () -> testSubject.loadSettingsByNames(PROXY_SETTINGS));
    }

    @Test
    public void testLoadSettingsByNamesNeedsTheProxyMenuForTheProxy() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.loadSettingsByNames(PROXY_SETTINGS));

        AuthorMenus.grant(PROXY);
        assertEquals(PROXY_SETTINGS, names(testSubject.loadSettingsByNames(PROXY_SETTINGS)));
        // Proxy doesn't open the store folders
        assertThrows(AccessDeniedException.class, () -> testSubject.loadSettingsByNames(STORES_SETTINGS));
    }

    @Test
    public void testLoadSettingsByNamesGivesOtherSettingsOnlyToAdministrators()
            throws ServerException, PersistenceException {
        // The database password, a tenant's email password, the paid screens'
        // keys and any prefix, whatever menus the user has
        AuthorMenus.grant(PROXY);
        AuthorMenus.grant(STORES);
        prepareSession("author", "admin");
        for (String name : List.of("jdbc.password", "default.smtp.password", "firewall.enabled", "*", "proxy.*",
                "conf.*"))
            assertThrows(name, AccessDeniedException.class, () -> testSubject.loadSettingsByNames(List.of(name)));

        // One name it may not read refuses the whole call
        assertThrows(AccessDeniedException.class,
                () -> testSubject.loadSettingsByNames(List.of("proxy.host", "jdbc.password")));

        prepareSession("admin", "admin");
        assertEquals(List.of("jdbc.password"), names(testSubject.loadSettingsByNames(List.of("jdbc.password"))));
        assertFalse(testSubject.loadSettingsByNames(List.of("firewall.*")).isEmpty());
    }

    @Test
    public void testSaveExtensionAliasesNeedsTheFormatConvertersMenu() throws ServerException, IOException {
        // CE's database has no Format converters menu (1750), so only
        // administrators pass, as for loadConverterParameters()
        ContextProperties config = Context.get().getConfig();
        prepareSession("author", "admin");
        try {
            assertThrows(AccessDeniedException.class, () -> testSubject.saveExtensionAliases("txt", "eft"));
            assertNull(config.getString("converter.alias.eft"));

            prepareSession("admin", "admin");
            testSubject.saveExtensionAliases("txt", "eft");
            assertEquals("txt", config.getString("converter.alias.eft"));
        } finally {
            config.remove("converter.alias.eft");
            config.write();
        }
    }

    @Test
    public void testTestProxyNeedsTheProxyMenu() throws Exception {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.testProxy("localhost", 1, null, null));

        AuthorMenus.grant(PROXY);
        try (ImpersonatingProxy proxy = new ImpersonatingProxy("docs.healdan.com", "<html><body></body></html>")) {
            // The proxy's certificate isn't trusted, so the test fails, but it
            // got as far as the proxy
            assertThrows(ServerException.class,
                    () -> testSubject.testProxy(proxy.getHost(), proxy.getPort(), null, null));
            assertEquals("CONNECT docs.healdan.com:443 HTTP/1.1", proxy.getConnect());
        }
    }

    @Test
    public void testTestStoreNeedsTheStoresMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.testStore(1));

        AuthorMenus.grant(STORES);
        assertTrue(testSubject.testStore(1));
    }

    @Test
    public void testSaveRegistrationNeedsAnAdministrator() throws ServerException, PersistenceException, IOException {
        // The admin menu's Registration item, now removed, showed only to the
        // admin group, and no menu ever covered it
        ContextProperties config = Context.get().getConfig();
        AuthorMenus.grant(PROXY);
        AuthorMenus.grant(STORES);
        prepareSession("author", "admin");
        try {
            assertThrows(AccessDeniedException.class,
                    () -> testSubject.saveRegistration("acme", "john@acme.com", "Acme", "https://www.acme.com"));
            assertNull(config.getString("reg.name"));

            prepareSession("admin", "admin");
            testSubject.saveRegistration("acme", "john@acme.com", "Acme", "https://www.acme.com");
            assertEquals("acme", config.getString("reg.name"));
        } finally {
            for (String key : new String[] { "reg.name", "reg.email", "reg.organization", "reg.website" })
                config.remove(key);
            config.write();
        }
    }

    @Test
    public void testSaveSettingsNeedsTheMenuOfTheScreenThatWritesEachKey() throws Exception {
        AuthorMenus.revoke(GUI_SETTINGS);
        AuthorMenus.grant(ADMINISTRATION);
        prepareSession("author", "admin");

        // Administration alone, which CE's poweruser group holds, writes none
        for (List<String> keys : SCREEN_KEYS.values())
            for (String key : keys)
                assertThrows(key, AccessDeniedException.class, () -> save(List.of(key)));

        // Each screen's menu writes the keys that screen writes, and no others
        for (Map.Entry<Long, List<String>> screen : SCREEN_KEYS.entrySet()) {
            AuthorMenus.grant(screen.getKey());
            save(screen.getValue());
            for (Map.Entry<Long, List<String>> other : SCREEN_KEYS.entrySet())
                if (!other.getKey().equals(screen.getKey()))
                    for (String key : other.getValue())
                        assertThrows(screen.getKey() + " " + key, AccessDeniedException.class,
                                () -> save(List.of(key)));
            AuthorMenus.revoke(screen.getKey());
        }

        // Two screens write these
        AuthorMenus.grant(METADATA);
        save(List.of("default.gui.tag.vocabulary"));
        AuthorMenus.revoke(METADATA);
        AuthorMenus.grant(AUDITING);
        save(List.of("webservice.call.ttl"));
    }

    @Test
    public void testSaveSettingsGivesOtherKeysOnlyToAdministrators() throws Exception {
        ContextProperties config = Context.get().getConfig();
        for (long menu : List.of(ADMINISTRATION, SETTINGS, PROTOCOLS, PARAMETERS, PROXY, GUI_SETTINGS, STORES,
                AUDITING, RUNLEVEL, METADATA))
            AuthorMenus.grant(menu);
        prepareSession("author", "admin");

        // The database's settings, the email password, the paid screens' keys,
        // another tenant's settings and a key nothing has
        for (String key : List.of("jdbc.url", "jdbc.password", "default.smtp.password", "firewall.enabled",
                "throttle.enabled", "acme.gui.welcome", "acme.tag.mode", "made.up.key"))
            assertThrows(key, AccessDeniedException.class, () -> save(List.of(key)));

        // One key it may not write refuses the whole save
        String host = config.getProperty("proxy.host");
        assertThrows(AccessDeniedException.class,
                () -> testSubject.saveSettings(List.of(new GUIParameter("proxy.host", "elsewhere"),
                        new GUIParameter("jdbc.url", config.getProperty("jdbc.url")))));
        assertEquals(host, config.getProperty("proxy.host"));

        prepareSession("admin", "admin");
        save(List.of("jdbc.url", "firewall.enabled"));
    }

    @Test
    public void testSaveSettingsLetsParametersWriteTheKeysItLists() throws Exception {
        AuthorMenus.grant(ADMINISTRATION);
        AuthorMenus.grant(PARAMETERS);
        prepareSession("author", "admin");

        List<String> listed = names(testSubject.loadSettings());
        assertFalse(listed.isEmpty());
        save(listed);

        // Parameters lists neither the database's settings nor a new key
        assertFalse(listed.contains("jdbc.url"));
        for (String key : List.of("jdbc.url", "made.up.key"))
            assertThrows(key, AccessDeniedException.class, () -> save(List.of(key)));
    }

    @Test
    public void testSaveStoreSettingsAndRemoveStoreNeedTheStoresMenu() throws Exception {
        ContextProperties config = Context.get().getConfig();
        AuthorMenus.grant(ADMINISTRATION);
        prepareSession("author", "admin");
        List<GUIParameter> store = List.of(new GUIParameter("store.3.dir", "target/tmp/repository/docs2/"),
                new GUIParameter("store.3.type", "fs"));
        try {
            assertThrows(AccessDeniedException.class, () -> testSubject.saveStoreSettings(store));
            assertNull(config.getString("store.3.dir"));

            prepareSession("admin", "admin");
            testSubject.saveStoreSettings(store);
            prepareSession("author", "admin");
            assertThrows(AccessDeniedException.class, () -> testSubject.removeStore(3));
            assertEquals("target/tmp/repository/docs2/", config.getString("store.3.dir"));

            AuthorMenus.grant(STORES);
            assertTrue(testSubject.removeStore(3).isEmpty());
            assertNull(config.getString("store.3.dir"));
            testSubject.saveStoreSettings(store);
            assertEquals("target/tmp/repository/docs2/", config.getString("store.3.dir"));
        } finally {
            prepareSession("admin", "admin");
            testSubject.removeStore(3);
        }
    }

    @Test
    public void testEmailSettingsNeedTheOutgoingEmailMenu() throws Exception {
        ContextProperties config = Context.get().getConfig();
        AuthorMenus.revoke(OUTGOING_EMAIL);
        AuthorMenus.grant(SETTINGS);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.loadEmailSettings());

        prepareSession("admin", "admin");
        GUIEmailSettings settings = testSubject.loadEmailSettings();
        String host = config.getProperty("default.smtp.host");
        settings.setServer("elsewhere");
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.saveEmailSettings(settings));
        assertEquals(host, config.getProperty("default.smtp.host"));

        AuthorMenus.grant(OUTGOING_EMAIL);
        testSubject.saveEmailSettings(testSubject.loadEmailSettings());
        assertEquals(host, config.getProperty("default.smtp.host"));
    }

    @Test
    public void testLoadSettingsNeedsTheParametersMenu() throws Exception {
        AuthorMenus.grant(SETTINGS);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.loadSettings());

        AuthorMenus.grant(PARAMETERS);
        assertFalse(testSubject.loadSettings().isEmpty());
    }

    @Test
    public void testProtocolSettingsNeedTheProtocolsMenu() throws Exception {
        AuthorMenus.grant(SETTINGS);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.loadProtocolSettings());
        assertThrows(AccessDeniedException.class, () -> testSubject.loadWebserviceStats(null));

        AuthorMenus.grant(PROTOCOLS);
        assertFalse(testSubject.loadProtocolSettings().isEmpty());
        assertEquals(2, testSubject.loadWebserviceStats(null).size());
    }

    @Test
    public void testLoadGUISettingsNeedsTheGUISettingsMenu() throws Exception {
        AuthorMenus.revoke(GUI_SETTINGS);
        AuthorMenus.grant(SETTINGS);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.loadGUISettings());

        AuthorMenus.grant(GUI_SETTINGS);
        assertFalse(testSubject.loadGUISettings().isEmpty());
    }

    /**
     * Saves each setting with the value it has, so nothing changes, and
     * removes again those that weren't there
     */
    private void save(List<String> names) throws ServerException, IOException {
        ContextProperties config = Context.get().getConfig();
        List<String> missing = names.stream().filter(name -> config.getProperty(name) == null).toList();
        try {
            testSubject.saveSettings(
                    names.stream().map(name -> new GUIParameter(name, config.getProperty(name))).toList());
        } finally {
            missing.forEach(config::remove);
            config.write();
        }
    }

    private static List<String> names(List<GUIParameter> settings) {
        return settings.stream().map(GUIParameter::getName).toList();
    }
}
