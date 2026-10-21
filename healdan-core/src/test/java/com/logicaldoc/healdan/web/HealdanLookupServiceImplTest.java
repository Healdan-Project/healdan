package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIDocNumberResult;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * The search box's doc # lookup (spec 6.5): the RPC passes the lookup's
 * answers on as they are, for the session's user.
 * <p>
 * One document, as each costs over a second, in a Store that Alice may read
 * and Bob may not.
 */
public class HealdanLookupServiceImplTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private Session alices;

	private Session bobs;

	private Document doc;

	/** The document's doc #, as text */
	private String n;

	@Before
	public void prepare() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		Group readers = fx.group("readers");
		alices = open(fx.user("alice", readers.getName()));
		bobs = open(fx.user("bob"));
		Folder store = fx.store("Main");
		fx.grant(store, readers, Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		doc = fx.upload(documents, "memo.txt", "quokkanumber abc", fx.admin());
		awaitAsyncWork();
		doc = DocumentDAO.get().findById(doc.getId(), true);
		n = doc.getCustomId();
	}

	@After
	@Override
	public void tearDown() throws IOException {
		SessionManager.get().kill(alices.getSid());
		SessionManager.get().kill(bobs.getSid());
		try {
			awaitAsyncWork();
		} catch (Exception e) {
			// The database goes anyway
		}
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	private static Session open(User user) {
		return SessionManager.get().newSession(user.getUsername(), HealdanFixtures.PASSWORD,
				(com.logicaldoc.core.security.Client) null);
	}

	/** The service as the given session's user */
	private static HealdanLookupServiceImpl as(Session session) {
		return new HealdanLookupServiceImpl() {
			private static final long serialVersionUID = 1L;

			@Override
			protected Session session() {
				return session;
			}
		};
	}

	@Test
	public void testLookupMessages() throws Exception {
		GUIDocNumberResult found = as(alices).lookup("#" + n);
		assertTrue(found.isFound());
		assertEquals(Long.valueOf(doc.getId()), found.getDocId());
		assertEquals(Long.valueOf(n), found.getDocNumber());
		assertEquals(Long.valueOf(doc.getFolder().getId()), found.getFolderId());
		assertNull(found.getVersionId());
		assertEquals(doc.getVersion(), found.getVersion());
		assertEquals(doc.getFileVersion(), found.getFileVersion());
		assertNull(found.getMessage());

		assertNotFound("No document #999", as(alices).lookup("#999"));
		// Unreadable is the same as missing
		assertNotFound("No document #" + n, as(bobs).lookup("#" + n));
		assertNotFound("'abc' isn't a document number. Type # and the number, e.g. #1234.", as(alices).lookup("abc"));
	}

	@Test
	public void testLookupVersion() throws Exception {
		long v21 = addVersion("2.1", "2.0", new Date(1700000000000L), "carol");
		long v210 = addVersion("2.10", "2.1", new Date(1710000000000L), "dave");

		GUIDocNumberResult exact = as(alices).lookup("#" + n + ".2.1");
		assertEquals(Long.valueOf(doc.getId()), exact.getDocId());
		assertEquals(Long.valueOf(v21), exact.getVersionId());
		assertEquals(Long.valueOf(n), exact.getDocNumber());
		assertEquals("2.1", exact.getVersion());
		assertEquals("2.0", exact.getFileVersion());
		assertEquals(storedDate(v21), exact.getVersionDate());
		assertFalse(storedDate(v21).equals(storedDate(v210)));
		assertEquals("carol", exact.getUser());

		// The highest 2.x
		GUIDocNumberResult major = as(alices).lookup("#" + n + ".2");
		assertEquals(Long.valueOf(v210), major.getVersionId());
		assertEquals("2.10", major.getVersion());
		assertEquals("2.1", major.getFileVersion());
		assertEquals(storedDate(v210), major.getVersionDate());
		assertEquals("dave", major.getUser());

		assertNotFound("Document #%s has no version 2.2".formatted(n), as(alices).lookup("#" + n + ".2.2"));
	}

	private static void assertNotFound(String message, GUIDocNumberResult result) {
		assertFalse(result.isFound());
		assertNull(result.getDocId());
		assertNull(result.getDocNumber());
		assertNull(result.getVersionDate());
		assertNull(result.getUser());
		assertNull(result.getFolderId());
		assertNull(result.getVersionId());
		assertEquals(message, result.getMessage());
	}

	/** The version's date as CE reads it, whatever time zone the database applies */
	private static Date storedDate(long versionId) throws Exception {
		return new Date(VersionDAO.get().findById(versionId).getVersionDate().getTime());
	}

	private long addVersion(String version, String fileVersion, Date date, String user) {
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		Map<String, Object> row = jdbc.queryForMap(
				"select * from ld_version where ld_documentid = ? and ld_version = '1.0'", doc.getId());
		long id = jdbc.queryForObject("select max(ld_id) from ld_version", Long.class) + 1;
		row.put("ld_id", id);
		row.put("ld_version", version);
		row.put("ld_fileversion", fileVersion);
		row.put("ld_versiondate", new Timestamp(date.getTime()));
		row.put("ld_username", user);
		jdbc.update("insert into ld_version (%s) values (%s)".formatted(String.join(", ", row.keySet()),
				row.keySet().stream().map(k -> "?").collect(Collectors.joining(", "))), row.values().toArray());
		return id;
	}

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
