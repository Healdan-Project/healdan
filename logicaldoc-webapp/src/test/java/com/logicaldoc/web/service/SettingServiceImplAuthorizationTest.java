package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
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

    private static List<String> names(List<GUIParameter> settings) {
        return settings.stream().map(GUIParameter::getName).toList();
    }
}
