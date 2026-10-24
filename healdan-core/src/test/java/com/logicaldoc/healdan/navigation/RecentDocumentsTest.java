package com.logicaldoc.healdan.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
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
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.navigation.RecentDocuments.RecentDocument;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.util.spring.Context;

/**
 * Spec 6.4: recent documents are the ones the user filed, changed, checked out
 * or downloaded lately, that the user can still read, newest first.
 */
public class RecentDocumentsTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private User alice;

	private Group lawyers;

	private Folder documents;

	private Matter matter;

	private Instant now;

	private long nextHistoryId = 1_000_000L;

	@Before
	public void createLayout() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		lawyers = fx.group("lawyers");
		alice = fx.user("alice", lawyers.getName());
		Folder store = fx.store("Main");
		fx.grant(store, lawyers, Permission.values());
		Client client = fx.client(998, "Healdan");
		matter = fx.matter(client, 1, "Demo", store, "Documents");
		documents = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
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

	private List<Long> ids(User user) throws Exception {
		return RecentDocuments.get().list(user.getId()).stream().map(RecentDocument::docId).toList();
	}

	/** A document of Alice's with no history, so each test writes exactly the events it wants */
	private Document bare(String name) throws Exception {
		Document doc = fx.upload(documents, name, "Healdan " + name, alice);
		awaitAsyncWork();
		jdbc.update("delete from ld_history where ld_docid = ?", doc.getId());
		return doc;
	}

	@Test
	public void testEachKindOfEventNewestFirst() throws Exception {
		Document filed = bare("filed.txt");
		Document changed = bare("changed.txt");
		Document out = bare("out.txt");
		Document downloaded = bare("downloaded.txt");
		Document viewed = bare("viewed.txt");
		record(alice, filed, DocumentEvent.STORED, now.minus(Duration.ofHours(5)));
		record(alice, changed, DocumentEvent.CHECKEDIN, now.minus(Duration.ofHours(4)));
		record(alice, out, DocumentEvent.CHECKEDOUT, now.minus(Duration.ofHours(3)));
		record(alice, downloaded, DocumentEvent.DOWNLOADED, now.minus(Duration.ofHours(1)));
		// Not a way of working with a document
		record(alice, viewed, DocumentEvent.VIEWED, now.minus(Duration.ofHours(2)));
		// An older event of the same document doesn't list it twice
		record(alice, filed, DocumentEvent.STORED, now.minus(Duration.ofHours(9)));

		List<RecentDocument> list = RecentDocuments.get().list(alice.getId());

		assertEquals(List.of(downloaded.getId(), out.getId(), changed.getId(), filed.getId()),
				list.stream().map(RecentDocument::docId).toList());
		assertEquals(now.minus(Duration.ofHours(1)).toEpochMilli(), list.get(0).lastAccess().getTime());
	}

	@Test
	public void testRealCheckoutAndCheckin() throws Exception {
		Document doc = bare("real.txt");
		DocumentHistory out = fx.documentHistory(alice);
		out.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(doc.getId(), out);
		assertEquals(List.of(doc.getId()), ids(alice));

		jdbc.update("delete from ld_history where ld_docid = ?", doc.getId());
		assertEquals(List.of(), ids(alice));
		DocumentHistory in = fx.documentHistory(alice);
		in.setEvent(DocumentEvent.CHECKEDIN);
		DocumentManager.get().checkin(doc.getId(), new ByteArrayInputStream("new".getBytes()), "real.txt", false, null,
				in);
		awaitAsyncWork();
		assertEquals(List.of(doc.getId()), ids(alice));
	}

	@Test
	public void testWindowOtherUsersAndDeleted() throws Exception {
		User bob = fx.user("bob", lawyers.getName());
		Document old = bare("old.txt");
		Document theirs = bare("theirs.txt");
		Document gone = bare("gone.txt");
		record(alice, old, DocumentEvent.STORED, now.minus(Duration.ofDays(31)));
		record(bob, theirs, DocumentEvent.STORED, now.minus(Duration.ofHours(1)));
		record(alice, gone, DocumentEvent.STORED, now.minus(Duration.ofHours(1)));
		DocumentDAO.get().delete(gone.getId(), fx.documentHistory(alice));

		assertEquals(List.of(), ids(alice));
		assertEquals(List.of(theirs.getId()), ids(bob));
	}

	@Test
	public void testUnreadableDocumentsLeftOut() throws Exception {
		Folder secret = fx.folder(documents, "Secret");
		Document open = bare("open.txt");
		Document hidden = fx.upload(secret, "hidden.txt", "Healdan hidden", fx.admin());
		awaitAsyncWork();
		jdbc.update("delete from ld_history where ld_docid = ?", hidden.getId());
		record(alice, open, DocumentEvent.STORED, now.minus(Duration.ofHours(2)));
		record(alice, hidden, DocumentEvent.STORED, now.minus(Duration.ofHours(1)));
		assertEquals(List.of(hidden.getId(), open.getId()), ids(alice));

		Folder current = FolderDAO.get().findById(secret.getId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		FolderAccessControlEntry entry = new FolderAccessControlEntry(fx.admin().getUserGroup().getId());
		entry.grantPermissions(Set.of(Permission.values()));
		current.addAccessControlEntry(entry);
		FolderHistory history = fx.folderHistory(fx.admin());
		history.setEvent(FolderEvent.PERMISSION);
		FolderDAO.get().store(current, history);

		assertEquals(List.of(open.getId()), ids(alice));
	}

	@Test
	public void testAtMostFifty() throws Exception {
		List<Document> docs = new ArrayList<>();
		for (int n = 0; n < RecentDocuments.MAX_ENTRIES + 1; n++) {
			Document doc = fx.upload(documents, "d%d.txt".formatted(n), "Healdan doc " + n, alice);
			docs.add(doc);
		}
		awaitAsyncWork();
		jdbc.update("delete from ld_history where ld_userid = ?", alice.getId());
		for (int n = 0; n < docs.size(); n++)
			record(alice, docs.get(n), DocumentEvent.STORED, now.minus(Duration.ofMinutes(docs.size() - n)));

		List<Long> ids = ids(alice);

		assertEquals(RecentDocuments.MAX_ENTRIES, ids.size());
		// The newest is the last, the oldest is dropped
		assertEquals(docs.get(docs.size() - 1).getId(), ids.get(0).longValue());
		assertTrue(!ids.contains(docs.get(0).getId()));

		// A filter applies before the cap: leaving the newest out brings the oldest back
		long newest = docs.get(docs.size() - 1).getId();
		List<Long> filtered = RecentDocuments.get().list(alice.getId(), d -> d.getId() != newest).stream()
				.map(RecentDocument::docId).toList();
		assertEquals(RecentDocuments.MAX_ENTRIES, filtered.size());
		assertTrue(filtered.contains(docs.get(0).getId()));
	}

	private void record(User user, Document doc, DocumentEvent event, Instant when) {
		Timestamp date = Timestamp.from(when);
		jdbc.update("""
				insert into ld_history (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,
				                        ld_tenantid, ld_docid, ld_folderid, ld_userid, ld_username, ld_date,
				                        ld_event, ld_notified)
				values (?, ?, ?, 1, 0, ?, ?, ?, ?, ?, ?, ?, 0)
				""", nextHistoryId++, date, date, Tenant.DEFAULT_ID, doc.getId(), doc.getFolder().getId(),
				user.getId(), user.getUsername(), date, event.toKey());
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
