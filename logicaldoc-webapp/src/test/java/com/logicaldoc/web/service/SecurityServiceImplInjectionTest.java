package com.logicaldoc.web.service;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;

import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;

import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.util.junit.SqlRecorder;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.WebappTestContext;

/**
 * Checks that {@link SecurityServiceImpl#searchUsers(String, String)} doesn't
 * let its arguments rewrite the query. Doubling the quotes doesn't protect a
 * quoted value on MariaDB and MySQL, where a backslash escapes the closing
 * quote.
 */
public class SecurityServiceImplInjectionTest extends AbstractWPTestCase {

    private static final String VALUE = "zzz\\' or 1=1 -- ";

    private final SqlRecorder sql = new SqlRecorder();

    private SecurityServiceImpl testSubject = new SecurityServiceImpl();

    @Override
    protected ApplicationContext buildApplicationContext() {
        return sql.buildApplicationContext(WebappTestContext.class);
    }

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        sql.clear();
    }

    @Test
    public void testSearchUsersBindsTheUsername() throws ServerException {
        assertTrue(testSubject.searchUsers(VALUE, null).isEmpty());
        sql.assertNotInlined(VALUE);
    }

    @Test
    public void testSearchUsersRejectsAGroupIdThatIsNotANumber() {
        // The group ID isn't quoted at all, so this would list every user
        assertThrows(NumberFormatException.class, () -> testSubject.searchUsers(null, "1 or 1=1"));
    }
}
