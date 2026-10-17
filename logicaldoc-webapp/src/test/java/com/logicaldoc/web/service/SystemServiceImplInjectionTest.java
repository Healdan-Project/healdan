package com.logicaldoc.web.service;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.util.junit.SqlRecorder;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.WebappTestContext;

/**
 * Checks that the history search in {@link SystemServiceImpl} binds the
 * values it gets from users as parameters. Doubling the quotes doesn't
 * protect a quoted value on MariaDB and MySQL, where a backslash escapes the
 * closing quote.
 */
public class SystemServiceImplInjectionTest extends AbstractWPTestCase {

    private static final String VALUE = "zzz\\' or 1=1 -- ";

    private final SqlRecorder sql = new SqlRecorder();

    private SystemServiceImpl testSubject = new SystemServiceImpl();

    @Override
    protected ApplicationContext buildApplicationContext() {
        return sql.buildApplicationContext(WebappTestContext.class);
    }

    @Override
    protected List<String> getPluginArchives() {
        return List.of("/logicaldoc-core-plugin.jar", "/logicaldoc-dummy-plugin.jar");
    }

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        sql.clear();
    }

    @Test
    public void testSearchBindsTheEvents() throws ServerException {
        assertTrue(testSubject.search(null, null, null, null, 100, null, List.of("event.stored", VALUE), null)
                .isEmpty());
        sql.assertNotInlined(VALUE);
    }

    @Test
    public void testSearchBindsTheSessionId() throws ServerException {
        assertTrue(testSubject.search(null, null, null, null, 100, VALUE, null, null).isEmpty());
        sql.assertNotInlined(VALUE);
    }

    @Test
    public void testSearchBindsTheImpersonator() throws ServerException, PersistenceException {
        UserDAO.get().jdbcUpdate("update ld_user set ld_username = :username where ld_id = 3",
                Map.of("username", VALUE));
        sql.clear();

        assertTrue(testSubject.search(null, 3L, null, null, 100, null, null, null).isEmpty());
        sql.assertNotInlined(VALUE);
    }
}
