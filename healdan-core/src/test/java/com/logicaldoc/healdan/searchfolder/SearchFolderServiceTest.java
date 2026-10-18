package com.logicaldoc.healdan.searchfolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule.Kind;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * Spec §5.4: a search folder lists the documents of its matter that changed
 * within its window, newest change first, that the viewer can read.
 * <p>
 * A lean layout, as each document costs over a second: a Store "Main" that
 * Alice (lawyers) may do anything in and Bob (readers) may only read, and in
 * it matter 998.1 with a top-level folder Documents and, as in the demo
 * template, a top-level search folder Recent Work (anyone's changes in the
 * last 30 days).
 */
public class SearchFolderServiceTest extends AbstractHealdanTestCase {

	private static final String PLACE = "Search folders can only be created inside a matter or a workspace template.";

	private static final String TOP_LEVEL = "Only administrators can add, rename, move or delete a matter's top-level folders.";

	private static final String TEMPLATES = "Only administrators can change workspace templates.";

	private static final String FOLDER_IN_SEARCH_FOLDER = "Search folders can't hold folders.";

	private static final String NO_ADD = "You need the add permission on this folder to create a search folder in it.";

	private static final String NO_WRITE = "You need the write permission on this search folder to edit it.";

	private static final String NO_RENAME = "You need the rename permission on this search folder to rename it.";

	private static final String NO_SEARCH_FOLDER = "That search folder no longer exists.";

	private static final String BAD_DAYS = "A search folder's window must be at least 1 day.";

	private static final String SUBCLASS_WITHOUT_CLASS = "A search folder can't filter by Subclass without a Class.";

	private static final String BAD_NAME = "A search folder name can't be empty or longer than 255 characters.";

	private static final String OUTSIDE_PLANE_NAME = "A search folder name can't contain emoji or other characters the database can't store.";

	private static final SearchFolderRule ANYONE_30 = rule(30, false, Kind.ALL, null, null);

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private SearchFolderService service;

	private SearchFolderRuleDAO rules;

	private User admin;

	private Group lawyers;

	private Group readers;

	/** Every permission on the Store */
	private User alice;

	/** Read only on the Store */
	private User bob;

	private Folder store;

	private Client client;

	private Folder workspace;

	private Folder documents;

	/** Top level, anyone's changes in the last 30 days */
	private Folder recent;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		service = SearchFolderService.get();
		rules = SearchFolderRuleDAO.get();

		admin = fx.admin();
		lawyers = fx.group("lawyers");
		readers = fx.group("readers");
		alice = fx.user("alice", lawyers.getName());
		bob = fx.user("bob", readers.getName());

		store = fx.store("Main");
		fx.grant(store, lawyers, Permission.values());
		fx.grant(store, readers, Permission.READ);
		client = fx.client(998, "Healdan");
		Matter matter = fx.matter(client, 1, "Healdan Demo", store, "Documents");
		workspace = FolderDAO.get().findById(matter.getFolderId());
		documents = child(workspace, "Documents");
		recent = service.create(workspace.getId(), "Recent Work", ANYONE_30, admin);
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

	@Test
	public void testWindow() throws Exception {
		Folder week = service.create(documents.getId(), "This week", rule(7, false, Kind.ALL, null, null), alice);
		Document recentDoc = upload(documents, "recent.txt", alice);
		Document oldDoc = upload(documents, "old.txt", alice);
		changedDaysAgo(recentDoc, 6);
		changedDaysAgo(oldDoc, 8);
		assertEquals(List.of(recentDoc.getId()), service.evaluate(week.getId(), alice));

		// A window of 9 days reaches it
		service.update(week.getId(), "This week", rule(9, false, Kind.ALL, null, null), alice);
		assertEquals(List.of(recentDoc.getId(), oldDoc.getId()), service.evaluate(week.getId(), alice));
		service.update(week.getId(), "This week", rule(7, false, Kind.ALL, null, null), alice);

		// Locking isn't a change, though it moves CE's last modified date
		Date modified = reload(oldDoc).getLastModified();
		DocumentManager.get().lock(oldDoc.getId(), DocumentStatus.LOCKED, fx.documentHistory(alice));
		DocumentManager.get().unlock(oldDoc.getId(), fx.documentHistory(alice));
		awaitAsyncWork();
		assertTrue(reload(oldDoc).getLastModified().after(modified));
		assertEquals(List.of(recentDoc.getId()), service.evaluate(week.getId(), alice));

		// Renaming is
		rename(oldDoc, "renamed.txt", alice);
		assertEquals(List.of(oldDoc.getId(), recentDoc.getId()), service.evaluate(week.getId(), alice));
	}

