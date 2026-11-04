// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * M5 spec §6.4: a matter holds a message once. Filing it again into the same
 * matter is refused on every route (an upload, a copy, a move or a folder from
 * another matter, a restore), naming the copy; another matter holds its own;
 * a copy the user can't read doesn't count, and with no user every copy does.
 * Each refusal leaves the database, the index, DAO reads and the file Store as
 * they were.
 */
public class OncePerMatterTest extends AbstractRulesTestCase {

	/** The Message-ID of {@code healdan-cc.eml} */
	private static final String MESSAGE_ID = "1@example.com";

	/** Matter 998.2 */
	private Matter second;

	/** 998.2's Documents */
	private Folder otherDocuments;

	/** The Documents of matter 998.3, where {@link #beforeM5} files first */
	private Folder scratch;

	@Before
	public void createMoreMatters() throws Exception {
		second = fx.matter(ClientDAO.get().findByNumber(998), 2, "Second Matter", store, "Documents");
		otherDocuments = child(folderDao.findById(second.getFolderId()), "Documents");
		Matter third = fx.matter(ClientDAO.get().findByNumber(998), 3, "Scratch", store, "Documents");
		scratch = child(folderDao.findById(third.getFolderId()), "Documents");
	}

	private Document fileEmail(Folder folder, DocumentHistory history) throws Exception {
		return fileEmail("healdan-cc.eml", folder, history);
	}

