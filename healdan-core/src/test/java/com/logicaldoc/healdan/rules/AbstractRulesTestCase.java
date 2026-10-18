package com.logicaldoc.healdan.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.LongStream;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.DaoSnapshot;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.IndexSnapshot;
import com.logicaldoc.util.spring.Context;

/**
 * The layout the filing and structure rules are tested on: a Store "Main" with
 * matter 998.1 (top-level folders Documents and Correspondence, a search
 * folder in Documents), the Templates Store with one template, the Default
 * workspace with a folder, an indexed document and a deleted one in
 * Correspondence. Alice (group lawyers) has every permission on the Store, Bob
 * (group readers) can only read it; neither is an admin.
 */
public abstract class AbstractRulesTestCase extends AbstractHealdanTestCase {

	protected static final String CONTENT = "Healdan filing rules";

	protected static final String DOCUMENT_OUTSIDE = "Documents must be filed in a folder inside a matter.";

	protected static final String DOCUMENT_TOP_LEVEL = "Documents can't be saved at the top level of a matter. Choose one of its folders.";

	protected static final String DOCUMENT_IN_SEARCH_FOLDER = "Search folders can't hold documents.";

	protected static final String SHORTCUT = "Shortcuts aren't allowed inside Stores.";

	protected static final String FOLDER_IN_SEARCH_FOLDER = "Search folders can't hold folders.";

	protected static final String FOLDER_IN_STORE = "Folders can't be created directly in a Store. Use New matter.";

	protected static final String TOP_LEVEL = "Only administrators can add, rename, move or delete a matter's top-level folders.";

	protected static final String WORKSPACE_RENAME = "Rename a matter with Edit matter.";

	protected static final String WORKSPACE_DELETE_MOVE = "Only administrators can delete a matter or move it to another Store.";

	protected static final String STORE_TYPE = "Only administrators can create Stores.";

	protected static final String SEARCH_FOLDER_TYPE = "Search folders can only be created with New search folder.";

	protected static final String TEMPLATES = "Only administrators can change workspace templates.";

	protected static final String RESTORE_PERMISSION = "You need the delete permission on this folder to restore it.";

	protected final HealdanFixtures fx = new HealdanFixtures();

	protected JdbcTemplate jdbc;

	protected FolderDAO folderDao;

	protected DocumentDAO documentDao;

	protected User admin;

	/** Alice's group */
	protected Group lawyers;

	/** Every permission on the Store, not an admin */
	protected User alice;

	/** Read only on the Store */
	protected User bob;

	protected Folder root;

	protected Folder defaultWorkspace;

	/** A folder in the Default workspace */
	protected Folder loose;

	protected Folder store;

	protected Matter matter;

	protected Folder workspace;

	protected Folder documents;

	protected Folder correspondence;

	/** A search folder in Documents */
	protected Folder search;

	protected Folder templates;

	/** A template in the Templates Store */
	protected Folder template;

	/** An indexed document in Correspondence */
	protected Document doc;

	/** A deleted document, once in Correspondence */
	protected Document deletedDoc;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		folderDao = FolderDAO.get();
		documentDao = DocumentDAO.get();

		admin = fx.admin();
		lawyers = fx.group("lawyers");
		Group readers = fx.group("readers");
		alice = fx.user("alice", lawyers.getName());
		bob = fx.user("bob", readers.getName());

		root = folderDao.findRoot(Tenant.DEFAULT_ID);
		defaultWorkspace = folderDao.findDefaultWorkspace(Tenant.DEFAULT_ID);
		loose = fx.folder(defaultWorkspace, "Loose");

		store = fx.store("Main");
		fx.grant(store, lawyers, Permission.values());
		fx.grant(store, readers, Permission.READ);
		matter = fx.matter(fx.client(998, "Healdan"), 1, "Healdan Demo", store, "Documents", "Correspondence");
		workspace = folderDao.findById(matter.getFolderId());
		documents = child(workspace, "Documents");
		correspondence = child(workspace, "Correspondence");
		search = fx.searchFolder(documents, "Recent");

		templates = folderDao.findById(HealdanSettings.get().templatesStoreId());
		template = fx.folder(templates, "Standard");

