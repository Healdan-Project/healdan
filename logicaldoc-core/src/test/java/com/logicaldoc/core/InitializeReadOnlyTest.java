package com.logicaldoc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.security.authentication.PasswordAlreadyUsedException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * {@code initialize} only reads. It merges the detached copy it's given into
 * its own session to load the lazy data, and CE flushed whatever that copy
 * didn't share with the session's copy. On MariaDB and MySQL that was every
 * read: the {@code datetime} columns drop the milliseconds that the
 * second-level cache keeps, so listing the users rewrote each user's row, and
 * concurrent listings failed on the optimistic lock (issue #51).
 */
public class InitializeReadOnlyTest extends AbstractCoreTestCase {

    /**
     * The fixture's user "author", a member of three groups
     */
    private static final long USER_ID = 4L;

    private static final long DOC_ID = 1L;

    private UserDAO userDao;

    private DocumentDAO documentDao;

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        userDao = UserDAO.get();
        documentDao = DocumentDAO.get();
    }

    @Test
    public void testInitializingAUserLeavesItsRowAlone() throws PersistenceException {
        User user = userDao.findById(USER_ID);
        long recordVersion = recordVersion("ld_user", USER_ID);
        Timestamp lastModified = lastModified("ld_user", USER_ID);
        shiftLastModified(user);

        User initialized = userDao.initialize(user);

        assertEquals(3, initialized.getGroups().size());
        assertEquals(user.getLastModified(), initialized.getLastModified());
        assertEquals(recordVersion, recordVersion("ld_user", USER_ID));
        assertEquals(lastModified, lastModified("ld_user", USER_ID));
    }

    /**
     * The users listing behind every user picker initializes the users this way
     */
    @Test
    public void testInitializingUsersLeavesTheirRowsAlone() throws PersistenceException {
        User user = userDao.findById(USER_ID);
        long recordVersion = recordVersion("ld_user", USER_ID);
        shiftLastModified(user);

        List<User> initialized = userDao.initialize(List.of(user));

        assertEquals(3, initialized.get(0).getGroups().size());
        assertEquals(recordVersion, recordVersion("ld_user", USER_ID));
    }

    /**
     * The GUI initializes a document this way each time it opens one
     */
    @Test
    public void testInitializingADocumentLeavesItsRowAlone() throws PersistenceException {
        Document doc = documentDao.findById(DOC_ID);
        long recordVersion = recordVersion("ld_document", DOC_ID);
        Timestamp lastModified = lastModified("ld_document", DOC_ID);
        shiftLastModified(doc);

        documentDao.initialize(doc);

        assertEquals(recordVersion, recordVersion("ld_document", DOC_ID));
        assertEquals(lastModified, lastModified("ld_document", DOC_ID));
    }

    /**
     * The initialized copy keeps the caller's changes, so storing it saves them,
     * as {@code DocumentManager} does after setting the indexing status
     */
    @Test
    public void testAChangeStoredAfterInitializeIsSaved() throws PersistenceException {
        Document doc = documentDao.findById(DOC_ID);
        doc.setComment("changed before initialize");

        doc = documentDao.initialize(doc);
        documentDao.store(doc, new DocumentHistory());

        assertEquals("changed before initialize",
                documentDao.queryForString("select ld_comment from ld_document where ld_id=" + DOC_ID));
    }

    /**
     * As saving a user or an avatar does: the user's groups are rebuilt in the
     * read-only session, and storing the copy still saves the change and keeps
     * them
     */
    @Test
    public void testAUserChangedAfterInitializeIsSaved() throws PersistenceException, PasswordAlreadyUsedException {
        String countGroups = "select count(*) from ld_usergroup where ld_userid=" + USER_ID;
        long groups = userDao.queryForLong(countGroups);

        User user = userDao.initialize(userDao.findById(USER_ID));
        user.setCity("changed after initialize");
        userDao.store(user);

        assertEquals("changed after initialize",
                userDao.queryForString("select ld_city from ld_user where ld_id=" + USER_ID));
        assertEquals(groups, userDao.queryForLong(countGroups));
    }

    /**
     * Inside another transaction {@code initialize} joins it and isn't
     * read-only, so a change on the copy it's given is saved with that
     * transaction, as before
     */
    @Test
    public void testInitializeInsideAnotherTransactionSavesTheChange() throws PersistenceException {
        Document doc = documentDao.findById(DOC_ID);
        doc.setComment("changed inside a transaction");

        new TransactionTemplate(Context.get(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            try {
                documentDao.initialize(doc);
            } catch (PersistenceException e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals("changed inside a transaction",
                documentDao.queryForString("select ld_comment from ld_document where ld_id=" + DOC_ID));
    }

    /**
     * A new entity has nothing to load, so it comes back as it was, unsaved
     */
    @Test
    public void testInitializingANewDocumentLeavesItUnsaved() throws PersistenceException {
        String countDocuments = "select count(*) from ld_document";
        long documents = documentDao.queryForLong(countDocuments);
        Document doc = new Document();
        doc.setFileName("new.txt");

        assertSame(doc, documentDao.initialize(doc));
        assertEquals(0L, doc.getId());
        assertEquals(documents, documentDao.queryForLong(countDocuments));
    }

    /**
     * Moves a detached copy's last modified date by a few milliseconds, as the
     * second-level cache's copy differs from a {@code datetime} column's value
     */
    private static void shiftLastModified(PersistentObject entity) {
        entity.setLastModified(new Date(entity.getLastModified().getTime() + 7));
    }

    private long recordVersion(String table, long id) throws PersistenceException {
        return documentDao.queryForLong("select ld_recordversion from %s where ld_id=%d".formatted(table, id));
    }

    private Timestamp lastModified(String table, long id) throws PersistenceException {
        return documentDao.queryForObject("select ld_lastmodified from %s where ld_id=%d".formatted(table, id),
                Timestamp.class);
    }
}
