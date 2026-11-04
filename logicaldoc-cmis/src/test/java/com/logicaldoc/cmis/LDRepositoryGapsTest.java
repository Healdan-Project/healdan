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
import java.util.List;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.spi.Holder;
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
 * CMIS gaps the capability map listed: folder creation asked WRITE on the
 * parent where the GUI and the web services ask ADD, and {@code getObject}
 * resolves a shortcut to the document it points to, and a folder alias to its
 * folder, so deleting, moving or renaming one acted on what it points to.
 * Deleting a tree also went round an immutable document, which the document
 * DAO keeps from CMIS's session user, and deleted the folders round it.
 */
public class LDRepositoryGapsTest extends AbstractCmisTestCase {

    /** The fixture's document 2 is a shortcut, in the root, to document 1 */
    private static final long TARGET = 1L;

    private static final long SHORTCUT = 2L;

    /** A document of the fixture's no shortcut points to */
    private static final long PLAIN = 3L;

    /** Another */
    private static final long OTHER = 4L;

    /** The folder the fixture's documents are in */
    private static final long ROOT = 5L;

    /** The author's own group */
    private static final long AUTHOR_GROUP = -4L;

    /** The admins' group */
    private static final long ADMIN_GROUP = 1L;

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
            // The fixture's shortcut has a version CE can't count up from, for
            // the version a move or a rename makes; a real one has its target's
            folderDao.jdbcUpdate("update ld_document set ld_version = '1.0' where ld_id = %d".formatted(SHORTCUT));
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
    public void testCreateNeedsAddNotWriteForAFolder() throws PersistenceException {
        // AtomPub, the binding Healdan serves, creates folders through create()
        String parent = "fld." + PARENT;
        authorOnly(PARENT, Permission.READ, Permission.WRITE);
        assertThrows(CmisPermissionDeniedException.class,
                () -> asAuthor.create(null, folderProperties("refused"), parent, null, null));
        assertTrue(folderDao.findByName(folderDao.findById(PARENT), "refused", Tenant.DEFAULT_ID, true).isEmpty());

        authorOnly(PARENT, Permission.READ, Permission.ADD);
        asAuthor.create(null, folderProperties("allowed"), parent, null, null);
        assertEquals(1, folderDao.findByName(folderDao.findById(PARENT), "allowed", Tenant.DEFAULT_ID, true).size());
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

        // An alias holds nothing itself, so deleteObject deletes it though its
        // folder isn't empty
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

    @Test
    public void testDeletingAPlainFolderWithAReferenceLeavesTheFolderItPointsTo() throws PersistenceException {
        // A folder of type default can keep a reference to another, as CE's
        // copy of an alias does, and findChildren() follows it whatever the
        // folder's type
        Folder outside = folder("outside", Folder.DEFAULTWORKSPACEID);
        Folder inner = folder("inner", outside.getId());

        Folder forTree = folder("forTree", Folder.DEFAULTWORKSPACEID);
        Folder own = folder("own", forTree.getId());
        refer(forTree, outside);
        asAdmin.deleteTree(null, "fld." + forTree.getId(), false);
        assertNull(folderDao.findById(forTree.getId()));
        assertNull(folderDao.findById(own.getId()));
        assertNotNull(folderDao.findById(outside.getId()));
        assertNotNull(folderDao.findById(inner.getId()));

        // It holds nothing itself, so deleteObject deletes it
        Folder forObject = folder("forObject", Folder.DEFAULTWORKSPACEID);
        refer(forObject, outside);
        asAdmin.deleteObject(null, "fld." + forObject.getId());
        assertNull(folderDao.findById(forObject.getId()));
        assertNotNull(folderDao.findById(outside.getId()));
        assertNotNull(folderDao.findById(inner.getId()));
    }

    @Test
    public void testMoveObjectMovesAShortcutNotItsTarget() throws PersistenceException {
        Folder target = folder("target", Folder.DEFAULTWORKSPACEID);

        ObjectData moved = asAdmin.moveObject(context(), new Holder<>("doc." + SHORTCUT), "fld." + target.getId(),
                null);

        assertEquals("doc." + SHORTCUT, moved.getId());
        assertEquals(target.getId(), documentDao.findById(SHORTCUT).getFolder().getId());
        assertEquals(ROOT, documentDao.findById(TARGET).getFolder().getId());
    }

    @Test
    public void testMoveObjectMovesAFolderAliasNotItsFolder() throws PersistenceException {
        Folder outside = folder("outside", Folder.DEFAULTWORKSPACEID);
        Folder alias = alias(Folder.DEFAULTWORKSPACEID, outside);
        Folder target = folder("target", Folder.DEFAULTWORKSPACEID);

        ObjectData moved = asAdmin.moveObject(context(), new Holder<>("fld." + alias.getId()), "fld." + target.getId(),
                null);

        assertEquals("fld." + alias.getId(), moved.getId());
        assertEquals(target.getId(), folderDao.findById(alias.getId()).getParentId());
        assertEquals(Folder.DEFAULTWORKSPACEID, folderDao.findById(outside.getId()).getParentId());
    }

    @Test
    public void testMoveObjectChecksTheShortcutsOwnFolder() throws PersistenceException {
        // The author may take the document from its folder, but can't even
        // read the folder its shortcut is in
        Folder mine = folder("mine", Folder.DEFAULTWORKSPACEID);
        Folder theirs = folder("theirs", Folder.DEFAULTWORKSPACEID);
        Folder target = folder("target", Folder.DEFAULTWORKSPACEID);
        documentDao.jdbcUpdate("update ld_document set ld_folderid = %d where ld_id = %d".formatted(mine.getId(), TARGET));
        documentDao.jdbcUpdate(
                "update ld_document set ld_folderid = %d where ld_id = %d".formatted(theirs.getId(), SHORTCUT));
        authorOnly(mine.getId(), Permission.READ, Permission.DOWNLOAD, Permission.WRITE);
        only(theirs.getId(), ADMIN_GROUP, Permission.READ);
        authorOnly(target.getId(), Permission.READ, Permission.WRITE);

        assertThrows(CmisPermissionDeniedException.class, () -> asAuthor.moveObject(context(),
                new Holder<>("doc." + SHORTCUT), "fld." + target.getId(), null));

        assertEquals(theirs.getId(), documentDao.findById(SHORTCUT).getFolder().getId());
        assertEquals(mine.getId(), documentDao.findById(TARGET).getFolder().getId());
    }

    @Test
    public void testUpdatePropertiesRenamesAShortcutNotItsTarget() throws PersistenceException {
        asAdmin.updateProperties(context(), new Holder<>("doc." + SHORTCUT), name("renamed.doc"), null);

        assertEquals("renamed.doc", documentDao.findById(SHORTCUT).getFileName());
        assertEquals("test.doc", documentDao.findById(TARGET).getFileName());
    }

    @Test
    public void testUpdatePropertiesRenamesAFolderAliasNotItsFolder() throws PersistenceException {
        Folder outside = folder("outside", Folder.DEFAULTWORKSPACEID);
        Folder alias = alias(Folder.DEFAULTWORKSPACEID, outside);

        asAdmin.updateProperties(context(), new Holder<>("fld." + alias.getId()), name("renamed"), null);

        assertEquals("renamed", folderDao.findById(alias.getId()).getName());
        assertEquals("outside", folderDao.findById(outside.getId()).getName());
    }

    @Test
    public void testDeletingATreeDeletesTheAliasesOfItsFolders() throws PersistenceException {
        // A folder in the tree that an alias outside it points to. The folder
        // DAO's delete() marks the alias deleted as it stores the folder, so
        // its own SQL for that, which no database takes, never runs
        // (FolderAliasDeleteTest)
        Folder tree = folder("tree", Folder.DEFAULTWORKSPACEID);
        Folder pointed = folder("pointed", tree.getId());
        Folder alias = alias(Folder.DEFAULTWORKSPACEID, pointed);

        asAdmin.deleteTree(null, "fld." + tree.getId(), false);

        assertNull(folderDao.findById(pointed.getId()));
        assertNull(folderDao.findById(tree.getId()));
        assertNull(folderDao.findById(alias.getId()));
    }

    @Test
    public void testDeletingATreeWithAnImmutableDocumentIsRefused() throws PersistenceException {
        // The author may delete the tree, but the document DAO keeps an
        // immutable document from anyone whose groups it doesn't see admin in
        Folder tree = folder("tree", Folder.DEFAULTWORKSPACEID);
        Folder inner = folder("inner", tree.getId());
        documentDao.jdbcUpdate("update ld_document set ld_folderid = %d where ld_id = %d".formatted(tree.getId(), OTHER));
        documentDao.jdbcUpdate("update ld_document set ld_folderid = %d, ld_immutable = 1 where ld_id = %d"
                .formatted(inner.getId(), PLAIN));
        authorOnly(tree.getId(), Permission.READ, Permission.DELETE);
        authorOnly(inner.getId(), Permission.READ, Permission.DELETE);

        // CMIS's session user comes without its groups, so an admin's too
        for (LDRepository repository : List.of(asAuthor, asAdmin))
            for (boolean continueOnFailure : new boolean[] { false, true })
                assertThrows(CmisConstraintException.class,
                        () -> repository.deleteTree(null, "fld." + tree.getId(), continueOnFailure));

        // Nothing was deleted, as the folder DAO's deleteTree() refuses it
        assertNotNull(folderDao.findById(tree.getId()));
        assertNotNull(folderDao.findById(inner.getId()));
        assertNotNull(documentDao.findById(PLAIN));
        assertNotNull(documentDao.findById(OTHER));
    }

    @Test
    public void testDeletingAnImmutableDocumentIsRefused() throws PersistenceException {
        Folder folder = folder("folder", Folder.DEFAULTWORKSPACEID);
        documentDao.jdbcUpdate("update ld_document set ld_folderid = %d, ld_immutable = 1 where ld_id = %d"
                .formatted(folder.getId(), PLAIN));
        authorOnly(folder.getId(), Permission.READ, Permission.DELETE);

        for (LDRepository repository : List.of(asAuthor, asAdmin))
            assertThrows(CmisConstraintException.class, () -> repository.deleteObject(null, "doc." + PLAIN));
        assertNotNull(documentDao.findById(PLAIN));
    }

    private Folder folder(String name, long parentId) throws PersistenceException {
        Folder folder = new Folder(name);
        folder.setTenantId(Tenant.DEFAULT_ID);
        return folderDao.create(folderDao.findById(parentId), folder, true, adminTransaction());
    }

    private Folder alias(long parentId, Folder target) throws PersistenceException {
        return folderDao.createAlias(parentId, target.getId(), adminTransaction());
    }

    /** Gives a folder of type default a reference to another, as SOAP's update can */
    private void refer(Folder folder, Folder target) throws PersistenceException {
        Folder stored = folderDao.findById(folder.getId(), true);
        stored.setFoldRef(target.getId());
        folderDao.store(stored);
        assertEquals(Folder.TYPE_DEFAULT, folderDao.findById(folder.getId()).getType());
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
        only(folderId, AUTHOR_GROUP, permissions);
    }

    /**
     * Gives the folder an ACL of its own with only this group, with exactly
     * these permissions
     */
    private void only(long folderId, long groupId, Permission... permissions) throws PersistenceException {
        Folder folder = folderDao.findById(folderId, true);
        folder.setSecurityRef(null);
        folder.getAccessControlList().clear();
        FolderAccessControlEntry entry = new FolderAccessControlEntry(groupId);
        entry.grantPermissions(Set.of(permissions));
        folder.addAccessControlEntry(entry);
        folderDao.store(folder);
    }

    private static MockCallContext context() {
        return new MockCallContext(null, null);
    }

    private static PropertiesImpl name(String name) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, name));
        return properties;
    }

    private static PropertiesImpl folderProperties(String name) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder"));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, name));
        return properties;
    }
}
