// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.DocumentListenerManager;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.web.AbstractWPTestCase;

/**
 * The Classic Interface's Delete from the Trash and Empty Trash ask Healdan's
 * hooks first (Trash spec 2.3): a hook that answers true is called with the
 * RPC's table and ids and CE's own update doesn't run, one that answers false,
 * the default, leaves CE's update as it is, and the RPC's own check of what a
 * non-admin may purge comes before the hook. The Classic Interface's delete
 * takes a document before its shortcuts (Trash spec 9.1).
 */
public class HealdanTrashHooksTest extends AbstractWPTestCase {

	private static final long TRASHED_DOCUMENT = 4L;

	private static final long TRASHED_FOLDER = 8L;

	private HealdanHooks previous;

	private DocumentDAO docDao;

	private FolderDAO folderDao;

	@Before
	public void remember() throws Exception {
		previous = HealdanHooks.get();
		docDao = DocumentDAO.get();
		folderDao = FolderDAO.get();
		prepareSession("admin", "admin");
		// One document and one folder in the Trash, deleted by the admin
		docDao.jdbcUpdate("update ld_document set ld_deleted = 1, ld_deleteuserid = 1 where ld_id = " + TRASHED_DOCUMENT);
		folderDao.jdbcUpdate("update ld_folder set ld_deleted = 1, ld_deleteuserid = 1 where ld_id = " + TRASHED_FOLDER);
	}

	@After
	public void restore() {
		HealdanHooks.register(previous);
	}

	@Test
	public void testAHookThatAnswersTrueSkipsCesUpdate() throws Exception {
		Recorder hooks = new Recorder(true);
		HealdanHooks.register(hooks);

		new DocumentServiceImpl().deleteFromTrash(List.of(TRASHED_DOCUMENT));
		new FolderServiceImpl().deleteFromTrash(List.of(TRASHED_FOLDER));
		new DocumentServiceImpl().emptyTrash();

		assertEquals(List.of("ld_document:" + TRASHED_DOCUMENT, "ld_folder:" + TRASHED_FOLDER, "empty:admin"),
				hooks.calls);
		assertEquals(1, docDao.queryForInt("select ld_deleted from ld_document where ld_id = " + TRASHED_DOCUMENT));
		assertEquals(1, folderDao.queryForInt("select ld_deleted from ld_folder where ld_id = " + TRASHED_FOLDER));
	}

	@Test
	public void testTheDefaultLeavesCesUpdate() throws Exception {
		Recorder hooks = new Recorder(false);
		HealdanHooks.register(hooks);

		new DocumentServiceImpl().deleteFromTrash(List.of(TRASHED_DOCUMENT));
		new FolderServiceImpl().deleteFromTrash(List.of(TRASHED_FOLDER));

		assertEquals(2, hooks.calls.size());
		assertEquals(2, docDao.queryForInt("select ld_deleted from ld_document where ld_id = " + TRASHED_DOCUMENT));
		assertEquals(2, folderDao.queryForInt("select ld_deleted from ld_folder where ld_id = " + TRASHED_FOLDER));

		// With no hook registered at all
		HealdanHooks.register(null);
		new DocumentServiceImpl().emptyTrash();
		assertEquals(0, docDao.queryForInt("select count(*) from ld_document where ld_deleted = 1"));
	}

	@Test
	public void testANonAdminsPurgeOfAnothersRowIsRefusedBeforeTheHook() throws Exception {
		Recorder hooks = new Recorder(true);
		HealdanHooks.register(hooks);
		prepareSession("author", "admin");

		assertThrows(AccessDeniedException.class,
				() -> new DocumentServiceImpl().deleteFromTrash(List.of(TRASHED_DOCUMENT)));
		assertThrows(AccessDeniedException.class,
				() -> new FolderServiceImpl().deleteFromTrash(List.of(TRASHED_FOLDER)));

		assertTrue(hooks.calls.isEmpty());
		assertEquals(1, docDao.queryForInt("select ld_deleted from ld_document where ld_id = " + TRASHED_DOCUMENT));
		assertEquals(1, folderDao.queryForInt("select ld_deleted from ld_folder where ld_id = " + TRASHED_FOLDER));
	}

	@Test
	public void testADeleteTakesTheDocumentBeforeItsShortcuts() throws Exception {
		// Document 1 has shortcut 2. A refused document leaves its shortcut live
		Deletes refusing = new Deletes(1L);
		DocumentListenerManager.get().getListeners().add(refusing);
		try {
			assertThrows(Exception.class, () -> new DocumentServiceImpl().delete(List.of(1L)));
		} finally {
			DocumentListenerManager.get().getListeners().remove(refusing);
		}
		assertEquals(0, docDao.queryForInt("select ld_deleted from ld_document where ld_id = 2"));

		// Otherwise the document goes first, then its shortcut
		Deletes deletes = new Deletes(0L);
		DocumentListenerManager.get().getListeners().add(deletes);
		try {
			new DocumentServiceImpl().delete(List.of(1L));
		} finally {
			DocumentListenerManager.get().getListeners().remove(deletes);
		}
		assertEquals(List.of(1L, 2L), deletes.order);
	}

	/**
	 * Records the documents stored deleted, in order, and refuses one
	 */
	private static class Deletes implements DocumentListener {

		private final long refused;

		private final List<Long> order = new ArrayList<>();

		Deletes(long refused) {
			this.refused = refused;
		}

		@Override
		public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
				throws PersistenceException {
			if (document.getDeleted() != 0 && document.getId() == refused)
				throw new HealdanVetoException("Not this one.");
		}

		@Override
		public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			if (document.getDeleted() != 0)
				order.add(document.getId());
		}

		@Override
		public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to record
		}

		@Override
		public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to record
		}

		@Override
		public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to record
		}

		@Override
		public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
			// Nothing to record
		}
	}

	/**
	 * Remembers what it was asked
	 */
	private static class Recorder implements HealdanHooks {

		private final boolean answer;

		private final List<String> calls = new ArrayList<>();

		Recorder(boolean answer) {
			this.answer = answer;
		}

		@Override
		public boolean removeFromTrash(String table, Collection<Long> ids, User user) throws PersistenceException {
			for (Long id : ids)
				calls.add(table + ":" + id);
			return answer;
		}

		@Override
		public boolean emptyTrash(User user) throws PersistenceException {
			calls.add("empty:" + user.getUsername());
			return answer;
		}
	}
}
