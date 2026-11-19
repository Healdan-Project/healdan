// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.jackrabbit.webdav.DavException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.cmis.LDRepository;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.channels.ChannelHarness;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.webdav.resource.model.Resource;
import com.logicaldoc.webdav.session.DavSessionImpl;
import com.logicaldoc.webdav.session.WebdavSession;

/**
 * The server's rules for deleting and moving a folder, on every route (spec
 * 4.2, 4.3, 4.4): no folder is deleted while a document in it is checked out
 * or immutable, admins included; a folder moves only with Move on everything
 * in it and Add on where it goes; and a removed folder is restored by admins
 * only. The DAO is what the Classic Interface's calls end in; WebDAV, CMIS and
 * SOAP go through their own code to it. Each refusal is a fixed text that
 * names nothing inside.
 */
public class TrashRulesTest extends AbstractRulesTestCase {

	private static final String MOVE_TREE = "You need the move permission on everything in this folder to move it.";

	private static final String DELETE_TREE = "You need the delete permission on everything in this folder to delete it.";

	private final ChannelHarness harness = new ChannelHarness();

	private Session aliceSession;

	private Session adminSession;

	@Override
	protected List<String> databaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "sql/logicaldoc-webservice.sql", "data.sql");
	}

	@Before
	public void openSessions() throws Exception {
		aliceSession = SessionManager.get().newSession(alice.getUsername(), HealdanFixtures.PASSWORD, (Client) null);
		adminSession = SessionManager.get().newSession("admin", "admin", (Client) null);
	}

	@After
	public void closeSessions() {
		for (Session session : new Session[] { aliceSession, adminSession })
			if (session != null)
				SessionManager.get().kill(session.getSid());
	}

	@Test
	public void testCheckedOutInsideRefusesTheDelete() throws Exception {
		Folder cases = fx.folder(correspondence, "Cases");
		Folder inner = fx.folder(cases, "Inner");
		Document held = upload(inner, "held.txt", admin);
		awaitAsyncWork();
		// Bob may only read it, but holds it all the same
		DocumentManager.get().checkout(held.getId(), documentHistory(bob));
		awaitAsyncWork();
		// The fixture's deleted document is an item already
		long items = items();

		// Alice may delete everything in it, and the admin bypasses the permissions: the check-out stops both
		for (Session as : List.of(aliceSession, adminSession)) {
			User user = userOf(as);
			assertRefused(TrashRules.CHECKED_OUT, () -> folderDao.deleteTree(cases.getId(), folderHistory(user)));
			assertRefused(TrashRules.CHECKED_OUT, davRefusal(TrashRules.CHECKED_OUT),
					() -> webdavDelete(path(cases), user, as));
			assertCmisRefused(TrashRules.CHECKED_OUT, () -> harness.cmisFor(cases.getId(), as).deleteTree(null,
					"fld." + cases.getId(), false));
			assertRefused(TrashRules.CHECKED_OUT,
					() -> harness.soapFoldersWithSessions().delete(as.getSid(), cases.getId()));
		}
		// From further up, the admin's delete of the whole top-level folder too
		assertRefused(TrashRules.CHECKED_OUT,
				() -> folderDao.deleteTree(correspondence.getId(), folderHistory(admin)));
		assertEquals(items, items());

		// What the rule reads: every live document checked out in the live tree, by anyone
		TrashRules trashRules = TrashRules.get();
		assertEquals(List.of(held.getId()), trashRules.checkedOutIn(cases.getId()));
		assertEquals(List.of(held.getId()), trashRules.checkedOutIn(correspondence.getId()));
		assertEquals(List.of(), trashRules.checkedOutIn(documents.getId()));
		// A deleted document holds nothing, however its row was left
		jdbc.update("update ld_document set ld_status = ? where ld_id = ?", DocumentStatus.CHECKEDOUT.ordinal(),
				deletedDoc.getId());
		assertEquals(List.of(held.getId()), trashRules.checkedOutIn(correspondence.getId()));

		// Checked in, it goes
		DocumentManager.get().unlock(held.getId(), documentHistory(bob));
		awaitAsyncWork();
		assertEquals(List.of(), trashRules.checkedOutIn(cases.getId()));
		allowed(() -> folderDao.deleteTree(cases.getId(), folderHistory(alice)));
		assertNull(folderDao.findById(cases.getId()));
		assertEquals(items + 1, items());
	}

	@Test
	public void testItemsSheCantDeleteComeBeforeTheirCheckOuts() throws Exception {
		Folder cases = fx.folder(correspondence, "Cases");
		// Bob's group reads it; Alice's has no row, so she can neither see nor delete it
		Folder hidden = fx.folder(cases, "Hidden");
		setAcl(hidden, ace(readers(), Permission.READ));
		Document held = upload(hidden, "held.txt", admin);
		awaitAsyncWork();
		DocumentManager.get().checkout(held.getId(), documentHistory(bob));
		awaitAsyncWork();
		assertEquals(List.of(held.getId()), TrashRules.get().checkedOutIn(cases.getId()));

		// The permissions are checked first, so the refusal says nothing of the check-out she can't see (spec 4.2,
		// 6.1), on the routes that check nothing of the tree themselves
		assertRefused(DELETE_TREE, () -> folderDao.deleteTree(cases.getId(), folderHistory(alice)));
		assertRefused(DELETE_TREE, davRefusal(DELETE_TREE), () -> webdavDelete(path(cases), alice, aliceSession));
		assertCmisRefused(DELETE_TREE, () -> harness.cmisFor(cases.getId(), aliceSession).deleteTree(null,
				"fld." + cases.getId(), false));

		// The admin needs no permission, and meets the check-out
		assertRefused(TrashRules.CHECKED_OUT, () -> folderDao.deleteTree(cases.getId(), folderHistory(admin)));
	}

	@Test
	public void testImmutableInsideRefusesTheDelete() throws Exception {
		Folder sealedCase = fx.folder(correspondence, "Sealed Case");
		Folder inner = fx.folder(sealedCase, "Inner");
		Document fixed = upload(inner, "fixed.txt", admin);
		awaitAsyncWork();
		Document stored = reload(fixed);
		stored.setImmutable(true);
		documentDao.store(stored);
		awaitAsyncWork();
		assertTrue(TrashRules.get().holdsImmutable(sealedCase.getId()));
		assertFalse(TrashRules.get().holdsImmutable(documents.getId()));
		long items = items();

		// CE's deleteTree stopped here silently, having written the folder's history; now it's a refusal, admins' too
		assertRefused(TrashRules.IMMUTABLE, () -> folderDao.deleteTree(sealedCase.getId(), folderHistory(alice)));
		assertRefused(TrashRules.IMMUTABLE, () -> folderDao.deleteTree(sealedCase.getId(), folderHistory(admin)));
		assertRefused(TrashRules.IMMUTABLE, davRefusal(TrashRules.IMMUTABLE),
				() -> webdavDelete(path(sealedCase), alice, aliceSession));
		// CMIS counts the immutable documents itself, before the hook
		assertRefused("CMIS's own check", e -> {
			assertTrue(e.toString(), e instanceof CmisConstraintException);
			assertEquals("Folder %d holds immutable documents (1)".formatted(sealedCase.getId()), e.getMessage());
		}, () -> harness.cmisFor(sealedCase.getId(), aliceSession).deleteTree(null, "fld." + sealedCase.getId(),
				false));
		assertRefused(TrashRules.IMMUTABLE,
				() -> harness.soapFoldersWithSessions().delete(aliceSession.getSid(), sealedCase.getId()));

		// No DELETED history for the folder, the folder live, and no Trash item
		assertEquals(0L, (long) jdbc.queryForObject(
				"select count(*) from ld_folder_history where ld_folderid = ? and ld_event = ?", Long.class,
				sealedCase.getId(), FolderEvent.DELETED.toString()));
		assertEquals(0, folderDeleted(sealedCase));
		assertNotNull(folderDao.findById(sealedCase.getId()));
		assertEquals(items, items());
		assertFalse(TrashStore.get().itemHolding(new TrashMember('F', sealedCase.getId()), TrashState.DELETED)
				.isPresent());
	}

	@Test
	public void testMoveNeedsMoveOnEverythingInside() throws Exception {
		Folder moving = fx.folder(correspondence, "Moving");
		// Bob's group reads it; Alice's has no row, so she can't see it
		Folder hidden = fx.folder(moving, "Hidden");
		setAcl(hidden, ace(readers(), Permission.READ));
		awaitAsyncWork();

		assertMoveRefused(moving, documents);

		// Admins move it whatever it holds
		allowed(() -> daoMove(moving, documents, admin));
		assertEquals(documents.getId(), reload(moving).getParentId());
		allowed(() -> daoMove(moving, correspondence, admin));

		// Hidden opened to Alice, but a document inside whose own ACL lets her only read it
		setAcl(hidden, ace(lawyers, Permission.values()));
		Document kept = upload(hidden, "kept.txt", admin);
		awaitAsyncWork();
		setDocumentAcl(kept, Permission.READ);
		assertMoveRefused(moving, documents);

		// With Move on it, Alice's move goes through, with everything in it
		setDocumentAcl(kept, Permission.READ, Permission.MOVE);
		allowed(() -> daoMove(moving, documents, alice));
		assertEquals(documents.getId(), reload(moving).getParentId());
		assertEquals(moving.getId(), reload(hidden).getParentId());
		assertEquals(hidden.getId(), reload(kept).getFolder().getId());
	}

	@Test
	public void testMoveNeedsAddOnTheTarget() throws Exception {
		Folder moving = fx.folder(correspondence, "Moving");
		upload(moving, "inside.txt", admin);
		// Alice's group may do everything in Closed but add to it
		Folder closed = fx.folder(documents, "Closed");
		setAcl(closed, ace(lawyers, Permission.READ, Permission.WRITE, Permission.MOVE, Permission.DELETE,
				Permission.RENAME));
		awaitAsyncWork();

		// The DAO asks nothing, and CMIS only WRITE on the target
		assertRefused(MOVE_TREE, () -> daoMove(moving, closed, alice));
		assertRefused(MOVE_TREE, () -> cmisMove(moving, closed));
		// WebDAV asks ADD itself
		assertRefused("WebDAV's ADD check", davForbidden(), () -> webdavMove(moving, closed));

		allowed(() -> daoMove(moving, closed, admin));
		assertEquals(closed.getId(), reload(moving).getParentId());
		allowed(() -> daoMove(moving, correspondence, admin));

		setAcl(closed, ace(lawyers, Permission.values()));
		allowed(() -> daoMove(moving, closed, alice));
		assertEquals(closed.getId(), reload(moving).getParentId());
	}

	@Test
	public void testWebdavMoveThatRenamesIsChecked() throws Exception {
		Folder moving = fx.folder(correspondence, "Moving");
		// Bob's group reads it; Alice's has no row, so she can't see it
		Folder hidden = fx.folder(moving, "Hidden");
		setAcl(hidden, ace(readers(), Permission.READ));
		awaitAsyncWork();

		// A WebDAV MOVE to a new name in another folder is Alice's move, checked as any move, and a refused one
		// renames nothing either
		assertRefused(MOVE_TREE, () -> webdavMove(moving, documents, "Moved"));
		assertEquals(correspondence.getId(), reload(moving).getParentId());
		assertEquals("Moving", reload(moving).getName());

		setAcl(hidden, ace(lawyers, Permission.values()));
		allowed(() -> webdavMove(moving, documents, "Moved"));
		assertEquals(documents.getId(), reload(moving).getParentId());
		assertEquals("Moved", reload(moving).getName());
		assertEquals(moving.getId(), reload(hidden).getParentId());
		assertEquals(1L, history(moving, FolderEvent.MOVED));

		// A MOVE to a new name in the same folder is a rename, as it was
		allowed(() -> webdavMove(moving, documents, "Renamed"));
		assertEquals(documents.getId(), reload(moving).getParentId());
		assertEquals("Renamed", reload(moving).getName());
		assertEquals(1L, history(moving, FolderEvent.MOVED));
	}

	@Test
	public void testRemovedFolderRestoresOnlyForAnAdmin() throws Exception {
		Folder removed = fx.folder(correspondence, "Removed");
		Document inside = upload(removed, "inside.txt", admin);
		awaitAsyncWork();
		allowed(() -> folderDao.deleteTree(removed.getId(), folderHistory(alice)));
		// As CE's removal from the trash writes it: the folder at 2, its contents left at 1
		jdbc.update("update ld_folder set ld_deleted = 2 where ld_id = ?", removed.getId());
		CacheEvictor.get().evictFolders(List.of(removed.getId()));

		// Alice deleted it, and may delete it, but it's no longer in the Trash
		assertRefused(RESTORE_NOT_IN_TRASH,
				() -> folderDao.restore(removed.getId(), correspondence.getId(), folderHistory(alice)));

		allowed(() -> folderDao.restore(removed.getId(), correspondence.getId(), folderHistory(admin)));
		assertEquals(0, folderDeleted(removed));
		assertNotNull(folderDao.findById(removed.getId()));
		assertNotNull(documentDao.findById(inside.getId()));
	}

	/**
	 * A move of the folder into the target is refused to Alice with
	 * {@code MOVE_TREE} on each route that reaches the folder listener: the
	 * DAO, WebDAV and CMIS. The refusal is that text exactly, so it names
	 * nothing inside.
	 */
	private void assertMoveRefused(Folder folder, Folder target) throws Exception {
		assertRefused(MOVE_TREE, () -> daoMove(folder, target, alice));
		assertRefused(MOVE_TREE, () -> webdavMove(folder, target));
		assertRefused(MOVE_TREE, () -> cmisMove(folder, target));
	}

	private void daoMove(Folder folder, Folder target, User as) throws Exception {
		folderDao.move(folderDao.findById(folder.getId()), folderDao.findById(target.getId()), folderHistory(as));
	}

	/**
	 * Moves as a WebDAV MOVE that keeps the name does
	 */
	private void webdavMove(Folder folder, Folder target) throws Exception {
		WebdavSession session = davSession(alice, aliceSession);
		Resource source = harness.webdav().getResource(path(folder), session);
		Resource destination = harness.webdav().getResource(path(target), session);
		assertNotNull(source);
		assertNotNull(destination);
		harness.webdav().move(source, destination, null, session);
	}

	/**
	 * Moves as a WebDAV MOVE to a new name in the target does: CE's
	 * {@code DavResourceImpl.move} names the source as the request does, and
	 * passes the name too
	 */
	private void webdavMove(Folder folder, Folder target, String name) throws Exception {
		WebdavSession session = davSession(alice, aliceSession);
		Resource source = harness.webdav().getResource(path(folder), session);
		Resource destination = harness.webdav().getResource(path(target), session);
		assertNotNull(source);
		assertNotNull(destination);
		source.setName(name);
		harness.webdav().move(source, destination, name, session);
	}

	private void cmisMove(Folder folder, Folder target) throws Exception {
		harness.cmisFor(folder.getId(), aliceSession).moveObject(null, new Holder<>("fld." + folder.getId()),
				"fld." + target.getId(), null);
	}

	/**
	 * Deletes as a WebDAV DELETE does: the servlet's {@code removeMember}
	 * checks the resource's DELETE, then the resource service deletes it
	 */
	private void webdavDelete(String path, User as, Session session) throws Exception {
		WebdavSession davSession = davSession(as, session);
		Resource resource = harness.webdav().getResource(path, davSession);
		assertNotNull(path, resource);
		if (!resource.isDeleteEnabled())
			throw new DavException(403, "Delete not allowed.");
		harness.webdav().deleteResource(resource, davSession);
	}

	private WebdavSession davSession(User as, Session session) {
		DavSessionImpl davSession = (DavSessionImpl) harness.davSession(as);
		davSession.putObject("sid", session.getSid());
		return davSession;
	}

	/**
	 * CMIS's deleteTree reports any failure as a storage error and logs why
	 */
	private void assertCmisRefused(String message, Route route) throws Exception {
		try (LogCapture log = LogCapture.of(LDRepository.class)) {
			assertRefused(message, e -> {
				assertTrue(e.toString(), e instanceof CmisStorageException);
				assertTrue(log.lines().toString(), log.lines().stream().anyMatch(line -> line.contains(message)));
			}, route);
		}
	}

	/**
	 * WebDAV's delete turns any failure but its own refusals into a 500 with
	 * the message
	 */
	private static Refusal davRefusal(String message) {
		return e -> {
			assertTrue(e.toString(), e instanceof DavException);
			assertEquals(500, ((DavException) e).getErrorCode());
			assertEquals(message, e.getMessage());
		};
	}

	private static Refusal davForbidden() {
		return e -> {
			assertTrue(e.toString(), e instanceof DavException);
			assertEquals(403, ((DavException) e).getErrorCode());
		};
	}

	private User userOf(Session session) {
		return session == adminSession ? admin : alice;
	}

	private Group readers() throws Exception {
		return GroupDAO.get().findByName("readers", Tenant.DEFAULT_ID);
	}

	private String path(Folder folder) throws Exception {
		return folderDao.computePathExtended(folder.getId());
	}

	private long history(Folder folder, FolderEvent event) {
		return jdbc.queryForObject("select count(*) from ld_folder_history where ld_folderid = ? and ld_event = ?",
				Long.class, folder.getId(), event.toString());
	}

	private long items() {
		return jdbc.queryForObject("select count(*) from hd_trash", Long.class);
	}

	private int folderDeleted(Folder folder) {
		return jdbc.queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class, folder.getId());
	}

	private void setAcl(Folder folder, FolderAccessControlEntry... entries) throws Exception {
		Folder current = reload(folder);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		for (FolderAccessControlEntry entry : entries)
			current.addAccessControlEntry(entry);
		FolderHistory history = folderHistory(admin);
		history.setEvent(FolderEvent.PERMISSION);
		folderDao.store(current, history);
	}

	private static FolderAccessControlEntry ace(Group group, Permission... permissions) {
		FolderAccessControlEntry ace = new FolderAccessControlEntry(group.getId());
		ace.grantPermissions(Set.of(permissions));
		return ace;
	}

	/**
	 * Gives the document an ACL of its own, with these permissions for Alice's
	 * group
	 */
	private void setDocumentAcl(Document document, Permission... permissions) throws Exception {
		Document current = reload(document);
		current.getAccessControlList().clear();
		DocumentAccessControlEntry ace = new DocumentAccessControlEntry(lawyers.getId());
		ace.grantPermissions(Set.of(permissions));
		current.addAccessControlEntry(ace);
		documentDao.store(current);
		awaitAsyncWork();
	}
}