	private Document fileEmail(String resource, Folder folder, DocumentHistory history) throws Exception {
		File copy = File.createTempFile("email", ".eml");
		try {
			Files.copy(new File("src/test/resources/email/" + resource).toPath(), copy.toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			Document created = DocumentManager.get().create(copy, documentVO(folder, "message.eml"), history)
					.getDocument();
			awaitAsyncWork();
			return reload(created);
		} finally {
			copy.delete();
		}
	}

	private Document fileEmail(Folder folder, com.logicaldoc.core.security.user.User as) throws Exception {
		return fileEmail(folder, documentHistory(as));
	}

	private static String alreadyIn(String matter, Document copy) {
		return "This message is already in " + matter + " as #" + copy.getCustomId() + ".";
	}

	private static List<Long> copies() {
		return EmailIndex.get().copies(MESSAGE_ID);
	}

	/**
	 * Gives the folder an ACL of its own that grants only the given group, as
	 * an admin narrows a folder
	 */
	private void only(Folder folder, Group group) throws Exception {
		Folder own = folderDao.findById(folder.getId(), true);
		own.setSecurityRef(null);
		own.getAccessControlList().clear();
		folderDao.store(own, folderHistory(admin));
		fx.grant(folder, group, Permission.values());
	}

	/**
	 * Files a copy of the message where the rule wouldn't let it, as data from
	 * before M5 may hold: it is filed in 998.3, then put in the folder with JDBC
	 */
	private Document beforeM5(Folder folder) throws Exception {
		Document copy = fileEmail(scratch, admin);
		jdbc.update("update ld_document set ld_folderid = ? where ld_id = ?", folder.getId(), copy.getId());
		CacheEvictor.get().evictDocuments(List.of(copy.getId()));
		return reload(copy);
	}

	@Test
	public void testUploadIntoTheSameMatterRefused() throws Exception {
		Document first = fileEmail(documents, alice);

		assertRefused(alreadyIn("998.1", first), () -> fileEmail(correspondence, alice), first.getId());
		// Admins too, and into the very folder of the copy
		assertRefused(alreadyIn("998.1", first), () -> fileEmail(documents, admin), first.getId());
		assertEquals(List.of(first.getId()), copies());
	}

	@Test
	public void testAnotherMatterFiles() throws Exception {
		Document first = fileEmail(documents, alice);

		Document other = fileEmail(otherDocuments, alice);

		assertEquals(List.of(first.getId(), other.getId()), copies());
	}

	@Test
	public void testCopyRefused() throws Exception {
		Document first = fileEmail(documents, alice);

		assertRefused(alreadyIn("998.1", first), () -> DocumentManager.get().copyToFolder(reload(first),
				correspondence, documentHistory(alice), false, false, false), first.getId());

		allowed(() -> DocumentManager.get().copyToFolder(reload(first), otherDocuments, documentHistory(alice), false,
				false, false));
		assertEquals(2, copies().size());
	}

	@Test
	public void testMoveFromAnotherMatterRefused() throws Exception {
		Document first = fileEmail(documents, alice);
		Document other = fileEmail(otherDocuments, alice);

		assertRefused(alreadyIn("998.1", first),
				() -> DocumentManager.get().moveToFolder(reload(other), correspondence, documentHistory(alice)),
				first.getId(), other.getId());
		// A store that changes the folder without a MOVED event is a move too
		assertRefused(alreadyIn("998.1", first), () -> {
			Document document = reload(other);
			document.setFolder(correspondence);
			DocumentHistory transaction = documentHistory(alice);
			transaction.setEvent(DocumentEvent.CHANGED);
			documentDao.store(document, transaction);
		}, first.getId(), other.getId());

		Folder inner = fx.folder(otherDocuments, "Inner");
		allowed(() -> DocumentManager.get().moveToFolder(reload(other), inner, documentHistory(alice)));
		assertEquals(inner.getId(), reload(other).getFolder().getId());
	}

	@Test
	public void testAMoveIsCheckedAgainInItsStore() throws Exception {
		Document other = fileEmail(otherDocuments, alice);
		// A copy arrives in 998.1 between the move's hook and its store, as another upload's could
		HealdanHooks real = HealdanHooks.get();
		Document[] arrived = new Document[1];
		HealdanHooks.register(new HealdanHooks() {
			@Override
			public void beforeDocumentMove(Document document, Folder target, DocumentHistory transaction)
					throws PersistenceException {
				real.beforeDocumentMove(document, target, transaction);
				HealdanHooks.register(real);
				try {
					arrived[0] = fileEmail(documents, alice);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
		});
		try {
			DocumentManager.get().moveToFolder(reload(other), correspondence, documentHistory(alice));
			fail("Not refused");
		} catch (Exception e) {
			assertEquals(alreadyIn("998.1", arrived[0]), ruleIn(e).getMessage());
		} finally {
			HealdanHooks.register(real);
		}
		awaitAsyncWork();

		assertEquals(otherDocuments.getId(), reload(other).getFolder().getId());
		assertEquals(List.of(other.getId(), arrived[0].getId()), copies());
	}

	@Test
	public void testMoveWithinTheMatterAllowed() throws Exception {
		// Admin's copy, in a folder of 998.1 that Alice can't read, doesn't stop hers
		Folder board = fx.folder(documents, "Board");
		only(board, GroupDAO.get().findByName(Group.GROUP_ADMIN, Tenant.DEFAULT_ID));
		Document unreadable = fileEmail(board, admin);
		Document mine = fileEmail(documents, alice);
		// Nor does a copy from before M5 that she can read
		Document old = beforeM5(correspondence);
		assertEquals(List.of(unreadable.getId(), mine.getId(), old.getId()), copies());

		allowed(() -> DocumentManager.get().moveToFolder(reload(mine), correspondence, documentHistory(alice)));
		assertEquals(correspondence.getId(), reload(mine).getFolder().getId());
	}

	@Test
	public void testRestoreRefused() throws Exception {
		Document trashed = fileEmail(documents, alice);
		documentDao.delete(trashed.getId(), documentHistory(alice));
		// The trash doesn't count
		Document current = fileEmail(documents, alice);

		assertRefused(alreadyIn("998.1", current),
				() -> documentDao.restore(trashed.getId(), documents.getId(), documentHistory(alice)),
				current.getId());

		// Restoring the only copy
		documentDao.delete(current.getId(), documentHistory(alice));
		allowed(() -> documentDao.restore(trashed.getId(), documents.getId(), documentHistory(alice)));
		assertEquals(List.of(trashed.getId()), copies());
	}

	@Test
	public void testFolderMoveAndMergeRefused() throws Exception {
		Document first = fileEmail(documents, alice);
		Folder inbox = fx.folder(otherDocuments, "Inbox");
		Document other = fileEmail(inbox, alice);

		assertRefused(alreadyIn("998.1", first), () -> folderDao.move(reload(inbox), documents, folderHistory(alice)),
				first.getId(), other.getId());
		assertRefused(alreadyIn("998.1", first),
				() -> folderDao.merge(reload(inbox), reload(correspondence), folderHistory(alice)), first.getId(),
				other.getId());
		// Deeper in the folder moved is the same
		Folder outer = fx.folder(otherDocuments, "Outer");
		allowed(() -> folderDao.move(reload(inbox), outer, folderHistory(alice)));
		assertRefused(alreadyIn("998.1", first), () -> folderDao.move(reload(outer), documents, folderHistory(alice)),
				first.getId(), other.getId());

		// A folder with no copy moves and merges
		Folder empty = fx.folder(otherDocuments, "Empty");
		fx.upload(empty, "note.txt", CONTENT, alice);
		awaitAsyncWork();
		allowed(() -> folderDao.move(reload(empty), documents, folderHistory(alice)));
		Folder plain = fx.folder(otherDocuments, "Plain");
		fx.upload(plain, "memo.txt", CONTENT, alice);
		awaitAsyncWork();
		allowed(() -> folderDao.merge(reload(plain), reload(correspondence), folderHistory(alice)));

		// Within 998.2 the folder moves
		allowed(() -> folderDao.move(reload(outer), reload(otherDocuments), folderHistory(alice)));
	}

	@Test
	public void testFolderRestoreRefused() throws Exception {
		Folder letters = fx.folder(documents, "Letters");
		Document trashed = fileEmail(fx.folder(letters, "Inner"), alice);
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		awaitAsyncWork();
		Document current = fileEmail(correspondence, alice);

		// Restored into 998.1, where the message is live elsewhere
		assertRefused(alreadyIn("998.1", current),
				() -> folderDao.restore(letters.getId(), documents.getId(), folderHistory(alice)), current.getId(),
				trashed.getId());

		// Once the other copy is gone, it comes back
		documentDao.delete(current.getId(), documentHistory(alice));
		allowed(() -> folderDao.restore(letters.getId(), documents.getId(), folderHistory(alice)));
		assertEquals(List.of(trashed.getId()), copies());
	}

	@Test
	public void testFolderRestoreBringsBackWhatItHid() throws Exception {
		// A folder deleted on its own leaves its documents live, but out of the matter
		Folder letters = fx.folder(documents, "Letters");
		Document hidden = fileEmail(letters, alice);
		folderDao.delete(letters.getId(), folderHistory(alice));
		awaitAsyncWork();
		Document current = fileEmail(correspondence, alice);

		/*
		 * The database rows only: any refused restore of such a folder, the filing
		 * rule's too, leaves the DAO reading its live documents as absent
		 */
		DbSnapshot before = fx.snapshot();
		try {
			folderDao.restore(letters.getId(), documents.getId(), folderHistory(alice));
			fail("Not refused");
		} catch (Exception e) {
			assertEquals(alreadyIn("998.1", current), ruleIn(e).getMessage());
		}
		awaitAsyncWork();
		before.assertUnchanged();
		assertEquals(List.of(hidden.getId(), current.getId()), copies());
	}

	@Test
	public void testFolderRestoreRefusesTwoCopiesComingBack() throws Exception {
		Folder letters = fx.folder(documents, "Letters");
		Document trashed = fileEmail(letters, alice);
		documentDao.delete(trashed.getId(), documentHistory(alice));
		// The trash doesn't count
		Document later = fileEmail(letters, alice);
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		awaitAsyncWork();

		// CE's restore brings back both, the one deleted on its own too
		assertRefused(alreadyIn("998.1", trashed),
				() -> folderDao.restore(letters.getId(), documents.getId(), folderHistory(alice)), trashed.getId(),
				later.getId());
	}

	@Test
	public void testASecondMergeInOneTransactionIsCheckedToo() throws Exception {
		Document first = fileEmail(documents, alice);
		Folder inbox = fx.folder(otherDocuments, "Inbox");
		fileEmail("healdan-emoji.eml", inbox, documentHistory(alice));
		Folder other = fx.folder(otherDocuments, "Other");
		fileEmail(other, alice);

		// The first merge's checked tree doesn't stand for the second's
		try {
			new TransactionTemplate(Context.get(PlatformTransactionManager.class)).executeWithoutResult(status -> {
				try {
					folderDao.merge(reload(inbox), reload(correspondence), folderHistory(alice));
					folderDao.merge(reload(other), reload(correspondence), folderHistory(alice));
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
			fail("Not refused");
		} catch (IllegalStateException e) {
			assertEquals(alreadyIn("998.1", first), ruleIn(e).getMessage());
		}
		awaitAsyncWork();
	}

	@Test
	public void testFolderCopyRefused() throws Exception {
		Folder letters = fx.folder(documents, "Letters");
		Document first = fileEmail(letters, alice);

		// A copy of the folder would hold a second copy of the message in 998.1
		assertRefused(alreadyIn("998.1", first),
				() -> folderDao.copy(reload(letters), reload(correspondence), null, false, "inherit", folderHistory(alice)),
				first.getId());
		// The folders alone are copied
		allowed(() -> folderDao.copy(reload(letters), reload(correspondence), null, true, "inherit",
				folderHistory(alice)));

		// Into 998.2 it copies
		allowed(() -> folderDao.copy(reload(letters), reload(otherDocuments), null, false, "inherit",
				folderHistory(alice)));
		assertEquals(2, copies().size());
	}

	@Test
	public void testTwoCopiesArrivingTogetherRefused() throws Exception {
		// Data from before M5: 998.2's Inbox holds the message twice
		Folder inbox = fx.folder(otherDocuments, "Inbox");
		Document one = fileEmail(inbox, alice);
		Document two = beforeM5(inbox);

		// Merged into 998.1, both would land there: refused before the first moves
		assertRefused(alreadyIn("998.2", one),
				() -> folderDao.merge(reload(inbox), reload(correspondence), folderHistory(alice)), one.getId(),
				two.getId());
		assertRefused(alreadyIn("998.2", one),
				() -> folderDao.copy(reload(inbox), reload(correspondence), null, false, "inherit", folderHistory(alice)),
				one.getId(), two.getId());
	}

	@Test
	public void testUnreadableCopyDoesntCount() throws Exception {
		Document hers = fileEmail(documents, alice);
		// Bob reads the Store, but not Documents
		only(documents, lawyers);
		assertTrue(OncePerMatter.get().copyIn(MESSAGE_ID, correspondence.getId(), 0, bob).isEmpty());

		// Review Focus 1: his filing doesn't tell him a copy exists
		Document his = fileEmail(correspondence, bob);
		assertEquals(List.of(hers.getId(), his.getId()), copies());

		// Alice can't read hers either now: the refusal names the copy she can read
		only(documents, GroupDAO.get().findByName(Group.GROUP_ADMIN, Tenant.DEFAULT_ID));
		Folder drafts = fx.folder(correspondence, "Drafts");
		assertRefused(alreadyIn("998.1", his), () -> fileEmail(drafts, alice), hers.getId(), his.getId());
	}

	@Test
	public void testNoUserEveryCopyCounts() throws Exception {
		Document hers = fileEmail(documents, alice);
		only(documents, lawyers);
		assertTrue(OncePerMatter.get().copyIn(MESSAGE_ID, correspondence.getId(), 0, bob).isEmpty());
		assertEquals(hers.getId(),
				OncePerMatter.get().copyIn(MESSAGE_ID, correspondence.getId(), 0, null).orElseThrow().getId());

		// A store with no user, as a system task makes it
		File file = File.createTempFile("email", ".eml");
		try {
			Files.copy(new File("src/test/resources/email/healdan-cc.eml").toPath(), file.toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			assertRefused(alreadyIn("998.1", hers), () -> {
				DocumentHistory nobody = documentHistory(null);
				nobody.setFile(file);
				documentDao.store(documentVO(correspondence, "message.eml"), nobody);
			}, hers.getId());
		} finally {
			file.delete();
		}
	}

	@Test
	public void testTheAttributeCountsWhenTheFileSaysNothing() throws Exception {
		Document hers = fileEmail(documents, alice);

		// A new document whose file wasn't read, carrying the Message-ID as a copy's clone does
		assertRefused(alreadyIn("998.1", hers), () -> {
			Document vo = documentVO(correspondence, "message.eml");
			vo.setValue(com.logicaldoc.healdan.HealdanConstants.ATTR_EMAILMESSAGEID, MESSAGE_ID);
			documentDao.store(vo, documentHistory(alice));
		}, hers.getId());
	}

	@Test
	public void testNoMessageIdNoRule() throws Exception {
		for (int i = 0; i < 2; i++) {
			File noId = File.createTempFile("noid", ".eml");
			try {
				Files.writeString(noId.toPath(), "From: ann@example.com\nTo: bob@example.com\nSubject: No ID\n"
						+ "Date: Tue, 14 Oct 2025 09:29:00 +0200\nMIME-Version: 1.0\nContent-Type: text/plain\n\n"
						+ "Hello.\n");
				DocumentManager.get().create(noId, documentVO(documents, "message.eml"), documentHistory(alice));
			} finally {
				noId.delete();
			}
		}
		awaitAsyncWork();

		assertEquals(2, documentDao.findByFolder(documents.getId(), null).size());
		assertTrue(copies().isEmpty());
	}

	@Test
	public void testOutsideAMatterNoRule() throws Exception {
		fileEmail(documents, alice);

		for (Folder folder : List.of(store, root, defaultWorkspace, loose, templates))
			assertTrue(folder.getName(), OncePerMatter.get().copyIn(MESSAGE_ID, folder.getId(), 0, admin).isEmpty());
		assertTrue(OncePerMatter.get().copyIn(null, correspondence.getId(), 0, admin).isEmpty());
		assertTrue(OncePerMatter.get().copyIn(MESSAGE_ID, otherDocuments.getId(), 0, admin).isEmpty());
		assertTrue(OncePerMatter.get().copyIn(MESSAGE_ID, correspondence.getId(), 0, admin).isPresent());
	}
}
