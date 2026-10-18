package com.logicaldoc.healdan.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
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
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * Spec §8.2: My Matters lists the user's CE folder bookmarks on matter
 * workspaces; Recent Matters lists the matters where the user created or
 * changed a document in the last 30 days (the change events of §5.4), newest
 * first, at most 20. Both list only live matters whose workspace the user can
 * read.
 * <p>
 * A lean layout, as a document costs over a second: a Store "Main" where
 * lawyers (Alice and Bob) and partners (nobody) may do anything, and client
 * 998. Most matters are bare, which is all both lists need: a live folder
 * directly in the Store with an {@code hd_matter} row. Recent Matters reads
 * CE's document history, which the tests mostly write with JDBC, where a real
 * change would only cost time.
 */
public class NavigationTest extends AbstractHealdanTestCase {

	private static final String NOT_A_MATTER = "Only a matter can be added to My Matters.";

	private static final String NO_READ = "You need the read permission on this matter to add it to My Matters.";

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private MyMatters myMatters;

	private RecentMatters recentMatters;

	private User admin;

	private Group lawyers;

	private Group partners;

	/** A lawyer */
	private User alice;

	/** A lawyer */
	private User bob;

	private Folder store;

	private Client client;

	/** Now, to the second, so the dates written read back the same */
	private Instant now;

	/** The id of the next history entry written, above any CE gives in a test */
	private long nextHistoryId = 1_000_000L;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		myMatters = MyMatters.get();
		recentMatters = RecentMatters.get();
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		admin = fx.admin();
		lawyers = fx.group("lawyers");
		partners = fx.group("partners");
		alice = fx.user("alice", lawyers.getName());
		bob = fx.user("bob", lawyers.getName());
		store = fx.store("Main");
		fx.grant(store, lawyers, Permission.values());
		fx.grant(store, partners, Permission.values());
		client = fx.client(998, "Healdan");
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
	public void testMyMattersOnlyWorkspaces() throws Exception {
		// A matter as an admin creates one, and two bare ones
		Matter demo = fx.matter(client, 1, "Healdan Demo", store, "Documents");
		Matter second = bareMatter(2, "Second");
		Matter tenth = bareMatter(10, "Tenth");

		myMatters.add(alice.getId(), tenth.getFolderId());
		myMatters.add(alice.getId(), demo.getFolderId());
		myMatters.add(alice.getId(), second.getFolderId());

		// By display, its numbers compared as numbers
		List<MatterEntry> entries = myMatters.list(alice.getId());
		assertEquals(List.of("998.1 \u2013 Healdan Demo", "998.2 \u2013 Second", "998.10 \u2013 Tenth"),
				displays(entries));
		assertEquals(new MatterEntry(demo.getId(), demo.getFolderId(), "998.1 \u2013 Healdan Demo", null),
				entries.get(0));

		// Each is a CE folder bookmark, which CE's Bookmarks section lists too (plan, call 9)
		Bookmark bookmark = BookmarkDAO.get().findByUserIdAndFolderId(alice.getId(), demo.getFolderId());
		assertEquals(Bookmark.Type.FOLDER, bookmark.getType());
		assertEquals("998.1 \u2013 Healdan Demo", bookmark.getTitle());

		// Anything but a live matter's workspace is refused: the Store, a folder in a matter,
		// a workspace without its matter row, a folder that doesn't exist
		Folder documents = FolderDAO.get()
				.findByName(workspace(demo), "Documents", Tenant.DEFAULT_ID, true).get(0);
		Folder unnumbered = HealdanOperation.run(HealdanOperation.Kind.MATTER_CREATE,
				() -> fx.folder(store, "Being created"));
		for (long folderId : List.of(store.getId(), documents.getId(), unnumbered.getId(), 987654321L))
			assertRefused(NOT_A_MATTER, () -> myMatters.add(alice.getId(), folderId));
		// The Default workspace, the Templates Store and a template, by the admin, who can read them
		for (long folderId : List.of(Folder.DEFAULTWORKSPACEID, HealdanSettings.get().templatesStoreId(),
				fx.storeTemplate(store).getId()))
			assertRefused(NOT_A_MATTER, () -> myMatters.add(admin.getId(), folderId));
		// A matter Alice can't read
		Matter closed = bareMatter(3, "Closed");
		restrictTo(closed, partners);
		assertRefused(NO_READ, () -> myMatters.add(alice.getId(), closed.getFolderId()));
		assertEquals(3, countBookmarks(alice, Bookmark.Type.FOLDER));
		assertEquals(0, countBookmarks(admin, Bookmark.Type.FOLDER));

		// A folder bookmarked in CE's Bookmarks section is in My Matters if it is a matter's
		// workspace the user can read; CE checks no permission
		ceBookmark(bob, Bookmark.Type.FOLDER, second.getFolderId());
		ceBookmark(bob, Bookmark.Type.FOLDER, documents.getId());
		ceBookmark(bob, Bookmark.Type.FOLDER, store.getId());
		ceBookmark(bob, Bookmark.Type.FOLDER, closed.getFolderId());
		assertEquals(List.of("998.2 \u2013 Second"), displays(myMatters.list(bob.getId())));

		// Removing takes away that user's bookmark only, and does nothing when there is none
		myMatters.remove(alice.getId(), second.getFolderId());
		myMatters.remove(alice.getId(), second.getFolderId());
		myMatters.remove(alice.getId(), documents.getId());
		assertNull(BookmarkDAO.get().findByUserIdAndFolderId(alice.getId(), second.getFolderId()));
		assertEquals(List.of("998.1 \u2013 Healdan Demo", "998.10 \u2013 Tenth"),
				displays(myMatters.list(alice.getId())));
		assertEquals(List.of("998.2 \u2013 Second"), displays(myMatters.list(bob.getId())));
	}

