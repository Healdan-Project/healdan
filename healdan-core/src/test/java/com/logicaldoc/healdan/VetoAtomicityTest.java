package com.logicaldoc.healdan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.LongStream;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.support.DaoSnapshot;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.IndexSnapshot;
import com.logicaldoc.healdan.support.VetoSwitch;
import com.logicaldoc.healdan.support.VetoingHooks;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Spec §7.5: for every route a Healdan rule guards, a veto at the route's
 * first check point leaves the database and the search index exactly as they
 * were. The check point is the core hook where the route has one, otherwise
 * the listener's {@code beforeStore}. The findings are in the plan's decision
 * log, D3.
 */
public class VetoAtomicityTest extends AbstractHealdanTestCase {

	private static final String CONTENT = "Healdan veto atomicity";

	private JdbcTemplate jdbc;

	private DocumentDAO documentDao;

	private FolderDAO folderDao;

	private User admin;

	/** Folder with the indexed document and the deleted document */
	private Folder folderA;

	/** Folder with the alias of the indexed document */
	private Folder folderB;

	/** Child of A that inherits A's security */
	private Folder inheriting;

	/** Child of A with its own ACL */
	private Folder restricted;

	/** Deleted child of A, holding a document */
	private Folder deletedFolder;

	/** The deleted document in {@link #deletedFolder} */
	private Document insideDoc;

	/** An indexed document in A */
	private Document doc;

	/** An indexed alias of {@link #doc} in B */
	private Document alias;

	/** A deleted document in A */
	private Document deletedDoc;

	/** Folder to merge, made by {@link #createMergeSource()} */
	private long mergeSource;

	@Override
	protected List<String> getPluginArchives() {
		List<String> archives = new ArrayList<>(super.getPluginArchives());
		archives.add(PluginFixtures.addTestPlugin("healdan-test", """
				<extension plugin-id="logicaldoc-core" point-id="DocumentListener" id="vetoingDocumentListener">
					<parameter id="position" value="100" />
					<parameter id="class" value="com.logicaldoc.healdan.support.VetoingDocumentListener" />
				</extension>
				<extension plugin-id="logicaldoc-core" point-id="FolderListener" id="vetoingFolderListener">
					<parameter id="position" value="100" />
					<parameter id="class" value="com.logicaldoc.healdan.support.VetoingFolderListener" />
				</extension>"""));
		return archives;
	}

	/**
	 * Runs after the base class has built the context and the schema
	 */
	@Before
	public void createFixture() throws Exception {
		VetoSwitch.off();
		HealdanHooks.register(new VetoingHooks());

		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		documentDao = DocumentDAO.get();
		folderDao = FolderDAO.get();
		admin = UserDAO.get().findById(User.USERID_ADMIN, true);

		Folder workspace = folderDao.findDefaultWorkspace(Tenant.DEFAULT_ID);
		folderA = createFolder(workspace, "A", true);
		folderB = createFolder(workspace, "B", true);
		inheriting = createFolder(folderA, "Inheriting", true);
		restricted = createFolder(folderA, "Restricted", false);
		restricted = folderDao.findById(restricted.getId(), true);
		FolderAccessControlEntry ace = new FolderAccessControlEntry(3L);
		ace.setRead(true);
		restricted.addAccessControlEntry(ace);
		folderDao.store(restricted, folderTransaction(FolderEvent.PERMISSION));

		doc = createIndexedDocument(folderA, "doc.txt");
		alias = documentDao.findById(DocumentManager.get().createAlias(doc, folderB, null, documentTransaction()).getId(),
				true);
		alias.setIndexingStatus(IndexingStatus.INDEXED);
		documentDao.store(alias);

		deletedDoc = createDocument(folderA, "deleted.txt");
		documentDao.delete(deletedDoc.getId(), documentTransaction());

		deletedFolder = createFolder(folderA, "Deleted", true);
		insideDoc = createDocument(deletedFolder, "inside.txt");
		folderDao.deleteTree(deletedFolder.getId(), folderTransaction(null));

		awaitAsyncWork();
	}