		doc = indexedDocument(correspondence, "doc.txt");
		deletedDoc = fx.upload(correspondence, "deleted.txt", CONTENT, admin);
		documentDao.delete(deletedDoc.getId(), documentHistory(admin));
		awaitAsyncWork();
	}

	@After
	@Override
	public void tearDown() throws IOException {
		try {
			awaitAsyncWork();
		} catch (Exception e) {
			// The database goes anyway
		}
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	/**
	 * Runs an operation that a rule must refuse, and proves that it was refused
	 * by that rule and left the database, the search index, DAO reads and the
	 * file Store as they were
	 *
	 * @param message the rule's message
	 * @param route the operation
	 * @param moreDocIds more documents whose index hits must stay as they are
	 *
	 * @throws Exception if something else fails
	 */
	protected void assertRefused(String message, Route route, long... moreDocIds) throws Exception {
		assertRefused(message, e -> {
			HealdanRuleException rule = ruleIn(e);
			assertNotNull("Refused, but not by a Healdan rule: " + e, rule);
			assertEquals(message, rule.getMessage());
		}, route, moreDocIds);
	}

	/**
	 * As above, for a route that reports the refusal its own way
	 *
	 * @param expected what refuses it, for the failure message
	 * @param refusal checks the exception the route threw
	 * @param route the operation
	 * @param moreDocIds more documents whose index hits must stay as they are
	 *
	 * @throws Exception if something else fails
	 */
	protected void assertRefused(String expected, Refusal refusal, Route route, long... moreDocIds)
			throws Exception {
		awaitAsyncWork();
		// The database first, so that rows a DAO read deletes are caught as a change
		DbSnapshot db = DbSnapshot.take(jdbc);
		IndexSnapshot index = IndexSnapshot.of(
				LongStream.concat(LongStream.of(doc.getId(), deletedDoc.getId()), LongStream.of(moreDocIds)).toArray());
		DaoSnapshot dao = DaoSnapshot.take(jdbc);
		long storeSize = Store.get().getTotalSize();

		try {
			route.run();
			fail("Not refused, expected: " + expected);
		} catch (Exception e) {
			refusal.check(e);
		}

		awaitAsyncWork();
		db.assertUnchanged();
		index.assertUnchanged();
		dao.assertUnchanged();
		assertEquals("files left in the Store", storeSize, Store.get().getTotalSize());
	}

	/**
	 * Runs an operation the rules must allow, and waits for the work CE does in
	 * the background
	 *
	 * @param route the operation
	 *
	 * @throws Exception if the operation fails
	 */
	protected void allowed(Route route) throws Exception {
		route.run();
		awaitAsyncWork();
	}

	/**
	 * @param e an exception
	 * @return the Healdan rule exception it is, or was caused by; null if none
	 */
	protected static HealdanRuleException ruleIn(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause())
			if (t instanceof HealdanRuleException rule)
				return rule;
		return null;
	}

	@FunctionalInterface
	protected interface Route {
		void run() throws Exception;
	}

	@FunctionalInterface
	protected interface Refusal {
		void check(Exception e) throws Exception;
	}

	/**
	 * Waits for the versions CE saves in the background
	 */
	protected static void awaitAsyncWork() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 20000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				fail("Background version saves didn't finish");
			Thread.sleep(50L);
		}
	}

	protected Folder child(Folder parent, String name) throws Exception {
		return folderDao.findByName(parent, name, Tenant.DEFAULT_ID, true).get(0);
	}

	protected Folder reload(Folder folder) throws Exception {
		return folderDao.findById(folder.getId(), true);
	}

	protected Document reload(Document document) throws Exception {
		return documentDao.findById(document.getId(), true);
	}

	/**
	 * @return a value object for a new folder of the given type
	 */
	protected Folder folderVO(String name, int type) {
		Folder vo = new Folder(name);
		vo.setType(type);
		vo.setTenantId(Tenant.DEFAULT_ID);
		return vo;
	}

	protected Folder createFolder(Folder parent, String name, User as) throws Exception {
		return folderDao.create(parent, folderVO(name, Folder.TYPE_DEFAULT), true, folderHistory(as));
	}

	protected Document upload(Folder folder, String fileName, User as) throws Exception {
		return fx.upload(folder, fileName, CONTENT, as);
	}

	protected Document indexedDocument(Folder folder, String fileName) throws Exception {
		Document document = upload(folder, fileName, admin);
		awaitAsyncWork();
		document = reload(document);
		SearchEngine.get().unlock();
		SearchEngine.get().addHit(document, CONTENT);
		document.setIndexingStatus(IndexingStatus.INDEXED);
		documentDao.store(document);
		return reload(document);
	}

	protected Document documentVO(Folder folder, String fileName) {
		Document vo = new Document();
		vo.setFileName(fileName);
		vo.setFolder(folder);
		vo.setTenantId(Tenant.DEFAULT_ID);
		vo.setLanguage("en");
		return vo;
	}

	protected void createDocument(Folder folder, String fileName, User as) throws Exception {
		DocumentManager.get().create(content(), documentVO(folder, fileName), documentHistory(as));
	}

	protected static InputStream content() {
		return new ByteArrayInputStream(CONTENT.getBytes(StandardCharsets.UTF_8));
	}

	protected FolderHistory folderHistory(User as) {
		return fx.folderHistory(as);
	}

	protected DocumentHistory documentHistory(User as) {
		return fx.documentHistory(as);
	}
}