	@Test
	public void testMyMattersHidesDeleted() throws Exception {
		Matter kept = bareMatter(1, "Kept");
		Matter binned = bareMatter(2, "Binned");
		Matter unnumbered = bareMatter(3, "Unnumbered");
		Matter closed = bareMatter(4, "Closed");
		for (Matter matter : List.of(kept, binned, unnumbered, closed))
			myMatters.add(alice.getId(), matter.getFolderId());
		assertEquals(4, myMatters.list(alice.getId()).size());

		// Its workspace deleted, its matter row deleted, or no longer readable
		FolderDAO.get().deleteTree(binned.getFolderId(), fx.folderHistory(admin));
		MatterDAO.get().delete(unnumbered.getId(), Matter.DELETED_CODE_DEFAULT);
		restrictTo(closed, partners);
		assertEquals(List.of("998.1 \u2013 Kept"), displays(myMatters.list(alice.getId())));
		// The list hides them; their bookmarks stay, as CE keeps a deleted folder's bookmarks
		assertEquals(4, countBookmarks(alice, Bookmark.Type.FOLDER));

		// The admin can still read the closed one
		ceBookmark(admin, Bookmark.Type.FOLDER, closed.getFolderId());
		assertEquals(List.of("998.4 \u2013 Closed"), displays(myMatters.list(admin.getId())));

		// And none can be added again
		assertRefused(NOT_A_MATTER, () -> myMatters.add(bob.getId(), binned.getFolderId()));
		assertRefused(NOT_A_MATTER, () -> myMatters.add(bob.getId(), unnumbered.getFolderId()));
		assertRefused(NO_READ, () -> myMatters.add(bob.getId(), closed.getFolderId()));
		assertEquals(0, countBookmarks(bob, Bookmark.Type.FOLDER));
	}

