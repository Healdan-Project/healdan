package com.logicaldoc.healdan.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.navigation.RecentFolders.RecentFolder;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * Spec 6.2: recent folders are the folders inside matters where the user
 * changed a document lately, newest first. History entries are written with
 * JDBC, as a real change costs over a second.
 */
public class RecentFoldersTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private User alice;

	private User bob;

	private Group lawyers;

	private Folder documents;

	private Folder sub;

	private Matter matter;

	private Instant now;

	private long nextHistoryId = 1_000_000L;

	@Before
	public void createLayout() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		lawyers = fx.group("lawyers");
		alice = fx.user("alice", lawyers.getName());
		bob = fx.user("bob", lawyers.getName());
		Folder store = fx.store("Main");
		fx.grant(store, lawyers, Permission.values());
		Client client = fx.client(998, "Healdan");
		matter = fx.matter(client, 1, "Demo", store, "Documents");
		documents = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		sub = fx.folder(documents, "Sub");
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
	public void testNewestFirst() throws Exception {
		record(alice, documents.getId(), DocumentEvent.STORED, now.minus(Duration.ofHours(2)));
		record(alice, sub.getId(), DocumentEvent.CHECKEDIN, now.minus(Duration.ofHours(1)));
		// Older changes in the same folder don't count twice
		record(alice, sub.getId(), DocumentEvent.STORED, now.minus(Duration.ofHours(3)));

		List<RecentFolder> recent = RecentFolders.get().list(alice.getId());

		assertEquals(List.of(sub.getId(), documents.getId()), recent.stream().map(RecentFolder::folderId).toList());
		assertEquals(now.minus(Duration.ofHours(1)).toEpochMilli(), recent.get(0).lastChange().getTime());
	}

	@Test
	public void testWindowAndOtherKindsOfFolder() throws Exception {
		record(alice, documents.getId(), DocumentEvent.STORED, now.minus(Duration.ofDays(31)));
		// Not changes
		record(alice, sub.getId(), DocumentEvent.DOWNLOADED, now.minus(Duration.ofHours(1)));
		// Not inside a matter: the workspace itself, and the Default workspace
		record(alice, matter.getFolderId(), DocumentEvent.STORED, now.minus(Duration.ofHours(1)));
		record(alice, Folder.DEFAULTWORKSPACEID, DocumentEvent.STORED, now.minus(Duration.ofHours(1)));
		assertEquals(List.of(), RecentFolders.get().list(alice.getId()));

		HealdanSettings.get().set(HealdanSettings.RECENT_MATTERS_DAYS, "32");
		assertEquals(List.of(documents.getId()),
				RecentFolders.get().list(alice.getId()).stream().map(RecentFolder::folderId).toList());
		HealdanSettings.get().set(HealdanSettings.RECENT_MATTERS_DAYS, "30");
	}

	@Test
	public void testOtherUsersAndDeletedFolders() throws Exception {
		record(bob, sub.getId(), DocumentEvent.STORED, now.minus(Duration.ofHours(1)));
		record(alice, documents.getId(), DocumentEvent.STORED, now.minus(Duration.ofHours(1)));
		assertEquals(List.of(documents.getId()),
				RecentFolders.get().list(alice.getId()).stream().map(RecentFolder::folderId).toList());
		assertEquals(List.of(sub.getId()),
				RecentFolders.get().list(bob.getId()).stream().map(RecentFolder::folderId).toList());

		FolderDAO.get().delete(sub.getId(), fx.folderHistory(fx.admin()));
		assertEquals(List.of(), RecentFolders.get().list(bob.getId()));
		assertEquals(List.of(documents.getId()),
				RecentFolders.get().list(alice.getId()).stream().map(RecentFolder::folderId).toList());
	}

	@Test
	public void testFolderNoLongerReadable() throws Exception {
		record(alice, sub.getId(), DocumentEvent.STORED, now.minus(Duration.ofHours(1)));
		record(alice, documents.getId(), DocumentEvent.STORED, now.minus(Duration.ofHours(2)));
		assertEquals(2, RecentFolders.get().list(alice.getId()).size());

		// Sub gets an ACL of its own that excludes the lawyers
		Folder current = FolderDAO.get().findById(sub.getId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		FolderAccessControlEntry entry = new FolderAccessControlEntry(fx.admin().getUserGroup().getId());
		entry.grantPermissions(Set.of(Permission.values()));
		current.addAccessControlEntry(entry);
		FolderHistory history = fx.folderHistory(fx.admin());
		history.setEvent(FolderEvent.PERMISSION);
		FolderDAO.get().store(current, history);

		assertEquals(List.of(documents.getId()),
				RecentFolders.get().list(alice.getId()).stream().map(RecentFolder::folderId).toList());
	}

	private void record(User user, long folderId, DocumentEvent event, Instant when) {
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
	 * Waits for the versions CE saves in the background
	 */
	private static void awaitAsyncWork() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 120000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				fail("Background version saves didn't finish");
			Thread.sleep(50L);
		}
	}
}
