package com.logicaldoc.healdan.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException;
import org.apache.jackrabbit.webdav.DavException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.cmis.LDRepository;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.channels.ChannelHarness;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.webdav.resource.model.Resource;
import com.logicaldoc.webdav.session.DavSessionImpl;
import com.logicaldoc.webdav.session.WebdavSession;

/**
 * The permissions R70, R73 and R74 ask on WebDAV and CMIS, which delete and
 * copy through their own code rather than the GUI's or SOAP's: a refused call
 * writes nothing (D3). Alice may delete Old and Kept, but not Sealed inside
 * Old, nor kept.txt, whose own ACL lets her only read it; she can't download
 * sealed.txt. CE checks only the folder given: the top folder for a tree, the
 * document's folder for a document, the target folder for a copy.
 * <p>
 * A tree is checked in Healdan's folder-delete hook, which every route goes
 * through, so its refusal reaches WebDAV as a 500 and CMIS as a storage error
 * whose reason is logged, as the filing rules' do. A document is checked in
 * each route, which refuses as CE does: a 403, a permission-denied error.
 */
public class ChannelTreePermissionsTest extends AbstractRulesTestCase {

	private static final String DELETE_TREE = "You need the delete permission on everything in this folder to delete it.";

	private final ChannelHarness harness = new ChannelHarness();

	private Session aliceSession;

	/** Alice has every permission on it */
	private Folder old;

	/** In Old; Alice may only read it */
	private Folder sealed;

	/** In Sealed */
	private Document inSealed;

	/** Alice has every permission on it */
	private Folder kept;

	/** In Kept, with an ACL of its own that lets Alice only read it */
	private Document keptDoc;

