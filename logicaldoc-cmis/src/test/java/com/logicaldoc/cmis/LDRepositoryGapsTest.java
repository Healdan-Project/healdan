// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.cmis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.util.plugin.PluginException;

/**
 * CMIS gaps the capability map listed: {@code createFolder} asked WRITE on the
 * parent where the GUI and the web services ask ADD, and {@code getObject}
 * resolves a shortcut to the document it points to, and a folder alias to its
 * folder, so deleting one deleted what it points to.
 */
public class LDRepositoryGapsTest extends AbstractCmisTestCase {

    /** The fixture's document 2 is a shortcut, in the root, to document 1 */
    private static final long TARGET = 1L;

    private static final long SHORTCUT = 2L;

    /** The author's own group */
    private static final long AUTHOR_GROUP = -4L;

    /** A folder of the fixture's, in the root */
    private static final long PARENT = 6L;

    private FolderDAO folderDao;

    private DocumentDAO documentDao;

    private LDRepository asAdmin;

    private LDRepository asAuthor;

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        folderDao = FolderDAO.get();
        documentDao = DocumentDAO.get();
        Folder workspace;
        try {
            workspace = folderDao.findDefaultWorkspace(Tenant.DEFAULT_ID);
            // The fixture's author has an old hash; give it the admin's password
            folderDao.jdbcUpdate(
                    "update ld_user set ld_password = (select ld_password from ld_user where ld_id = 1) where ld_id = 4");
        } catch (PersistenceException e) {
            throw new IOException(e);
        }
        asAdmin = new LDRepository(workspace,
                SessionManager.get().newSession("admin", "admin", (Client) null).getSid());
        asAuthor = new LDRepository(workspace,
                SessionManager.get().newSession("author", "admin", (Client) null).getSid());
    }

    @Test
    public void testCreateFolderNeedsAddNotWrite() throws PersistenceException {
        String parent = "fld." + PARENT;
        authorOnly(PARENT, Permission.READ, Permission.WRITE);
        assertThrows(CmisPermissionDeniedException.class,
                () -> asAuthor.createFolder(null, folderProperties("refused"), parent));
        assertTrue(folderDao.findByName(folderDao.findById(PARENT), "refused", Tenant.DEFAULT_ID, true).isEmpty());

        authorOnly(PARENT, Permission.READ, Permission.ADD);
        String id = asAuthor.createFolder(null, folderProperties("allowed"), parent);
        Folder created = folderDao.findById(Long.parseLong(id.substring(4)));
        assertEquals("allowed", created.getName());
        assertEquals(PARENT, created.getParentId());
    }

    @Test
    public void testDeleteObjectDeletesAShortcutNotItsTarget() throws PersistenceException {
        // A listing shows the shortcut under its own ID
        assertTrue(asAdmin.getChildren(new MockCallContext(null, null), "fld.5", null, false, false, 100, 0, null)
                .getObjects().stream()
                .anyMatch(child -> ("doc." + SHORTCUT).equals(child.getObject().getId())));

        asAdmin.deleteObject(null, "doc." + SHORTCUT);

        assertNull(documentDao.findById(SHORTCUT));
        assertNotNull(documentDao.findById(TARGET));
    }

    @Test
    public void testDeleteObjectOrCancelCheckOutDeletesAShortcutNotItsTarget() throws PersistenceException {
        asAdmin.deleteObjectOrCancelCheckOut(null, "doc." + SHORTCUT);

        assertNull(documentDao.findById(SHORTCUT));
        assertNotNull(documentDao.findById(TARGET));
    }

    @Test
    public void testDeletingAFolderAliasDeletesTheAliasNotItsFolder() throws PersistenceException {
        Folder outside = folder("outside", Folder.DEFAULTWORKSPACEID);
        Folder inner = folder("inner", outside.getId());

        Folder forTree = alias(Folder.DEFAULTWORKSPACEID, outside);
        asAdmin.deleteTree(null, "fld." + forTree.getId(), false);
        assertNull(folderDao.findById(forTree.getId()));
        assertNotNull(folderDao.findById(outside.getId()));
        assertNotNull(folderDao.findById(inner.getId()));

        // The folder isn't empty, so deleteObject refused the alias
        Folder forObject = alias(Folder.DEFAULTWORKSPACEID, outside);
        asAdmin.deleteObject(null, "fld." + forObject.getId());
        assertNull(folderDao.findById(forObject.getId()));
        assertNotNull(folderDao.findById(outside.getId()));
    }

    @Test
    public void testDeletingATreeDeletesItsAliasNotTheAliasesFolder() throws PersistenceException {
        // A tree the user may delete, holding an alias of a folder outside it
        Folder outside = folder("outside", Folder.DEFAULTWORKSPACEID);
        Folder inner = folder("inner", outside.getId());
        Folder tree = folder("tree", Folder.DEFAULTWORKSPACEID);
        Folder alias = alias(tree.getId(), outside);

        asAdmin.deleteTree(null, "fld." + tree.getId(), false);

        assertNull(folderDao.findById(tree.getId()));
        assertNull(folderDao.findById(alias.getId()));
        assertNotNull(folderDao.findById(outside.getId()));
        assertNotNull(folderDao.findById(inner.getId()));
    }

    private Folder folder(String name, long parentId) throws PersistenceException {
        Folder folder = new Folder(name);
        folder.setTenantId(Tenant.DEFAULT_ID);
        return folderDao.create(folderDao.findById(parentId), folder, true, adminTransaction());
    }

    private Folder alias(long parentId, Folder target) throws PersistenceException {
        return folderDao.createAlias(parentId, target.getId(), adminTransaction());
    }

    private static FolderHistory adminTransaction() throws PersistenceException {
        FolderHistory transaction = new FolderHistory();
        transaction.setUser(UserDAO.get().findByUsername("admin"));
        return transaction;
    }

    /**
     * Gives the folder an ACL of its own with only the author's own group,
     * with exactly these permissions
     */
    private void authorOnly(long folderId, Permission... permissions) throws PersistenceException {
        Folder folder = folderDao.findById(folderId, true);
        folder.setSecurityRef(null);
        folder.getAccessControlList().clear();
        FolderAccessControlEntry entry = new FolderAccessControlEntry(AUTHOR_GROUP);
        entry.grantPermissions(Set.of(permissions));
        folder.addAccessControlEntry(entry);
        folderDao.store(folder);
    }

    private static PropertiesImpl folderProperties(String name) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder"));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, name));
        return properties;
    }
}
