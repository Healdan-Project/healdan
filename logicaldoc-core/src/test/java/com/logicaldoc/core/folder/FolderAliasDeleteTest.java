// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.core.folder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.sql.SQLException;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.util.plugin.PluginException;

/**
 * Deleting a folder deletes the aliases that point to it. {@code store}, which
 * {@code HibernateFolderDAO.delete} calls first, marks them deleted with the
 * folder ({@code updateAliases}), so the aliases {@code delete} looks for
 * afterwards are already gone. That keeps its own SQL for them from running,
 * which both HSQLDB and MariaDB would reject ({@code update set ld_deleted = …
 * from ld_folder where ld_foldref in (…)}, given the aliases' own IDs). CMIS's
 * {@code deleteTree} deletes a tree one folder at a time this way, so if that
 * order changed it would delete such a folder's contents and then fail on the
 * folder. This fails first.
 */
public class FolderAliasDeleteTest extends AbstractCoreTestCase {

    private FolderDAO folderDao;

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        folderDao = FolderDAO.get();
    }

    @Test
    public void testDeletingAFolderDeletesItsAliases() throws PersistenceException {
        Folder pointed = folder("pointed");
        Folder alias = folderDao.createAlias(Folder.DEFAULTWORKSPACEID, pointed.getId(), transaction());
        Folder other = folder("other");
        Folder otherAlias = folderDao.createAlias(Folder.DEFAULTWORKSPACEID, other.getId(), transaction());

        folderDao.delete(pointed.getId(), 3, transaction());

        assertNull(folderDao.findById(pointed.getId()));
        assertNull(folderDao.findById(alias.getId()));
        assertEquals(3, folderDao.queryForInt("select ld_deleted from ld_folder where ld_id = " + alias.getId()));

        // Only the aliases of the folder deleted
        assertNotNull(folderDao.findById(other.getId()));
        assertNotNull(folderDao.findById(otherAlias.getId()));
    }

    private Folder folder(String name) throws PersistenceException {
        Folder folder = new Folder(name);
        folder.setTenantId(Tenant.DEFAULT_ID);
        return folderDao.create(folderDao.findById(Folder.DEFAULTWORKSPACEID), folder, true, transaction());
    }

    private static FolderHistory transaction() throws PersistenceException {
        FolderHistory transaction = new FolderHistory();
        transaction.setUser(UserDAO.get().findByUsername("admin"));
        return transaction;
    }
}
