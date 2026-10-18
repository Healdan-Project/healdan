package com.logicaldoc.healdan.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Spec §7.1: a document may be created, copied, moved or restored only into a
 * folder inside a matter workspace that isn't a search folder. Every refusal
 * leaves the database, the index and DAO reads as they were, and binds admins
 * too.
 */
public class FilingRulesTest extends AbstractRulesTestCase {

	/**
	 * @return each place a document can't go, with the message that refuses it
	 */
	private Map<Folder, String> refusedTargets() throws Exception {
		Map<Folder, String> targets = new LinkedHashMap<>();
		targets.put(store, DOCUMENT_OUTSIDE);
		targets.put(defaultWorkspace, DOCUMENT_OUTSIDE);
		targets.put(loose, DOCUMENT_OUTSIDE);
		targets.put(templates, DOCUMENT_OUTSIDE);
		targets.put(template, DOCUMENT_OUTSIDE);
		targets.put(workspace, DOCUMENT_TOP_LEVEL);
		targets.put(search, DOCUMENT_IN_SEARCH_FOLDER);
		return targets;
	}

	@Test
	public void testCreate() throws Exception {
		for (Map.Entry<Folder, String> target : refusedTargets().entrySet())
			assertRefused(target.getValue(), () -> createDocument(target.getKey(), "new.txt", admin));

		allowed(() -> createDocument(documents, "new.txt", admin));
		assertEquals(1, documentDao.findByFileNameAndParentFolderId(documents.getId(), "new.txt", null,
				Tenant.DEFAULT_ID, null).size());
	}

	@Test
	public void testCopyToFolder() throws Exception {
		for (Map.Entry<Folder, String> target : refusedTargets().entrySet())
			assertRefused(target.getValue(),
					() -> DocumentManager.get().copyToFolder(reload(doc), target.getKey(), documentHistory(admin), false,
							false, false));

		allowed(() -> DocumentManager.get().copyToFolder(reload(doc), documents, documentHistory(admin), false, false,
				false));
		assertEquals(1, documentDao.findByFolder(documents.getId(), null).size());
	}

	@Test
	public void testMoveToFolder() throws Exception {
		for (Map.Entry<Folder, String> target : refusedTargets().entrySet())
			assertRefused(target.getValue(),
					() -> DocumentManager.get().moveToFolder(reload(doc), target.getKey(), documentHistory(admin)));

		allowed(() -> DocumentManager.get().moveToFolder(reload(doc), documents, documentHistory(admin)));
		assertEquals(documents.getId(), reload(doc).getFolder().getId());
	}

	@Test
	public void testRestore() throws Exception {
		for (Map.Entry<Folder, String> target : refusedTargets().entrySet())
			assertRefused(target.getValue(),
					() -> documentDao.restore(deletedDoc.getId(), target.getKey().getId(), documentHistory(admin)));

		// Restoring needs DELETE on the document, as a folder's restore does (R22): Bob only reads it
		assertRefused(RESTORE_DOCUMENT_PERMISSION, () -> restore(bob));
		// The document's own ACL holds where it is narrower: Alice's group has DELETE on the folder, only READ on it
		ownEntry(lawyers.getId(), 0);
		assertRefused(RESTORE_DOCUMENT_PERMISSION, () -> restore(alice));
		jdbc.update("delete from ld_document_acl where ld_docid = ?", deletedDoc.getId());

		// Alice's DELETE comes from the folder
		allowed(() -> restore(alice));
		assertEquals(documents.getId(), reload(deletedDoc).getFolder().getId());
		// Restoring a live document would move it, with no MOVE asked
		assertRefused(RESTORE_LIVE_DOCUMENT, () -> restore(alice));

		// Bob's DELETE comes from the deleted document's own ACL (D7)
		documentDao.delete(deletedDoc.getId(), documentHistory(admin));
		awaitAsyncWork();
		ownEntry(bob.getUserGroup().getId(), 1);
		allowed(() -> restore(bob));
		assertEquals(documents.getId(), reload(deletedDoc).getFolder().getId());
	}

	private void restore(User as) throws PersistenceException {
		documentDao.restore(deletedDoc.getId(), documents.getId(), documentHistory(as));
	}

	/**
	 * Adds an entry with READ to the deleted document's own ACL, with JDBC
	 * because CE's DAO doesn't load a deleted document
	 */
	private void ownEntry(long groupId, int delete) {
		jdbc.update("""
				insert into ld_document_acl (ld_docid, ld_groupid, ld_read, ld_write, ld_security, ld_immutable, ld_delete,
				  ld_customid, ld_rename, ld_sign, ld_preview, ld_archive, ld_workflow, ld_download, ld_calendar,
				  ld_subscription, ld_print, ld_password, ld_move, ld_email, ld_automation, ld_readingreq, ld_revision)
				values (?, ?, 1, 0, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
				""", deletedDoc.getId(), groupId, delete);
	}

