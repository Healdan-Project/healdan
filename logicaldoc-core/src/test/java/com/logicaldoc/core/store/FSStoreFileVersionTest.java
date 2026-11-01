package com.logicaldoc.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.util.plugin.PluginException;

/**
 * A file version's resources are the file named after it and the files named
 * after it followed by {@code -} and a suffix. CE's listing kept every name
 * that started with the file version, so 1.2's took in 1.20 to 1.29 and their
 * thumbnails, and replacing 1.2's file or deleting version 1.2 deleted them.
 */
public class FSStoreFileVersionTest extends AbstractCoreTestCase {

    /**
     * The fixture's document 3: unlocked, with file versions 1.0, 1.2 and 1.3
     */
    private static final long DOC_ID = 3L;

    /**
     * Document 3's version 1.2, the only one on file version 1.2
     */
    private static final long VERSION_ID = 12L;

    // Instance under test
    private FSStore testSubject;

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();

        testSubject = new FSStore();

        for (String name : List.of("1.2", "1.2-thumb.png", "1.20", "1.20-thumb.png", "1.21"))
            put(name);
    }

    @Test
    public void testListResourcesMatchesTheFileVersionExactly() {
        assertEquals(List.of("1.2", "1.2-thumb.png"), names("1.2"));
        assertEquals(List.of("1.20", "1.20-thumb.png"), names("1.20"));
        assertEquals(List.of("1.21"), names("1.21"));
        assertEquals(List.of("1.2", "1.2-thumb.png", "1.20", "1.20-thumb.png", "1.21", "1.3"), names(null));
    }

    @Test
    public void testReplaceFileKeepsTheOtherFileVersions()
            throws IOException, PersistenceException, InterruptedException, ExecutionException {
        File newFile = new File(tempDir, "replacement");
        Files.writeString(newFile.toPath(), "replacement", StandardCharsets.UTF_8);

        DocumentManager.get().replaceFile(DOC_ID, "1.2", newFile, transaction()).get();

        assertEquals("replacement", read("1.2"));
        assertFalse(exists("1.2-thumb.png"));
        assertKept("1.20", "1.20-thumb.png", "1.21");
    }

    @Test
    public void testDeleteVersionKeepsTheOtherFileVersions() throws PersistenceException {
        DocumentManager.get().deleteVersion(VERSION_ID, transaction());

        assertFalse(exists("1.2"));
        assertFalse(exists("1.2-thumb.png"));
        assertKept("1.20", "1.20-thumb.png", "1.21");
    }

    private DocumentHistory transaction() throws PersistenceException {
        DocumentHistory transaction = new DocumentHistory();
        transaction.setSessionId("1234");
        transaction.setUser(UserDAO.get().findByUsername("admin"));
        return transaction;
    }

    private List<String> names(String fileVersion) {
        return testSubject.listResources(DOC_ID, fileVersion).stream().map(StoreResource::name).sorted().toList();
    }

    private void put(String name) throws IOException {
        File file = new File(testSubject.getContainer(DOC_ID), name);
        FileUtils.forceMkdirParent(file);
        Files.writeString(file.toPath(), name, StandardCharsets.UTF_8);
    }

    private boolean exists(String name) {
        return new File(testSubject.getContainer(DOC_ID), name).exists();
    }

    private String read(String name) throws IOException {
        return Files.readString(new File(testSubject.getContainer(DOC_ID), name).toPath(), StandardCharsets.UTF_8);
    }

    private void assertKept(String... names) throws AssertionError {
        for (String name : names) {
            assertTrue("Deleted: " + name, exists(name));
            try {
                assertEquals(name, read(name));
            } catch (IOException e) {
                throw new AssertionError(name, e);
            }
        }
    }
}