	@Test
	public void testOnlyMine() throws Exception {
		Folder mine = service.create(workspace.getId(), "My Recent Work", rule(30, true, Kind.ALL, null, null),
				admin);
		Document alices = upload(documents, "alice.txt", alice);
		Document bobs = upload(documents, "bob.txt", bob);

		// One search folder, a list for each viewer
		assertEquals(List.of(alices.getId()), service.evaluate(mine.getId(), alice));
		assertEquals(List.of(bobs.getId()), service.evaluate(mine.getId(), bob));

		// Bob's change puts Alice's document on his list, and leaves hers as it was
		rename(alices, "alice-2.txt", bob);
		assertEquals(List.of(alices.getId(), bobs.getId()), service.evaluate(mine.getId(), bob));
		assertEquals(List.of(alices.getId()), service.evaluate(mine.getId(), alice));

		// Anyone's changes
		assertEquals(List.of(alices.getId(), bobs.getId()), service.evaluate(recent.getId(), alice));
	}

	@Test
	public void testKind() throws Exception {
		Document memo = upload(documents, "memo.txt", alice);
		Document mail = upload(documents, "Reply.EML", alice);

		Folder all = service.create(documents.getId(), "All", rule(30, false, Kind.ALL, null, null), alice);
		Folder docsOnly = service.create(documents.getId(), "Documents only",
				rule(30, false, Kind.DOCUMENTS, null, null), alice);
		Folder emailsOnly = service.create(documents.getId(), "Emails only", rule(30, false, Kind.EMAILS, null, null),
				alice);

		assertEquals(List.of(mail.getId(), memo.getId()), service.evaluate(all.getId(), alice));
		assertEquals(List.of(memo.getId()), service.evaluate(docsOnly.getId(), alice));
		assertEquals(List.of(mail.getId()), service.evaluate(emailsOnly.getId(), alice));
	}