	@Test
	public void testAdminIsRefusedAtTheWorkspaceTopLevel() throws Exception {
		assertRefused(DOCUMENT_TOP_LEVEL, () -> createDocument(workspace, "top.txt", admin));
		assertRefused(DOCUMENT_TOP_LEVEL,
				() -> DocumentManager.get().moveToFolder(reload(doc), workspace, documentHistory(admin)));
	}

	@Test
	public void testNonAdminIsRefusedAndAllowedTheSame() throws Exception {
		assertRefused(DOCUMENT_TOP_LEVEL, () -> createDocument(workspace, "top.txt", alice));
		assertRefused(DOCUMENT_OUTSIDE, () -> createDocument(defaultWorkspace, "loose.txt", alice));
		allowed(() -> createDocument(correspondence, "letter.txt", alice));
	}

	@Test
	public void testDeeperFoldersAreAllowed() throws Exception {
		Folder deep = fx.folder(fx.folder(documents, "2026"), "Invoices");
		allowed(() -> createDocument(deep, "invoice.txt", alice));
		allowed(() -> DocumentManager.get().moveToFolder(reload(doc), deep, documentHistory(alice)));
		assertEquals(deep.getId(), reload(doc).getFolder().getId());
	}

	@Test
	public void testShortcuts() throws Exception {
		// Inside a Store, a shortcut is refused even where a document may go
		assertRefused(SHORTCUT,
				() -> DocumentManager.get().createAlias(reload(doc), documents, null, documentHistory(admin)));
		assertRefused(SHORTCUT, () -> DocumentManager.get().createAlias(reload(doc), template, null,
				documentHistory(admin)));
		// Outside, the filing rule refuses it
		assertRefused(DOCUMENT_OUTSIDE, () -> DocumentManager.get().createAlias(reload(doc), defaultWorkspace, null,
				documentHistory(admin)));

		// Copying a shortcut makes another
		Document shortcut = fx.legacyShortcut(doc, correspondence);
		assertRefused(SHORTCUT, () -> DocumentManager.get().copyToFolder(reload(shortcut), documents,
				documentHistory(admin), false, false, false));
		assertRefused(SHORTCUT,
				() -> DocumentManager.get().moveToFolder(reload(shortcut), documents, documentHistory(admin)));
	}

	@Test
	public void testFolderChangeThroughAPlainStore() throws Exception {
		// A store that changes the folder without a MOVED event is a move too
		assertRefused(DOCUMENT_OUTSIDE, () -> {
			Document document = reload(doc);
			document.setFolder(loose);
			DocumentHistory transaction = documentHistory(admin);
			transaction.setEvent(DocumentEvent.CHANGED);
			documentDao.store(document, transaction);
		});
		allowed(() -> {
			Document document = reload(doc);
			document.setFolder(documents);
			DocumentHistory transaction = documentHistory(admin);
			transaction.setEvent(DocumentEvent.CHANGED);
			documentDao.store(document, transaction);
		});
		assertEquals(documents.getId(), reload(doc).getFolder().getId());
	}

	@Test
	public void testOrdinaryStoresOfMisplacedDocumentsAreNotVetoed() throws Exception {
		// Data from before Healdan: a document in the Default workspace
		jdbc.update("update ld_document set ld_folderid = ? where ld_id = ?", loose.getId(), doc.getId());
		Context.get(CacheEvictor.class).evictDocuments(List.of(doc.getId()));

		allowed(() -> {
			Document document = reload(doc);
			document.setColor("red");
			DocumentHistory transaction = documentHistory(admin);
			transaction.setEvent(DocumentEvent.CHANGED);
			documentDao.store(document, transaction);
		});
		assertEquals("red", reload(doc).getColor());

		// Unarchiving stores it where it is, with the event RESTORED
		allowed(() -> documentDao.archive(doc.getId(), documentHistory(admin)));
		allowed(() -> documentDao.unarchive(doc.getId(), documentHistory(admin)));
		assertEquals(DocumentStatus.UNLOCKED, reload(doc).getStatus());

		// Moving it out of there is a transition, to a folder that must be allowed
		assertRefused(DOCUMENT_TOP_LEVEL,
				() -> DocumentManager.get().moveToFolder(reload(doc), workspace, documentHistory(admin)));
		allowed(() -> DocumentManager.get().moveToFolder(reload(doc), documents, documentHistory(admin)));
	}

	@Test
	public void testRestoreIntoAMissingFolder() throws Exception {
		assertRefused(DOCUMENT_OUTSIDE, () -> documentDao.restore(deletedDoc.getId(), 987654321L, documentHistory(admin)));
		assertNull(documentDao.findById(deletedDoc.getId()));
		assertNotNull(documentDao.findDocument(doc.getId()));
	}
}
