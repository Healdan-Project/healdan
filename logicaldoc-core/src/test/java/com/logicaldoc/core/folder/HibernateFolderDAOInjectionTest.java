package com.logicaldoc.core.folder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
 * Checks that {@link HibernateFolderDAO} binds the names and tags it gets from
 * users as parameters. Doubling the quotes doesn't protect a quoted value on
 * MariaDB and MySQL, where a backslash escapes the closing quote.
 */
public class HibernateFolderDAOInjectionTest extends AbstractCoreTestCase {

    private static final String VALUE = "zzz\\' or 1=1 -- ";

    private final SqlRecorder sql = new SqlRecorder();

    private FolderDAO testSubject;

    @Override
    protected ApplicationContext buildApplicationContext() {
        return sql.buildApplicationContext();
    }

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        testSubject = FolderDAO.get();
        sql.clear();
    }

    @Test
    public void testFindByNameBindsTheName() throws PersistenceException {
        assertTrue(testSubject.findByName(null, VALUE, null, true).isEmpty());
        assertTrue(testSubject.findByName(null, VALUE, null, false).isEmpty());
        sql.assertNotInlined(VALUE);
    }

    @Test
    public void testFindByNameAndParentIdBindsTheName() throws PersistenceException {
        assertTrue(testSubject.findByNameAndParentId(VALUE, Folder.DEFAULTWORKSPACEID).isEmpty());
        sql.assertNotInlined(VALUE);
    }

    @Test
    public void testCreateBindsTheName() throws PersistenceException {
        Folder folderVO = new Folder();
        folderVO.setName(VALUE);
        testSubject.create(testSubject.findById(1202L), folderVO, true, null);
        sql.assertNotInlined(VALUE);
    }

    @Test
    public void testFindByUserIdAndTagBindsTheTag() throws PersistenceException {
        // The admin and other users take different queries
        assertTrue(testSubject.findByUserIdAndTag(1L, VALUE, null).isEmpty());
        assertTrue(testSubject.findByUserIdAndTag(4L, VALUE, null).isEmpty());
        sql.assertNotInlined(VALUE);

        assertEquals(1, testSubject.findByUserIdAndTag(4L, "ftag3", null).size());
    }
}
