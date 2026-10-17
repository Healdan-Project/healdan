package com.logicaldoc.core.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.util.junit.SqlRecorder;
import com.logicaldoc.util.plugin.PluginException;

/**
 * Checks that {@link HibernateAttributeOptionDAO} binds the attribute names it
 * gets from users as parameters. Doubling the quotes doesn't protect a quoted
 * value on MariaDB and MySQL, where a backslash escapes the closing quote.
 */
public class HibernateAttributeOptionDAOInjectionTest extends AbstractCoreTestCase {

    private static final String VALUE = "zzz\\' or 1=1 -- ";

    private final SqlRecorder sql = new SqlRecorder();

    private AttributeOptionDAO testSubject;

    @Override
    protected ApplicationContext buildApplicationContext() {
        return sql.buildApplicationContext();
    }

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        testSubject = AttributeOptionDAO.get();
        sql.clear();
    }

    @Test
    public void testDeleteOrphanedBindsTheAttributeNames() throws PersistenceException {
        assertEquals(4, testSubject.findByAttribute(1L, "att1").size());
        sql.clear();

        testSubject.deleteOrphaned(1L, List.of("pippo", VALUE));

        sql.assertNotInlined(VALUE);
        assertTrue(testSubject.findByAttribute(1L, "att1").isEmpty());
    }
}
