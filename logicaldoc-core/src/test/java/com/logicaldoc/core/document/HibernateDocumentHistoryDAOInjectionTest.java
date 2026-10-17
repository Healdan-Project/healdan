package com.logicaldoc.core.document;

import java.io.IOException;
import java.sql.SQLException;

import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.util.junit.SqlRecorder;
import com.logicaldoc.util.plugin.PluginException;

/**
 * Checks that {@link HibernateDocumentHistoryDAO} binds the event it gets
 * from users as a parameter. Doubling the quotes doesn't protect a quoted
 * value on MariaDB and MySQL, where a backslash escapes the closing quote.
 */
public class HibernateDocumentHistoryDAOInjectionTest extends AbstractCoreTestCase {

    private static final String VALUE = "zzz\\' or 1=1 -- ";

    private final SqlRecorder sql = new SqlRecorder();

    private DocumentHistoryDAO testSubject;

    @Override
    protected ApplicationContext buildApplicationContext() {
        return sql.buildApplicationContext();
    }

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        testSubject = DocumentHistoryDAO.get();
        sql.clear();
    }

    @Test
    public void testMarkHistoriesAsReadBindsTheEvent() throws PersistenceException {
        testSubject.markHistoriesAsRead(VALUE, 1L);
        sql.assertNotInlined(VALUE);
    }
}
