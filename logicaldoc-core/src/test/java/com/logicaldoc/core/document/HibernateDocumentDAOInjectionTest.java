package com.logicaldoc.core.document;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.util.junit.SqlRecorder;
import com.logicaldoc.util.plugin.PluginException;

/**
 * Checks that {@link HibernateDocumentDAO} binds the file names, custom IDs
 * and tags it gets from users as parameters. Doubling the quotes doesn't
 * protect a quoted value on MariaDB and MySQL, where a backslash escapes the
 * closing quote.
 */
public class HibernateDocumentDAOInjectionTest extends AbstractCoreTestCase {

    private static final String VALUE = "zzz\\' or 1=1 -- ";

    private final SqlRecorder sql = new SqlRecorder();

    private DocumentDAO testSubject;

    @Override
    protected ApplicationContext buildApplicationContext() {
        return sql.buildApplicationContext();
    }

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        testSubject = DocumentDAO.get();
        sql.clear();
    }

    @Test
    public void testStoreBindsTheFileName() throws PersistenceException {
        // No extension, or the base name would lose everything up to the
        // backslash
        Document doc = testSubject.findById(1L, true);
        doc.setFileName(VALUE);
        testSubject.store(doc);
        sql.assertNotInlined(VALUE);
    }

    @Test
    public void testFindByFileNameAndParentFolderIdBindsTheFileName() throws PersistenceException {
        assertTrue(testSubject.findByFileNameAndParentFolderId(null, VALUE, null, 1L, null).isEmpty());
        sql.assertNotInlined(VALUE);
    }

    @Test
    public void testFindByCustomIdBindsTheCustomId() throws PersistenceException {
        assertNull(testSubject.findByCustomId(VALUE, 1L));
        sql.assertNotInlined(VALUE);
    }

    @Test
    public void testFindByUserIdAndTagBindsTheTag() throws PersistenceException {
        // User 4 isn't an admin, so the tag goes through the folder-restricted
        // query
        assertTrue(testSubject.findByUserIdAndTag(4L, VALUE, null).isEmpty());
        sql.assertNotInlined(VALUE);
    }

    @Test
    public void testCleanUnexistingUniqueTagsOneByOneBindsTheTags() throws PersistenceException {
        // A user's tag, still in use, so its unique tag must survive
        testSubject.jdbcUpdate("insert into ld_tag(ld_docid, ld_tenantid, ld_tag) values (1, 1, :tag)",
                Map.of("tag", VALUE));
        testSubject.jdbcUpdate("insert into ld_uniquetag(ld_tag, ld_tenantid, ld_count) values (:tag, 1, 1)",
                Map.of("tag", VALUE));
        sql.clear();

        testSubject.cleanUnexistingUniqueTagsOneByOne();

        sql.assertNotInlined(VALUE);
        assertEquals(List.of(VALUE), testSubject.queryForList("select ld_tag from ld_uniquetag", String.class));
    }
}