	@Test
	public void testAddTwiceOnce() throws Exception {
		Matter demo = bareMatter(1, "Healdan Demo");
		long workspaceId = demo.getFolderId();

		myMatters.add(alice.getId(), workspaceId);
		myMatters.add(alice.getId(), workspaceId);
		assertEquals(1, countBookmarks(alice, Bookmark.Type.FOLDER));
		assertEquals(1, myMatters.list(alice.getId()).size());

		// A document bookmark whose document id is the workspace's id isn't a folder bookmark,
		// which CE's addBookmarks took it for
		ceBookmark(bob, Bookmark.Type.DOCUMENT, workspaceId);
		assertEquals(List.of(), myMatters.list(bob.getId()));
		myMatters.add(bob.getId(), workspaceId);
		assertEquals(1, countBookmarks(bob, Bookmark.Type.FOLDER));
		assertEquals(List.of("998.1 \u2013 Healdan Demo"), displays(myMatters.list(bob.getId())));

		// Two folder bookmarks on it, as CE made before its fix, show once, and removing takes both
		ceBookmark(bob, Bookmark.Type.FOLDER, workspaceId);
		assertEquals(1, myMatters.list(bob.getId()).size());
		myMatters.add(bob.getId(), workspaceId);
		assertEquals(2, countBookmarks(bob, Bookmark.Type.FOLDER));
		myMatters.remove(bob.getId(), workspaceId);
		assertEquals(0, countBookmarks(bob, Bookmark.Type.FOLDER));
		assertEquals(List.of(), myMatters.list(bob.getId()));
		// Bob's document bookmark and Alice's folder bookmark stay
		assertEquals(1, countBookmarks(bob, Bookmark.Type.DOCUMENT));
		assertEquals(1, countBookmarks(alice, Bookmark.Type.FOLDER));
	}

	@Test
	public void testRecentWindowCapAndOrder() throws Exception {
		List<Matter> matters = new ArrayList<>();
		for (int n = 1; n <= 26; n++)
			matters.add(bareMatter(n, "Matter " + n));

		// 30 days by default, and a setting: matter 26 was changed 31 days ago
		Instant longAgo = now.minus(Duration.ofDays(31));
		recordEvent(alice, matters.get(25).getFolderId(), DocumentEvent.CHANGED, longAgo);
		recordEvent(alice, matters.get(0).getFolderId(), DocumentEvent.STORED, now.minus(Duration.ofHours(1)));
		assertEquals(List.of("998.1 \u2013 Matter 1"), displays(recentMatters.list(alice.getId())));
		HealdanSettings.get().set(HealdanSettings.RECENT_MATTERS_DAYS, "32");
		List<MatterEntry> wider = recentMatters.list(alice.getId());
		assertEquals(List.of("998.1 \u2013 Matter 1", "998.26 \u2013 Matter 26"), displays(wider));
		assertEquals(Date.from(longAgo), wider.get(1).lastChange());
		HealdanSettings.get().set(HealdanSettings.RECENT_MATTERS_DAYS, "30");

		// 25 matters touched: matter n changed n hours ago, by each change event in turn
		List<DocumentEvent> changes = List.of(DocumentEvent.STORED, DocumentEvent.CHECKEDIN, DocumentEvent.CHANGED,
				DocumentEvent.RENAMED, DocumentEvent.MOVED);
		for (int n = 2; n <= 25; n++)
			recordEvent(alice, matters.get(n - 1).getFolderId(), changes.get(n % changes.size()),
					now.minus(Duration.ofHours(n)));
		// A matter's latest change anywhere in it counts: matter 25 is the newest by one in a folder of it
		Matter newest = matters.get(24);
		Folder inside = fx.folder(workspace(newest), "Documents");
		Instant halfHourAgo = now.minus(Duration.ofMinutes(30));
		recordEvent(alice, inside.getId(), DocumentEvent.CHECKEDIN, halfHourAgo);

		// The 20 changed last, newest first
		List<MatterEntry> recent = recentMatters.list(alice.getId());
		List<String> expected = new ArrayList<>(List.of("998.25 \u2013 Matter 25"));
		for (int n = 1; n <= 19; n++)
			expected.add("998.%d \u2013 Matter %d".formatted(n, n));
		assertEquals(expected, displays(recent));
		assertEquals(new MatterEntry(newest.getId(), newest.getFolderId(), "998.25 \u2013 Matter 25",
				Date.from(halfHourAgo)), recent.get(0));
		assertEquals(Date.from(now.minus(Duration.ofHours(1))), recent.get(1).lastChange());
		assertEquals(Date.from(now.minus(Duration.ofHours(19))), recent.get(19).lastChange());
	}