	@Test
	public void testClassAndSubclass() throws Exception {
		long setId = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true)
				.getId();
		AttributeOptionDAO options = AttributeOptionDAO.get();
		options.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCCLASS, "Letters"));
		options.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCSUBCLASS, "Sub1", "Letters"));
		options.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCSUBCLASS, "Sub2", "Letters"));

		Document letter = createWith(documents, "letter.txt", alice,
				Map.of(HealdanConstants.ATTR_DOCCLASS, "Letters", HealdanConstants.ATTR_DOCSUBCLASS, "Sub1"));
		// The default Class, Docs
		Document plain = upload(documents, "plain.txt", alice);

		assertEquals(List.of(letter.getId()), evaluateWith(rule(30, false, Kind.ALL, "Letters", null)));
		assertEquals(List.of(letter.getId()), evaluateWith(rule(30, false, Kind.ALL, "Letters", "Sub1")));
		assertEquals(List.of(), evaluateWith(rule(30, false, Kind.ALL, "Letters", "Sub2")));
		assertEquals(List.of(plain.getId()), evaluateWith(rule(30, false, Kind.ALL, "Docs", null)));
		// Blank means no filter
		assertEquals(List.of(plain.getId(), letter.getId()), evaluateWith(rule(30, false, Kind.ALL, " ", "")));
	}

	@Test
	public void testExcludesOtherMattersAndDeleted() throws Exception {
		Matter other = fx.matter(client, 2, "Other", store, "Documents");
		Folder otherWorkspace = FolderDAO.get().findById(other.getFolderId());
		Folder otherRecent = service.create(otherWorkspace.getId(), "Recent Work", ANYONE_30, admin);

		Document kept = upload(documents, "kept.txt", alice);
		Document deleted = upload(documents, "deleted.txt", alice);
		Document moved = upload(documents, "moved.txt", alice);
		DocumentDAO.get().delete(deleted.getId(), fx.documentHistory(alice));
		DocumentManager.get().moveToFolder(reload(moved), child(otherWorkspace, "Documents"),
				fx.documentHistory(alice));
		awaitAsyncWork();

		// Each lists its own matter's documents, where they are now
		assertEquals(List.of(kept.getId()), service.evaluate(recent.getId(), alice));
		assertEquals(List.of(moved.getId()), service.evaluate(otherRecent.getId(), alice));

		// Nothing for what isn't a live search folder
		assertEquals(List.of(), service.evaluate(documents.getId(), alice));
		assertEquals(List.of(), service.evaluate(987654L, alice));
		FolderDAO.get().delete(otherRecent.getId(), fx.folderHistory(admin));
		assertEquals(List.of(), service.evaluate(otherRecent.getId(), alice));
	}

	@Test
	public void testPermissionFilter() throws Exception {
		Folder secured = securedFolder();
		Document open = upload(documents, "open.txt", alice);
		Document secret = upload(secured, "secret.txt", alice);

		assertEquals(List.of(secret.getId(), open.getId()), service.evaluate(recent.getId(), alice));
		assertEquals(List.of(open.getId()), service.evaluate(recent.getId(), bob));

		// The scope is the whole matter, wherever the search folder is; Bob can't read this one at all
		Folder inSecured = service.create(secured.getId(), "Recent", ANYONE_30, alice);
		assertEquals(List.of(secret.getId(), open.getId()), service.evaluate(inSecured.getId(), alice));
		assertEquals(List.of(), service.evaluate(inSecured.getId(), bob));
	}

	@Test
	public void testDocumentOnlyGrantAppears() throws Exception {
		Document minutes = upload(securedFolder(), "minutes.txt", alice);
		assertEquals(List.of(), service.evaluate(recent.getId(), bob));

		Document stored = DocumentDAO.get().findById(minutes.getId(), true);
		DocumentAccessControlEntry ace = new DocumentAccessControlEntry(readers.getId());
		ace.grantPermissions(Set.of(Permission.READ, Permission.DOWNLOAD));
		stored.getAccessControlList().add(ace);
		DocumentHistory history = fx.documentHistory(admin);
		history.setEvent(DocumentEvent.PERMISSION);
		DocumentDAO.get().store(stored, history);
		awaitAsyncWork();

		assertEquals(List.of(minutes.getId()), service.evaluate(recent.getId(), bob));
	}

	@Test
	public void testNewestFirst() throws Exception {
		Document first = upload(documents, "first.txt", alice);
		Document second = upload(documents, "second.txt", alice);
		changedDaysAgo(first, 3);
		changedDaysAgo(second, 2);
		assertEquals(List.of(second.getId(), first.getId()), service.evaluate(recent.getId(), alice));

		// By its latest change: created three days ago, renamed now
		rename(first, "first-2.txt", alice);
		assertEquals(List.of(first.getId(), second.getId()), service.evaluate(recent.getId(), alice));
	}

	@Test
	public void testCopiedFromTemplateKeepsRule() throws Exception {
		Folder template = fx.storeTemplate(store);
		SearchFolderRule custom = rule(14, true, Kind.EMAILS, "Letters", "Sub1");
		Folder inTemplate = service.create(template.getId(), "My letters", custom, admin);
		assertEquals(Optional.of(custom.withFolderId(inTemplate.getId())), rules.find(inTemplate.getId()));

		Matter second = fx.matter(client, 2, "Second", store);
		Folder copy = child(FolderDAO.get().findById(second.getFolderId()), "My letters");
		assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, copy.getType());
		assertEquals(Optional.of(custom.withFolderId(copy.getId())), rules.find(copy.getId()));

		// A template's search folder isn't in a matter, so it lists nothing
		assertEquals(List.of(), service.evaluate(inTemplate.getId(), admin));

		// The copy is the matter's own
		service.update(inTemplate.getId(), "My letters", ANYONE_30, admin);
		assertEquals(Optional.of(custom.withFolderId(copy.getId())), rules.find(copy.getId()));
	}

	@Test
	public void testCannotCreateOutsideMatterOrTemplates() throws Exception {
		Folder defaultWorkspace = FolderDAO.get().findDefaultWorkspace(Tenant.DEFAULT_ID);
		Folder loose = fx.folder(defaultWorkspace, "Loose");
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		Folder template = fx.storeTemplate(store);

		assertRefused(PLACE, () -> create(loose, admin));
		assertRefused(PLACE, () -> create(defaultWorkspace, admin));
		assertRefused(PLACE, () -> create(FolderDAO.get().findRoot(Tenant.DEFAULT_ID), admin));
		assertRefused(PLACE, () -> create(store, admin));
		// Directly in the Templates Store it would be a template
		assertRefused(PLACE, () -> create(templates, admin));
		assertRefused(FOLDER_IN_SEARCH_FOLDER, () -> create(recent, admin));
		assertRefused(TEMPLATES, () -> create(template, alice));
		assertRefused(NO_ADD, () -> create(documents, bob));

		Folder inTemplate = create(template, admin);
		Folder inMatter = create(documents, alice);
		assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, reload(inTemplate).getType());
		assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, reload(inMatter).getType());
		assertEquals(Optional.of(ANYONE_30.withFolderId(inMatter.getId())), rules.find(inMatter.getId()));
	}

	@Test
	public void testTopLevelNeedsAdmin() throws Exception {
		assertRefused(TOP_LEVEL, () -> service.create(workspace.getId(), "Mine", ANYONE_30, alice));

		// Below the top level CE's permissions decide
		create(documents, alice);

		// Renaming a top-level search folder is for admins too, but changing its rule isn't
		assertRefused(TOP_LEVEL, () -> service.update(recent.getId(), "Renamed", ANYONE_30, alice));
		service.update(recent.getId(), "Recent Work", rule(7, false, Kind.ALL, null, null), alice);
		assertEquals(7, rules.find(recent.getId()).get().days());
		service.update(recent.getId(), "Renamed", ANYONE_30, admin);
		assertEquals("Renamed", reload(recent).getName());
	}

	@Test
	public void testTemplateRuleNeedsAdmin() throws Exception {
		Folder inTemplate = service.create(fx.storeTemplate(store).getId(), "Search", ANYONE_30, admin);
		// Alice may write there, as the template grants what the Store grants
		assertTrue(FolderDAO.get().isWriteAllowed(inTemplate.getId(), alice.getId()));

		// Every new matter copies the rule
		assertRefused(TEMPLATES,
				() -> service.update(inTemplate.getId(), "Search", rule(7, false, Kind.ALL, null, null), alice));
		service.update(inTemplate.getId(), "Search", rule(7, false, Kind.ALL, null, null), admin);
		assertEquals(7, rules.find(inTemplate.getId()).get().days());
	}

	@Test
	public void testUpdateChangesOnlyNameAndRule() throws Exception {
		assertRefused(BAD_DAYS, () -> service.create(documents.getId(), "Bad", rule(0, false, Kind.ALL, null, null),
				alice));
		assertRefused(SUBCLASS_WITHOUT_CLASS, () -> service.create(documents.getId(), "Bad",
				rule(30, false, Kind.ALL, null, "Sub1"), alice));
		assertRefused(BAD_NAME, () -> service.create(documents.getId(), " ", ANYONE_30, alice));
		assertRefused(OUTSIDE_PLANE_NAME, () -> service.create(documents.getId(), "Recent \ud83d\udd0d", ANYONE_30, alice));

		Folder search = create(documents, alice);
		assertRefused(BAD_NAME, () -> service.update(search.getId(), null, ANYONE_30, alice));
		assertRefused(BAD_DAYS,
				() -> service.update(search.getId(), "Search", rule(-1, false, Kind.ALL, null, null), alice));
		assertRefused(SUBCLASS_WITHOUT_CLASS,
				() -> service.update(search.getId(), "Search", rule(30, false, Kind.ALL, " ", "Sub1"), alice));
		assertRefused(NO_SEARCH_FOLDER, () -> service.update(documents.getId(), "Documents", ANYONE_30, admin));
		assertRefused(NO_WRITE, () -> service.update(search.getId(), "Search", ANYONE_30, bob));

		// The folder id in the rule given is ignored
		Folder before = reload(search);
		service.update(search.getId(), "Renamed", new SearchFolderRule(987654L, 14, true, Kind.EMAILS, "Letters", null),
				alice);
		Folder after = reload(search);
		assertEquals("Renamed", after.getName());
		assertEquals(before.getSecurityRef(), after.getSecurityRef());
		assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, after.getType());
		assertEquals(Optional.of(new SearchFolderRule(search.getId(), 14, true, Kind.EMAILS, "Letters", null)),
				rules.find(search.getId()));
		assertEquals(Optional.empty(), rules.find(987654L));

		// Write to change the rule; rename as well to change the name
		Folder narrowed = fx.folder(documents, "Narrowed");
		setAcl(narrowed, lawyers, Permission.READ, Permission.WRITE, Permission.ADD);
		Folder limited = create(narrowed, alice);
		service.update(limited.getId(), "Search", rule(7, false, Kind.ALL, null, null), alice);
		assertEquals(7, rules.find(limited.getId()).get().days());
		assertRefused(NO_RENAME, () -> service.update(limited.getId(), "Other", ANYONE_30, alice));
	}

	/*
	 * Helpers
	 */

	private static SearchFolderRule rule(int days, boolean onlyMine, Kind kind, String docClass, String docSubclass) {
		return new SearchFolderRule(0L, days, onlyMine, kind, docClass, docSubclass);
	}

	/**
	 * @return a new search folder named "Search" in the parent, anyone's
	 *         changes in the last 30 days
	 */
	private Folder create(Folder parent, User as) throws Exception {
		return service.create(parent.getId(), "Search", ANYONE_30, as);
	}

	/**
	 * @return what a new search folder with the rule in Documents lists for
	 *         Alice
	 */
	private List<Long> evaluateWith(SearchFolderRule rule) throws Exception {
		return service.evaluate(service.create(documents.getId(), "Search", rule, alice).getId(), alice);
	}

	/**
	 * @return a top-level folder only the lawyers can use
	 */
	private Folder securedFolder() throws Exception {
		Folder secured = fx.folder(workspace, "Secured");
		setAcl(secured, lawyers, Permission.values());
		return secured;
	}

	/**
	 * Gives the folder an ACL of its own granting the group exactly these
	 * permissions, as CE's Security tab does
	 */
	private void setAcl(Folder folder, Group group, Permission... permissions) throws Exception {
		Folder current = reload(folder);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		FolderAccessControlEntry entry = new FolderAccessControlEntry(group.getId());
		entry.grantPermissions(Set.of(permissions));
		current.addAccessControlEntry(entry);
		FolderHistory history = fx.folderHistory(admin);
		history.setEvent(FolderEvent.PERMISSION);
		FolderDAO.get().store(current, history);
	}

	/**
	 * Dates every history entry of the document so many days back, as if all
	 * its changes were made then
	 */
	private void changedDaysAgo(Document document, int days) {
		jdbc.update("update ld_history set ld_date = ? where ld_docid = ?",
				Timestamp.from(Instant.now().minus(Duration.ofDays(days))), document.getId());
	}

	private Document upload(Folder folder, String fileName, User as) throws Exception {
		Document document = fx.upload(folder, fileName, "Healdan search folders", as);
		awaitAsyncWork();
		return document;
	}

	private Document createWith(Folder folder, String fileName, User as, Map<String, Object> values)
			throws Exception {
		Document vo = new Document();
		vo.setFileName(fileName);
		vo.setFolder(folder);
		vo.setTenantId(Tenant.DEFAULT_ID);
		vo.setLanguage("en");
		values.forEach(vo::setValue);
		Document created = DocumentManager.get().create(
				new ByteArrayInputStream("Healdan search folders".getBytes(StandardCharsets.UTF_8)), vo,
				fx.documentHistory(as)).getDocument();
		awaitAsyncWork();
		return created;
	}

	private void rename(Document document, String name, User as) throws Exception {
		DocumentManager.get().rename(document.getId(), name, fx.documentHistory(as));
		awaitAsyncWork();
	}

	private Folder child(Folder parent, String name) throws Exception {
		return FolderDAO.get().findByName(parent, name, Tenant.DEFAULT_ID, true).get(0);
	}

	private static Folder reload(Folder folder) throws Exception {
		return FolderDAO.get().findById(folder.getId(), true);
	}

	private static Document reload(Document document) throws Exception {
		return DocumentDAO.get().findById(document.getId(), true);
	}

	/**
	 * Runs an operation a rule must refuse, and proves that it was refused by
	 * that rule and left the database as it was
	 */
	private void assertRefused(String message, Route route) throws Exception {
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();
		try {
			route.run();
			fail("Not refused, expected: " + message);
		} catch (Exception e) {
			HealdanRuleException rule = null;
			for (Throwable t = e; t != null && rule == null; t = t.getCause())
				if (t instanceof HealdanRuleException found)
					rule = found;
			assertNotNull("Refused, but not by a Healdan rule: " + e, rule);
			assertEquals(message, rule.getMessage());
		}
		before.assertUnchanged();
	}

	@FunctionalInterface
	private interface Route {
		void run() throws Exception;
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
}
