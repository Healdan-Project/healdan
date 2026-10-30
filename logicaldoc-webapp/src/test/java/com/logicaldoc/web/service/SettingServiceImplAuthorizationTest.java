package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
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

    private SettingServiceImpl testSubject = new SettingServiceImpl();

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
}