	@Override
	protected List<String> getDatabaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "sql/logicaldoc-webservice.sql", "data.sql");
	}

	@Before
	public void createTrees() throws Exception {
		old = fx.folder(correspondence, "Old");
		sealed = fx.folder(old, "Sealed");
		setAcl(sealed, ace(Permission.READ));
		inSealed = upload(sealed, "sealed.txt", admin);

		kept = fx.folder(correspondence, "Kept");
		keptDoc = upload(kept, "kept.txt", admin);
		awaitAsyncWork();
		setKeptDocAcl(Permission.READ);

		aliceSession = SessionManager.get().newSession(alice.getUsername(), HealdanFixtures.PASSWORD, (Client) null);
	}

	@After
	public void closeSession() {
		if (aliceSession != null)
			SessionManager.get().kill(aliceSession.getSid());
	}

	@Test
	public void testCeChecksTheFolderGiven() throws Exception {
		assertTrue(folderDao.isPermissionAllowed(Permission.DELETE, old.getId(), alice.getId()));
		assertTrue(folderDao.isPermissionAllowed(Permission.DELETE, kept.getId(), alice.getId()));
		assertFalse(folderDao.isPermissionAllowed(Permission.DELETE, sealed.getId(), alice.getId()));
		assertFalse(documentDao.isPermissionAllowed(Permission.DELETE, keptDoc.getId(), alice.getId()));
		assertFalse(documentDao.isPermissionAllowed(Permission.DOWNLOAD, inSealed.getId(), alice.getId()));

		assertRefused("CE's DELETE check", davRefusal(), () -> webdavDelete(path(sealed), alice));
		assertRefused("CE's DELETE check", e -> assertTrue(e.toString(), e instanceof CmisPermissionDeniedException),
				() -> cmisDeleteTree(sealed));
	}

	@Test
	public void testWebdavDeleteNeedsDeleteOnSubfolders() throws Exception {
		assertRefused(DELETE_TREE, davTreeRefusal(), () -> webdavDelete(path(old), alice));

		grantDeleteOnSealed();
		allowed(() -> webdavDelete(path(old), alice));
		assertNull(folderDao.findById(old.getId()));
		assertNull(documentDao.findById(inSealed.getId()));
	}

	@Test
	public void testWebdavDeleteNeedsDeleteOnDocuments() throws Exception {
		assertRefused(DELETE_TREE, davTreeRefusal(), () -> webdavDelete(path(kept), alice));

		setKeptDocAcl(Permission.READ, Permission.DELETE);
		allowed(() -> webdavDelete(path(kept), alice));
		assertNull(documentDao.findById(keptDoc.getId()));
	}

	@Test
	public void testWebdavDocumentDeleteNeedsItsDelete() throws Exception {
		assertRefused("R70", davForbidden("No rights to delete the document."),
				() -> webdavDelete(path(kept) + "/kept.txt", alice));

		setKeptDocAcl(Permission.READ, Permission.DELETE);
		allowed(() -> webdavDelete(path(kept) + "/kept.txt", alice));
		assertNull(documentDao.findById(keptDoc.getId()));
	}

	@Test
	public void testCmisDeleteTreeNeedsDeleteOnSubfolders() throws Exception {
		assertCmisTreeRefused(() -> cmisDeleteTree(old));

		grantDeleteOnSealed();
		allowed(() -> cmisDeleteTree(old));
		assertNull(folderDao.findById(old.getId()));
		assertNull(documentDao.findById(inSealed.getId()));
	}

	@Test
	public void testCmisDeleteTreeNeedsDeleteOnDocuments() throws Exception {
		assertCmisTreeRefused(() -> cmisDeleteTree(kept));

		setKeptDocAcl(Permission.READ, Permission.DELETE);
		allowed(() -> cmisDeleteTree(kept));
		assertNull(documentDao.findById(keptDoc.getId()));
	}

	@Test
	public void testFolderDeleteChecksEveryRoute() throws Exception {
		// The DAO itself, as any route that calls it
		assertRefused(DELETE_TREE, () -> folderDao.deleteTree(old.getId(), folderHistory(alice)));

		// The system user is checked as any user: it has no grant here
		User system = UserDAO.get().findByUsername("_system");
		assertRefused(DELETE_TREE, () -> folderDao.deleteTree(old.getId(), folderHistory(system)));

		// Without a user there's nothing to check, and CE refuses the delete itself
		assertRefused("CE's refusal", e -> {
			assertTrue(e.toString(), e instanceof PersistenceException);
			assertNull(e.toString(), ruleIn(e));
		}, () -> folderDao.deleteTree(old.getId(), folderHistory(null)));

		allowed(() -> folderDao.deleteTree(old.getId(), folderHistory(admin)));
		assertNull(folderDao.findById(sealed.getId()));
	}

	@Test
	public void testCmisDocumentDeleteNeedsItsDelete() throws Exception {
		assertRefused("R70", cmisDenied("DELETE", keptDoc),
				() -> harness.cmisFor(kept.getId(), aliceSession).deleteObject(null, "doc." + keptDoc.getId()));
		// CMIS deleteTree also deletes a document given
		assertRefused("R70", cmisDenied("DELETE", keptDoc),
				() -> harness.cmisFor(kept.getId(), aliceSession).deleteTree(null, "doc." + keptDoc.getId(), false));

		setKeptDocAcl(Permission.READ, Permission.DELETE);
		allowed(() -> harness.cmisFor(kept.getId(), aliceSession).deleteObject(null, "doc." + keptDoc.getId()));
		assertNull(documentDao.findById(keptDoc.getId()));
	}

	@Test
	public void testWebdavRefusesFolderCopies() throws Exception {
		// CE's WebDAV copies documents only, so R73's folder walk has nothing to guard
		WebdavSession session = davSession(alice);
		assertRefused("CE's refusal", e -> {
			assertTrue(e.toString(), e instanceof DavException);
			assertEquals(403, ((DavException) e).getErrorCode());
		}, () -> harness.webdav().copy(harness.webdav().getResource(path(old), session),
				harness.webdav().getResource(path(documents), session), "Copy", session));
	}

	@Test
	public void testWebdavCopyNeedsDownload() throws Exception {
		WebdavSession session = davSession(alice);
		assertRefused("R73", davForbidden("No rights to download the document."), () -> harness.webdav().copy(
				harness.webdav().getResource(path(sealed) + "/sealed.txt", session),
				harness.webdav().getResource(path(documents), session), null, session));

		setAcl(sealed, ace(Permission.READ, Permission.DOWNLOAD));
		allowed(() -> harness.webdav().copy(harness.webdav().getResource(path(sealed) + "/sealed.txt", session),
				harness.webdav().getResource(path(documents), session), null, session));
		assertCopiedToDocuments();
	}

	@Test
	public void testCmisCopyNeedsDownload() throws Exception {
		assertRefused("R73", cmisDenied("DOWNLOAD", inSealed), () -> harness.cmisFor(documents.getId(), aliceSession)
				.createDocumentFromSource(null, "doc." + inSealed.getId(), "fld." + documents.getId()));

		setAcl(sealed, ace(Permission.READ, Permission.DOWNLOAD));
		allowed(() -> harness.cmisFor(documents.getId(), aliceSession).createDocumentFromSource(null,
				"doc." + inSealed.getId(), "fld." + documents.getId()));
		assertCopiedToDocuments();
	}

	private void assertCopiedToDocuments() throws Exception {
		assertEquals(1, documentDao.findByFileNameAndParentFolderId(documents.getId(), "sealed.txt", null,
				inSealed.getTenantId(), null).size());
	}

	private void grantDeleteOnSealed() throws Exception {
		setAcl(sealed, ace(Permission.READ, Permission.DELETE));
		awaitAsyncWork();
	}

	/**
	 * Gives kept.txt an ACL of its own, with these permissions for Alice's
	 * group
	 */
	private void setKeptDocAcl(Permission... permissions) throws Exception {
		Document stored = reload(keptDoc);
		stored.getAccessControlList().clear();
		DocumentAccessControlEntry ace = new DocumentAccessControlEntry(lawyers.getId());
		ace.grantPermissions(Set.of(permissions));
		stored.addAccessControlEntry(ace);
		documentDao.store(stored);
		awaitAsyncWork();
	}

	/**
	 * Deletes as a WebDAV DELETE does: the servlet's {@code removeMember}
	 * checks the resource's DELETE, then the resource service deletes it
	 */
	private void webdavDelete(String path, User as) throws Exception {
		WebdavSession session = davSession(as);
		Resource resource = harness.webdav().getResource(path, session);
		assertNotNull(path, resource);
		if (!resource.isDeleteEnabled())
			throw new DavException(403, "Delete not allowed.");
		harness.webdav().deleteResource(resource, session);
	}

	private void cmisDeleteTree(Folder folder) throws Exception {
		harness.cmisFor(folder.getId(), aliceSession).deleteTree(null, "fld." + folder.getId(), false);
	}

	/**
	 * CMIS's deleteTree reports any failure as a storage error and logs why
	 */
	private void assertCmisTreeRefused(Route route) throws Exception {
		try (LogCapture log = LogCapture.of(LDRepository.class)) {
			assertRefused(DELETE_TREE, e -> {
				assertTrue(e.toString(), e instanceof CmisStorageException);
				assertTrue(log.lines().toString(),
						log.lines().stream().anyMatch(line -> line.contains(DELETE_TREE)));
			}, route);
		}
	}


	/**
	 * @return a WebDAV session for the user; Alice's carries her session id,
	 *         which a WebDAV copy needs
	 */
	private WebdavSession davSession(User as) {
		DavSessionImpl session = (DavSessionImpl) harness.davSession(as);
		if (as.getId() == alice.getId())
			session.putObject("sid", aliceSession.getSid());
		return session;
	}

	private String path(Folder folder) throws Exception {
		return folderDao.computePathExtended(folder.getId());
	}

	private static Refusal davRefusal() {
		return e -> assertTrue(e.toString(), e instanceof DavException);
	}

	/**
	 * WebDAV's delete turns any failure but its own refusals into a 500 with
	 * the message
	 */
	private static Refusal davTreeRefusal() {
		return e -> {
			assertTrue(e.toString(), e instanceof DavException);
			assertEquals(500, ((DavException) e).getErrorCode());
			assertEquals(DELETE_TREE, e.getMessage());
		};
	}

	private static Refusal davForbidden(String message) {
		return e -> {
			assertTrue(e.toString(), e instanceof DavException);
			assertEquals(403, ((DavException) e).getErrorCode());
			assertEquals(message, e.getMessage());
		};
	}

	private static Refusal cmisDenied(String permission, Document document) {
		return e -> {
			assertTrue(e.toString(), e instanceof CmisPermissionDeniedException);
			assertEquals("Permission %s not granted on document %d".formatted(permission, document.getId()),
					e.getMessage());
		};
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

	private FolderAccessControlEntry ace(Permission... permissions) {
		FolderAccessControlEntry ace = new FolderAccessControlEntry(lawyers.getId());
		ace.grantPermissions(Set.of(permissions));
		return ace;
	}
}
