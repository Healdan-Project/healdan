package com.logicaldoc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.util.plugin.PluginException;

/**
 * Proves that each {@link HealdanHooks} call runs before CE's JDBC and index
 * side effects, so a hook that throws leaves nothing behind.
 */
public class HealdanHooksTest extends AbstractCoreTestCase {

    private static final String VETO = "veto";

    private DocumentDAO documentDao;

    private FolderDAO folderDao;

    private User admin;

    /**
     * The calls the registered hook received, as "method:arguments"
     */
    private final List<String> calls = new ArrayList<>();

    private final HealdanHooks vetoing = new HealdanHooks() {

        @Override
        public void beforeDocumentMove(Document document, Folder target, DocumentHistory transaction)
                throws PersistenceException {
            calls.add("beforeDocumentMove:%d,%d".formatted(document.getId(), target.getId()));
            throw new PersistenceException(VETO);
        }

        @Override
        public void beforeDocumentRename(long docId, String newName, DocumentHistory transaction)
                throws PersistenceException {
            calls.add("beforeDocumentRename:%d,%s".formatted(docId, newName));
            throw new PersistenceException(VETO);
        }

        @Override
        public void beforeDocumentUpdate(Document document, Document docVO, DocumentHistory transaction)
                throws PersistenceException {
            calls.add("beforeDocumentUpdate:%d,%s".formatted(document.getId(), docVO.getLanguage()));
            throw new PersistenceException(VETO);
        }

        @Override
        public void beforeDocumentRestore(long docId, long folderId, DocumentHistory transaction)
                throws PersistenceException {
            calls.add("beforeDocumentRestore:%d,%d".formatted(docId, folderId));
            throw new PersistenceException(VETO);
        }

        @Override
        public void beforeFolderDeleteTree(long folderId, FolderHistory transaction) throws PersistenceException {
            calls.add("beforeFolderDeleteTree:%d".formatted(folderId));
            throw new PersistenceException(VETO);
        }

        @Override
        public void beforeFolderRestore(long folderId, long parentId, FolderHistory transaction)
                throws PersistenceException {
            calls.add("beforeFolderRestore:%d,%d".formatted(folderId, parentId));
            throw new PersistenceException(VETO);
        }

        @Override
        public void beforeFolderMerge(Folder source, Folder target, FolderHistory transaction)
                throws PersistenceException {
            calls.add("beforeFolderMerge:%d,%d".formatted(source.getId(), target.getId()));
            throw new PersistenceException(VETO);
        }

        @Override
        public void beforeApplySecurityToTree(long rootId, FolderHistory transaction) throws PersistenceException {
            calls.add("beforeApplySecurityToTree:%d".formatted(rootId));
            throw new PersistenceException(VETO);
        }
    };

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        documentDao = DocumentDAO.get();
        folderDao = FolderDAO.get();
        admin = UserDAO.get().findById(User.USERID_ADMIN, true);
    }

    @After
    @Override
    public void tearDown() throws IOException {
        HealdanHooks.register(null);
        SearchEngine.get().unlock();
        SearchEngine.get().close();
        super.tearDown();
    }

    @Test
    public void testNoOpUntilRegistered() throws PersistenceException {
        HealdanHooks none = HealdanHooks.get();
        assertNotNull(none);
        none.beforeFolderDeleteTree(1200L, null);

        HealdanHooks.register(vetoing);
        assertSame(vetoing, HealdanHooks.get());

        HealdanHooks.register(null);
        assertSame(none, HealdanHooks.get());
    }

    @Test
    public void testMoveToFolder() throws Exception {
        Document doc = documentDao.findById(1L, true);
        assertEquals(6L, doc.getFolder().getId());
        assertEquals(1, column("ld_document", "ld_indexed", 1L));
        SearchEngine.get().unlock();
        SearchEngine.get().addHit(doc, "Healdan move content");
        assertNotNull(SearchEngine.get().getHit(1L));

        HealdanHooks.register(vetoing);
        Folder target = folderDao.findById(1200L);
        try {
            DocumentManager.get().moveToFolder(doc, target, documentTransaction());
            fail("The hook should have vetoed the move");
        } catch (PersistenceException e) {
            assertEquals(VETO, e.getMessage());
        }

        assertEquals(List.of("beforeDocumentMove:1,1200"), calls);
        assertEquals(6L, column("ld_document", "ld_folderid", 1L));
        assertEquals(1, column("ld_document", "ld_indexed", 1L));
        assertNotNull(SearchEngine.get().getHit(1L));
    }

    @Test
    public void testRename() throws Exception {
        assertEquals("pippo.pdf", documentDao.findById(1L).getFileName());
        String versionCount = "select count(*) from ld_version where ld_documentid = 1";
        long versionsBefore = documentDao.queryForLong(versionCount);

        HealdanHooks.register(vetoing);
        try {
            DocumentManager.get().rename(1L, "renamed.pdf", documentTransaction());
            fail("The hook should have vetoed the rename");
        } catch (PersistenceException e) {
            assertEquals(VETO, e.getMessage());
        }

        assertEquals(List.of("beforeDocumentRename:1,renamed.pdf"), calls);
        // CE saves the renamed version in the background
        awaitVersionSaves();
        assertEquals(versionsBefore, documentDao.queryForLong(versionCount));
        assertEquals("pippo.pdf", documentDao.findById(1L).getFileName());
    }

    @Test
    public void testUpdateWithLanguageChange() throws Exception {
        Document doc = documentDao.findById(1L, true);
        assertEquals("en", doc.getLanguage());
        SearchEngine.get().unlock();
        SearchEngine.get().addHit(doc, "Healdan update content");
        assertNotNull(SearchEngine.get().getHit(1L));

        HealdanHooks.register(vetoing);
        Document docVO = new Document(doc);
        docVO.setLanguage("it");
        try {
            DocumentManager.get().update(doc, docVO, documentTransaction());
            fail("The hook should have vetoed the update");
        } catch (PersistenceException e) {
            assertEquals(VETO, e.getMessage());
        }

        assertEquals(List.of("beforeDocumentUpdate:1,it"), calls);
        assertEquals("en", documentDao.queryForString("select ld_language from ld_document where ld_id = 1"));
        assertNotNull(SearchEngine.get().getHit(1L));
    }

    @Test
    public void testDocumentRestore() throws PersistenceException {
        assertEquals(1, column("ld_document", "ld_deleted", 4L));
        long folderBefore = column("ld_document", "ld_folderid", 4L);

        HealdanHooks.register(vetoing);
        try {
            documentDao.restore(4L, 5L, documentTransaction());
            fail("The hook should have vetoed the restore");
        } catch (PersistenceException e) {
            assertEquals(VETO, e.getMessage());
        }

        assertEquals(List.of("beforeDocumentRestore:4,5"), calls);
        assertEquals(1, column("ld_document", "ld_deleted", 4L));
        assertEquals(folderBefore, column("ld_document", "ld_folderid", 4L));
    }

    @Test
    public void testDeleteTree() throws PersistenceException {
        String historyCount = "select count(*) from ld_folder_history where ld_folderid = 1200";
        long historyBefore = folderDao.queryForLong(historyCount);

        HealdanHooks.register(vetoing);

        // Every overload goes through the hook
        try {
            folderDao.deleteTree(1200L, folderTransaction());
            fail("The hook should have vetoed deleteTree(long, transaction)");
        } catch (PersistenceException e) {
            assertEquals(VETO, e.getMessage());
        }
        try {
            folderDao.deleteTree(1200L, PersistentObject.DELETED_CODE_DEFAULT, folderTransaction());
            fail("The hook should have vetoed deleteTree(long, int, transaction)");
        } catch (PersistenceException e) {
            assertEquals(VETO, e.getMessage());
        }
        try {
            folderDao.deleteTree(folderDao.findById(1200L), PersistentObject.DELETED_CODE_DEFAULT,
                    folderTransaction());
            fail("The hook should have vetoed deleteTree(Folder, int, transaction)");
        } catch (PersistenceException e) {
            assertEquals(VETO, e.getMessage());
        }

        assertEquals(List.of("beforeFolderDeleteTree:1200", "beforeFolderDeleteTree:1200",
                "beforeFolderDeleteTree:1200"), calls);
        for (long id : new long[] { 1200L, 1201L, 1202L })
            assertEquals("folder " + id, 0, column("ld_folder", "ld_deleted", id));
        assertEquals(historyBefore, folderDao.queryForLong(historyCount));
    }

    @Test
    public void testFolderRestore() throws PersistenceException {
        assertEquals(1, column("ld_folder", "ld_deleted", 1204L));

        HealdanHooks.register(vetoing);
        try {
            folderDao.restore(1204L, 5L, folderTransaction());
            fail("The hook should have vetoed the restore");
        } catch (PersistenceException e) {
            assertEquals(VETO, e.getMessage());
        }

        assertEquals(List.of("beforeFolderRestore:1204,5"), calls);
        assertEquals(1, column("ld_folder", "ld_deleted", 1204L));
        assertEquals(1201L, column("ld_folder", "ld_parentid", 1204L));
    }

    @Test
    public void testApplySecurityToTree() throws PersistenceException {
        assertOwnAcl(1201L);
        assertOwnAcl(1202L);

        HealdanHooks.register(vetoing);
        try {
            folderDao.applySecurityToTree(1200L, folderTransaction());
            fail("The hook should have vetoed applySecurityToTree");
        } catch (PersistenceException e) {
            assertEquals(VETO, e.getMessage());
        }

        assertEquals(List.of("beforeApplySecurityToTree:1200"), calls);
        assertOwnAcl(1201L);
        assertOwnAcl(1202L);
    }

    @Test
    public void testMerge() throws Exception {
        String documentsIn6 = "select count(*) from ld_document where ld_deleted = 0 and ld_folderid = 6";
        String versionCount = "select count(*) from ld_version";
        long documentsBefore = folderDao.queryForLong(documentsIn6);
        long versionsBefore = folderDao.queryForLong(versionCount);
        assertTrue(documentsBefore > 0);
        Document doc = documentDao.findById(1L, true);
        assertEquals(6L, doc.getFolder().getId());
        SearchEngine.get().unlock();
        SearchEngine.get().addHit(doc, "Healdan merge content");

        HealdanHooks.register(vetoing);
        try {
            folderDao.merge(folderDao.findById(6L), folderDao.findById(1200L), folderTransaction());
            fail("The hook should have vetoed the merge");
        } catch (PersistenceException e) {
            assertEquals(VETO, e.getMessage());
        }

        assertEquals(List.of("beforeFolderMerge:6,1200"), calls);
        // Each moved document would save a version in the background
        awaitVersionSaves();
        assertEquals(documentsBefore, folderDao.queryForLong(documentsIn6));
        assertEquals(versionsBefore, folderDao.queryForLong(versionCount));
        assertEquals(0, column("ld_folder", "ld_deleted", 6L));
        assertNotNull(SearchEngine.get().getHit(1L));
    }

    @Test
    public void testAfterFolderRestoreRunsAfterRows() throws PersistenceException {
        assertEquals(1, column("ld_folder", "ld_deleted", 1204L));

        HealdanHooks.register(new HealdanHooks() {
            @Override
            public void beforeFolderRestore(long folderId, long parentId, FolderHistory transaction)
                    throws PersistenceException {
                calls.add("beforeFolderRestore:%d,%d deleted=%d".formatted(folderId, parentId,
                        column("ld_folder", "ld_deleted", folderId)));
            }

            @Override
            public void afterFolderRestore(long folderId, long parentId, FolderHistory transaction)
                    throws PersistenceException {
                calls.add("afterFolderRestore:%d,%d deleted=%d parent=%d".formatted(folderId, parentId,
                        column("ld_folder", "ld_deleted", folderId),
                        column("ld_folder", "ld_parentid", folderId)));
            }
        });

        folderDao.restore(1204L, 5L, folderTransaction());

        assertEquals(List.of("beforeFolderRestore:1204,5 deleted=1", "afterFolderRestore:1204,5 deleted=0 parent=5"),
                calls);
        assertEquals(0, column("ld_folder", "ld_deleted", 1204L));
    }

    private static void awaitVersionSaves() throws Exception {
        ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
        long deadline = System.currentTimeMillis() + 20000L;
        while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
            if (System.currentTimeMillis() > deadline)
                fail("Background version saves didn't finish");
            Thread.sleep(50L);
        }
    }

    private void assertOwnAcl(long folderId) throws PersistenceException {
        assertTrue("folder %d has its own ACL".formatted(folderId),
                folderDao.queryForLong("select count(*) from ld_folder_acl where ld_folderid = %d".formatted(folderId)) > 0);
        assertEquals(0L, folderDao
                .queryForLong("select count(*) from ld_folder where ld_securityref is not null and ld_id = %d"
                        .formatted(folderId)));
    }

    private long column(String table, String column, long id) throws PersistenceException {
        return folderDao.queryForLong("select %s from %s where ld_id = %d".formatted(column, table, id));
    }

    private DocumentHistory documentTransaction() {
        DocumentHistory transaction = new DocumentHistory();
        transaction.setSessionId("123");
        transaction.setUser(admin);
        return transaction;
    }

    private FolderHistory folderTransaction() {
        FolderHistory transaction = new FolderHistory();
        transaction.setSessionId("123");
        transaction.setUser(admin);
        return transaction;
    }
}