	@After
	@Override
	public void tearDown() throws IOException {
		VetoSwitch.off();
		HealdanHooks.register(null);
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	@Test
	public void testSnapshotsSeeChanges() throws Exception {
		DaoSnapshot dao = DaoSnapshot.take(jdbc);
		DbSnapshot db = DbSnapshot.take(jdbc);
		jdbc.update("update ld_folder set ld_name = 'Changed' where ld_id = ?", folderA.getId());
		jdbc.update("insert into hd_setting (name, value) values ('veto.test', 'x')");
		AssertionError error = assertThrows(db::assertUnchanged);
		assertTrue(error.getMessage(), error.getMessage().contains("ld_folder:"));
		assertTrue(error.getMessage(), error.getMessage().contains("hd_setting:"));
		Context.get(CacheEvictor.class).evictFolders(List.of(folderA.getId()));
		error = assertThrows(dao::assertUnchanged);
		assertTrue(error.getMessage(), error.getMessage().contains("folder %d:".formatted(folderA.getId())));

		IndexSnapshot index = IndexSnapshot.of(doc.getId());
		SearchEngine.get().deleteHit(doc.getId());
		error = assertThrows(index::assertUnchanged);
		assertTrue(error.getMessage(), error.getMessage().contains("document %d".formatted(doc.getId())));
	}

	@Test
	public void testDocumentCreate() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.DOCUMENT_BEFORE_STORE, () -> {
			Document vo = new Document();
			vo.setFileName("new.txt");
			vo.setFolder(folderA);
			vo.setTenantId(Tenant.DEFAULT_ID);
			vo.setLanguage("en");
			DocumentManager.get().create(content(), vo, documentTransaction());
		});
	}

	@Test
	public void testDocumentUpdate() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.BEFORE_DOCUMENT_UPDATE, () -> {
			Document document = documentDao.findById(doc.getId(), true);
			Document vo = new Document(document);
			vo.setColor("red");
			DocumentManager.get().update(document, vo, documentTransaction());
		});
	}

	@Test
	public void testDocumentUpdateWithLanguageChange() throws Exception {
		// CE removes the hit before the store when the language changes
		assertVetoLeavesNothing(VetoSwitch.BEFORE_DOCUMENT_UPDATE, () -> {
			Document document = documentDao.findById(doc.getId(), true);
			Document vo = new Document(document);
			vo.setLanguage("it");
			DocumentManager.get().update(document, vo, documentTransaction());
		});
	}

	@Test
	public void testDocumentRename() throws Exception {
		// CE schedules the renamed version's save before the store
		assertVetoLeavesNothing(VetoSwitch.BEFORE_DOCUMENT_RENAME,
				() -> DocumentManager.get().rename(doc.getId(), "renamed.txt", documentTransaction()));
	}

	@Test
	public void testDocumentCheckin() throws Exception {
		DocumentManager.get().checkout(doc.getId(), documentTransaction());
		assertVetoLeavesNothing(VetoSwitch.DOCUMENT_BEFORE_STORE, () -> DocumentManager.get().checkin(doc.getId(),
				content(), "doc.txt", false, null, documentTransaction()));
	}

	@Test
	public void testDocumentCopyToFolder() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.DOCUMENT_BEFORE_STORE, () -> DocumentManager.get()
				.copyToFolder(documentDao.findById(doc.getId(), true), folderB, documentTransaction(), false, false, false));
	}

	@Test
	public void testDocumentMoveToFolder() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.BEFORE_DOCUMENT_MOVE, () -> DocumentManager.get()
				.moveToFolder(documentDao.findById(doc.getId(), true), folderB, documentTransaction()));
	}

	@Test
	public void testDocumentRestore() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.BEFORE_DOCUMENT_RESTORE,
				() -> documentDao.restore(deletedDoc.getId(), folderB.getId(), documentTransaction()));
	}

	@Test
	public void testDocumentRestoreVetoedAtStore() throws Exception {
		// The DAO undeletes the rows with JDBC, then loads the document, then stores it in the same transaction
		assertVetoLeavesNothing(VetoSwitch.DOCUMENT_BEFORE_STORE,
				() -> documentDao.restore(deletedDoc.getId(), folderB.getId(), documentTransaction()),
				() -> assertDocumentsDeleted(deletedDoc.getId()));
	}

	@Test
	public void testFolderCreate() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.FOLDER_BEFORE_STORE, () -> {
			Folder vo = new Folder("New");
			vo.setTenantId(Tenant.DEFAULT_ID);
			folderDao.create(folderA, vo, true, folderTransaction(null));
		});
	}

	@Test
	public void testFolderRename() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.FOLDER_BEFORE_STORE, () -> {
			Folder folder = folderDao.findById(inheriting.getId(), true);
			folder.setName("Renamed");
			folderDao.store(folder, folderTransaction(FolderEvent.RENAMED));
		});
	}

	@Test
	public void testFolderMove() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.FOLDER_BEFORE_STORE, () -> folderDao
				.move(folderDao.findById(inheriting.getId()), folderDao.findById(folderB.getId()), folderTransaction(null)));
	}

	@Test
	public void testFolderAclSave() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.FOLDER_BEFORE_STORE, () -> {
			Folder folder = folderDao.findById(restricted.getId(), true);
			FolderAccessControlEntry ace = new FolderAccessControlEntry(4L);
			ace.setRead(true);
			folder.addAccessControlEntry(ace);
			folderDao.store(folder, folderTransaction(FolderEvent.PERMISSION));
		});
	}

	@Test
	public void testFolderUpdateSecurityRef() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.FOLDER_BEFORE_STORE,
				() -> folderDao.updateSecurityRef(inheriting.getId(), restricted.getId(), folderTransaction(null)));
	}

	/**
	 * The overload CE calls. The interface default {@code delete(long)} is left
	 * out on purpose: it resolves to the base DAO's transaction attribute, with
	 * no rollback rule, and CE never calls it on folders (plan, D3)
	 */
	@Test
	public void testFolderDelete() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.FOLDER_BEFORE_STORE,
				() -> folderDao.delete(inheriting.getId(), folderTransaction(null)));
	}

	@Test
	public void testFolderDeleteTree() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.BEFORE_FOLDER_DELETE_TREE,
				() -> folderDao.deleteTree(folderA.getId(), folderTransaction(null)));
	}

	@Test
	public void testFolderRestore() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.BEFORE_FOLDER_RESTORE,
				() -> folderDao.restore(deletedFolder.getId(), folderB.getId(), folderTransaction(null)));
	}

	@Test
	public void testFolderRestoreRollsBackWhenAfterHookThrows() throws Exception {
		// The after-hook runs in restore's transaction, once the rows are back
		DbSnapshot db = DbSnapshot.take(jdbc);
		DaoSnapshot dao = DaoSnapshot.take(jdbc);
		HealdanHooks.register(new HealdanHooks() {
			@Override
			public void afterFolderRestore(long folderId, long parentId, FolderHistory transaction)
					throws PersistenceException {
				// As every Healdan veto must: restore has loaded the undeleted folder into the cache
				Context.get(CacheEvictor.class).evictFoldersOnRollback(List.of(folderId));
				throw new PersistenceException(VetoSwitch.MESSAGE);
			}
		});
		try {
			folderDao.restore(deletedFolder.getId(), folderB.getId(), folderTransaction(null));
			fail("The after-hook's exception was swallowed");
		} catch (PersistenceException e) {
			assertEquals(VetoSwitch.MESSAGE, e.getMessage());
		}
		db.assertUnchanged();
		// restore loaded the undeleted folder before the after-hook threw
		assertFoldersDeleted(deletedFolder.getId());
		assertDocumentsDeleted(insideDoc.getId());
		dao.assertUnchanged();
	}

	@Test
	public void testFolderMerge() throws Exception {
		long[] moved = createMergeSource();
		assertVetoLeavesNothing(VetoSwitch.BEFORE_FOLDER_MERGE, () -> folderDao.merge(folderDao.findById(mergeSource),
				folderDao.findById(folderB.getId()), folderTransaction(null)), moved);
	}

	/**
	 * Characterises, rather than guarantees: a listener veto in the middle of a
	 * merge rolls the database back, but each document moved before it keeps a
	 * MOVED version saved in the background, and it and the vetoed document
	 * have lost their index hits (moveToFolder removes the hit before the
	 * store). So rules that can refuse any part of a merge must run in
	 * beforeFolderMerge (D3).
	 */
	@Test
	public void testFolderMergeVetoedAtSecondDocumentKeepsFirstMoveSideEffects() throws Exception {
		long[] moved = createMergeSource();
		DbSnapshot db = DbSnapshot.take(jdbc);
		IndexSnapshot index = IndexSnapshot.of(moved);

		runVetoed(VetoSwitch.DOCUMENT_BEFORE_STORE, 1, () -> folderDao.merge(folderDao.findById(mergeSource),
				folderDao.findById(folderB.getId()), folderTransaction(null)));

		assertEquals(Set.of("ld_version"), db.changedTables());
		assertMovedVersions(db.addedRows("ld_version"), 1);
		assertEquals(Set.of(moved[0], moved[1]), index.changedDocuments());
	}

	/**
	 * Characterises, as above: a veto at the subfolder's store comes after
	 * every document has been moved
	 */
	@Test
	public void testFolderMergeVetoedAtSubfolderKeepsDocumentMoveSideEffects() throws Exception {
		long[] moved = createMergeSource();
		DbSnapshot db = DbSnapshot.take(jdbc);
		IndexSnapshot index = IndexSnapshot.of(moved);

		runVetoed(VetoSwitch.FOLDER_BEFORE_STORE, 0, () -> folderDao.merge(folderDao.findById(mergeSource),
				folderDao.findById(folderB.getId()), folderTransaction(null)));

		assertEquals(Set.of("ld_version"), db.changedTables());
		assertMovedVersions(db.addedRows("ld_version"), 2);
		assertEquals(Set.of(moved[0], moved[1]), index.changedDocuments());
	}

	@Test
	public void testFolderApplySecurityToTree() throws Exception {
		assertVetoLeavesNothing(VetoSwitch.BEFORE_APPLY_SECURITY_TO_TREE,
				() -> folderDao.applySecurityToTree(folderA.getId(), folderTransaction(null)));
	}

	/**
	 * Runs a route with the veto on at a check point, then proves that the
	 * route reached the check point, was refused there, and left the database
	 * and the index as they were
	 */
	private void assertVetoLeavesNothing(String route, Route operation, long... moreDocIds) throws Exception {
		assertVetoLeavesNothing(route, operation, () -> {
		}, moreDocIds);
	}

	/**
	 * As above, and also runs a check of what the DAOs read after the refusal,
	 * before the control run: Hibernate's second-level cache isn't
	 * transactional, so an entity loaded during the refused route can outlive
	 * the rollback
	 */
	private void assertVetoLeavesNothing(String route, Route operation, Route daoCheck, long... moreDocIds)
			throws Exception {
		assertNotNull("the indexed document must be in the index", SearchEngine.get().getHit(doc.getId()));
		DbSnapshot db = DbSnapshot.take(jdbc);
		long[] docIds = LongStream.concat(LongStream.of(doc.getId(), alias.getId(), deletedDoc.getId()),
				LongStream.of(moreDocIds)).toArray();
		IndexSnapshot index = IndexSnapshot.of(docIds);
		DaoSnapshot dao = DaoSnapshot.take(jdbc);

		runVetoed(route, 0, operation);

		db.assertUnchanged();
		index.assertUnchanged();
		daoCheck.run();
		dao.assertUnchanged();

		// Control: without the veto the same route does write, so the
		// snapshots had something to miss
		operation.run();
		awaitAsyncWork();
		assertTrue("the route changed nothing even without the veto",
				changed(db::assertUnchanged) || changed(index::assertUnchanged));
	}

	/**
	 * Runs a route with the veto on at a check point and proves that the route
	 * reached the check point and was refused there. Then waits for the work CE
	 * started in the background.
	 */
	private static void runVetoed(String route, int allowedPasses, Route operation) throws Exception {
		VetoSwitch.on(route, allowedPasses);
		try {
			operation.run();
			fail("The route wasn't vetoed at " + route);
		} catch (PersistenceException e) {
			assertEquals(VetoSwitch.MESSAGE, e.getMessage());
		} finally {
			VetoSwitch.off();
		}
		assertTrue("the veto didn't fire at " + route, VetoSwitch.vetoes() > 0);
		awaitAsyncWork();
	}

	@FunctionalInterface
	private interface Route {
		void run() throws Exception;
	}

	private static boolean changed(Runnable assertUnchanged) {
		try {
			assertUnchanged.run();
			return false;
		} catch (AssertionError e) {
			return true;
		}
	}

	private static AssertionError assertThrows(Runnable assertion) {
		try {
			assertion.run();
		} catch (AssertionError e) {
			return e;
		}
		throw new AssertionError("The snapshot didn't see the change");
	}

	/**
	 * Waits for the versions CE saves in the background
	 */
	private static void awaitAsyncWork() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 20000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				fail("Background version saves didn't finish");
			Thread.sleep(50L);
		}
	}

	/**
	 * Creates the folder to merge into B: two indexed documents and a
	 * subfolder
	 * 
	 * @return the ids of the two documents
	 */
	private long[] createMergeSource() throws Exception {
		Folder source = createFolder(folderDao.findDefaultWorkspace(Tenant.DEFAULT_ID), "Merge", true);
		mergeSource = source.getId();
		long[] docIds = { createIndexedDocument(source, "m1.txt").getId(), createIndexedDocument(source, "m2.txt").getId() };
		createFolder(source, "Sub", true);
		awaitAsyncWork();
		return docIds;
	}

	/**
	 * Asserts that the DAOs, through the lookups CE's GUI and web services use,
	 * see the documents as deleted
	 */
	private void assertDocumentsDeleted(long... docIds) throws PersistenceException {
		for (long id : docIds) {
			assertNull("DocumentDAO.findById sees document %d".formatted(id), documentDao.findById(id));
			assertNull("DocumentDAO.findDocument sees document %d".formatted(id), documentDao.findDocument(id));
		}
	}

	/**
	 * Asserts that the DAOs see the folders as deleted
	 */
	private void assertFoldersDeleted(long... folderIds) throws PersistenceException {
		for (long id : folderIds) {
			assertNull("FolderDAO.findById sees folder %d".formatted(id), folderDao.findById(id, true));
			assertNull("FolderDAO.findFolder sees folder %d".formatted(id), folderDao.findFolder(id));
		}
	}

	private static void assertMovedVersions(List<String> rows, int count) {
		assertEquals(rows.toString(), count, rows.size());
		for (String row : rows)
			assertTrue(row, row.contains("ld_event=event.moved"));
	}

	private Folder createFolder(Folder parent, String name, boolean inheritSecurity) throws PersistenceException {
		Folder vo = new Folder(name);
		vo.setTenantId(Tenant.DEFAULT_ID);
		return folderDao.create(parent, vo, inheritSecurity, folderTransaction(null));
	}

	private Document createDocument(Folder folder, String fileName) throws PersistenceException {
		Document vo = new Document();
		vo.setFileName(fileName);
		vo.setFolder(folder);
		vo.setTenantId(Tenant.DEFAULT_ID);
		vo.setLanguage("en");
		// Waits for the initial version
		long id = DocumentManager.get().create(content(), vo, documentTransaction()).getDocument().getId();
		return documentDao.findById(id, true);
	}

	private Document createIndexedDocument(Folder folder, String fileName) throws Exception {
		Document document = createDocument(folder, fileName);
		SearchEngine.get().unlock();
		SearchEngine.get().addHit(document, CONTENT);
		document.setIndexingStatus(IndexingStatus.INDEXED);
		documentDao.store(document);
		return documentDao.findById(document.getId(), true);
	}

	private static InputStream content() {
		return new ByteArrayInputStream(CONTENT.getBytes(StandardCharsets.UTF_8));
	}

	private DocumentHistory documentTransaction() {
		DocumentHistory transaction = new DocumentHistory();
		transaction.setSessionId("veto-test");
		transaction.setUser(admin);
		return transaction;
	}

	private FolderHistory folderTransaction(FolderEvent event) {
		FolderHistory transaction = new FolderHistory();
		transaction.setSessionId("veto-test");
		transaction.setUser(admin);
		if (event != null)
			transaction.setEvent(event);
		return transaction;
	}
}