	@Test
	public void testRecentIgnoresOtherUsersAndViews() throws Exception {
		Matter mine = bareMatter(1, "Mine");
		Matter bobs = bareMatter(2, "Bob's");
		Matter lookedAt = bareMatter(3, "Looked at");
		Folder documents = fx.folder(workspace(mine), "Documents");

		// Alice uploads into her matter, and CE writes the history entry
		Document brief = fx.upload(documents, "brief.txt", "Healdan recent matters", alice);
		awaitAsyncWork();
		// Bob changes his
		Instant fiveMinutesAgo = now.minus(Duration.ofMinutes(5));
		recordEvent(bob, bobs.getFolderId(), DocumentEvent.CHANGED, fiveMinutesAgo);
		// Alice does what isn't a change in the third
		for (DocumentEvent event : List.of(DocumentEvent.VIEWED, DocumentEvent.DOWNLOADED, DocumentEvent.CHECKEDOUT,
				DocumentEvent.LOCKED, DocumentEvent.UNLOCKED, DocumentEvent.PERMISSION, DocumentEvent.DELETED))
			recordEvent(alice, lookedAt.getFolderId(), event, fiveMinutesAgo);
		// and changes a document outside any matter
		recordEvent(alice, Folder.DEFAULTWORKSPACEID, DocumentEvent.CHANGED, fiveMinutesAgo);

		List<MatterEntry> alices = recentMatters.list(alice.getId());
		assertEquals(List.of("998.1 \u2013 Mine"), displays(alices));
		Timestamp stored = jdbc.queryForObject(
				"select max(ld_date) from ld_history where ld_docid = ? and ld_event = ? and ld_userid = ?",
				Timestamp.class, brief.getId(), DocumentEvent.STORED.toKey(), alice.getId());
		assertNotNull(stored);
		assertEquals(stored.getTime(), alices.get(0).lastChange().getTime());
		assertEquals(List.of("998.2 \u2013 Bob's"), displays(recentMatters.list(bob.getId())));
		assertEquals(List.of(), recentMatters.list(admin.getId()));
	}

	@Test
	public void testRecentHidesUnreadableWorkspace() throws Exception {
		Matter open = bareMatter(1, "Open");
		Matter closed = bareMatter(2, "Closed");
		restrictTo(closed, partners);
		Folder documents = fx.folder(workspace(closed), "Documents");
		// A document there grants Alice what its folder gives partners, and READ and WRITE
		Document doc = fx.upload(documents, "for-alice.txt", "Healdan recent matters", admin);
		awaitAsyncWork();
		grantDocument(doc, alice, Permission.READ, Permission.WRITE);
		assertFalse(FolderDAO.get().isReadAllowed(closed.getFolderId(), alice.getId()));
		assertFalse(FolderDAO.get().isReadAllowed(documents.getId(), alice.getId()));
		assertTrue(DocumentDAO.get().isWriteAllowed(doc.getId(), alice.getId()));

		// Alice changes that document, and earlier one in the open matter
		recordEvent(alice, documents.getId(), DocumentEvent.CHANGED, now.minus(Duration.ofMinutes(5)));
		recordEvent(alice, open.getFolderId(), DocumentEvent.STORED, now.minus(Duration.ofHours(2)));
		assertEquals(List.of("998.1 \u2013 Open"), displays(recentMatters.list(alice.getId())));

		// Once she may read the matter, it is there
		restrictTo(closed, partners, lawyers);
		assertEquals(List.of("998.2 \u2013 Closed", "998.1 \u2013 Open"),
				displays(recentMatters.list(alice.getId())));
	}

	/**
	 * @return a matter as both lists see one, cheaper than MatterService's: a
	 *         live folder directly in the Store, named as the matter, that
	 *         inherits the Store, with an {@code hd_matter} row
	 */
	private Matter bareMatter(long number, String name) throws Exception {
		Folder workspace = HealdanOperation.run(HealdanOperation.Kind.MATTER_CREATE,
				() -> fx.folder(store, MatterDisplay.format(client.getNumber(), number, name)));
		Matter matter = new Matter();
		matter.setClientId(client.getId());
		matter.setNumber(number);
		matter.setName(name);
		matter.setFolderId(workspace.getId());
		matter.setOwnerId(admin.getId());
		MatterDAO.get().store(matter);
		return matter;
	}

	private static Folder workspace(Matter matter) throws Exception {
		return FolderDAO.get().findById(matter.getFolderId());
	}

	/**
	 * Gives the matter's workspace an ACL of its own, as CE's Security tab
	 * does: every permission to these groups and nothing to anyone else
	 */
	private void restrictTo(Matter matter, Group... groups) throws Exception {
		Folder current = FolderDAO.get().findById(matter.getFolderId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		for (Group group : groups) {
			FolderAccessControlEntry entry = new FolderAccessControlEntry(group.getId());
			entry.grantPermissions(Set.of(Permission.values()));
			current.addAccessControlEntry(entry);
		}
		FolderHistory history = fx.folderHistory(admin);
		history.setEvent(FolderEvent.PERMISSION);
		FolderDAO.get().store(current, history);
	}

	/**
	 * Gives the document an ACL of its own, as its Security tab saves it: what
	 * its folder grants, and these permissions to the user
	 */
	private void grantDocument(Document document, User user, Permission... permissions) throws Exception {
		Document current = DocumentDAO.get().findById(document.getId(), true);
		for (Map.Entry<Long, Set<Permission>> entry : AclMath.effective(current.getFolder().getId()).entrySet()) {
			DocumentAccessControlEntry ace = new DocumentAccessControlEntry(entry.getKey());
			ace.grantPermissions(entry.getValue());
			current.addAccessControlEntry(ace);
		}
		DocumentAccessControlEntry grant = new DocumentAccessControlEntry(user.getUserGroup().getId());
		grant.grantPermissions(Set.of(permissions));
		current.addAccessControlEntry(grant);
		DocumentHistory history = fx.documentHistory(admin);
		history.setEvent(DocumentEvent.PERMISSION);
		DocumentDAO.get().store(current, history);
		awaitAsyncWork();
	}

	/**
	 * Writes a document history entry, as CE does when the user acts on a
	 * document in that folder
	 */
	private void recordEvent(User user, long folderId, DocumentEvent event, Instant when) {
		Timestamp date = Timestamp.from(when);
		jdbc.update("""
				insert into ld_history (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,
				                        ld_tenantid, ld_folderid, ld_userid, ld_username, ld_date, ld_event,
				                        ld_notified)
				values (?, ?, ?, 1, 0, ?, ?, ?, ?, ?, ?, 0)
				""", nextHistoryId++, date, date, Tenant.DEFAULT_ID, folderId, user.getId(), user.getUsername(), date,
				event.toKey());
	}

	/**
	 * Bookmarks a folder or a document as CE's Bookmarks section does
	 */
	private static void ceBookmark(User user, Bookmark.Type type, long targetId) throws Exception {
		Bookmark bookmark = new Bookmark();
		bookmark.setTenantId(Tenant.DEFAULT_ID);
		bookmark.setUserId(user.getId());
		bookmark.setType(type);
		bookmark.setTargetId(targetId);
		bookmark.setTitle("Bookmark " + targetId);
		BookmarkDAO.get().store(bookmark);
	}

	/**
	 * @return how many live bookmarks of that type the user has
	 */
	private int countBookmarks(User user, Bookmark.Type type) {
		return jdbc.queryForObject(
				"select count(*) from ld_bookmark where ld_deleted = 0 and ld_userid = ? and ld_type = ?",
				Integer.class, user.getId(), type.ordinal());
	}

	private static List<String> displays(List<MatterEntry> entries) {
		return entries.stream().map(MatterEntry::display).toList();
	}

	/**
	 * Proves that adding was refused by My Matters, with that message
	 */
	private static void assertRefused(String message, Route route) {
		HealdanRuleException e = assertThrows(HealdanRuleException.class, route::run);
		assertEquals(message, e.getMessage());
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
