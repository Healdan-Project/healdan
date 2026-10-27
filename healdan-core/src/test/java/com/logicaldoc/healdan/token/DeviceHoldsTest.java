package com.logicaldoc.healdan.token;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.token.DeviceHolds.Repeat;
import com.logicaldoc.healdan.token.DeviceHolds.Save;
import com.logicaldoc.util.spring.Context;

/**
 * Which of a user's devices holds a check-out made through the API (spec
 * section 9.4). Clerk checks the document out, as the old web page does,
 * and the API records the device on top.
 */
public class DeviceHoldsTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User clerk;

	private User boss;

	private Document doc;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		doc = fx.upload(documents, "a.docx", "one", boss);
		awaitVersionSaves();
	}

	@Test
	public void testRecordAndHeldHere() throws Exception {
		ApiToken mac = token("mac-1");
		ApiToken linux = token("linux-2");
		ApiToken none = token(null);
		checkout(clerk);
		assertTrue("a hold with no record is held on any device", holds().heldHere(reload(), linux));

		holds().record(reload(), mac, "LibreOffice");

		HoldDevice current = holds().current(reload()).orElseThrow();
		assertEquals("mac-1", current.getDevice());
		assertEquals("LibreOffice", current.getApp());
		assertEquals(Long.valueOf(mac.getId()), current.getTokenId());
		assertEquals("Laptop", holds().deviceName(current));
		assertTrue(holds().heldHere(reload(), mac));
		assertFalse(holds().heldHere(reload(), linux));
		assertFalse("a token without a device isn't the device", holds().heldHere(reload(), none));
	}

	@Test
	public void testNewTokenSameDeviceStillHere() throws Exception {
		ApiToken first = token("mac-1");
		checkout(clerk);
		holds().record(reload(), first, "LibreOffice");
		ApiTokens.get().revoke(first);

		ApiToken second = token("mac-1");

		assertTrue(holds().heldHere(reload(), second));
		// The name stays the recorded token's until that device writes again
		assertEquals("Laptop", holds().deviceName(holds().current(reload()).orElseThrow()));
	}

	@Test
	public void testStaleRecordIgnored() throws Exception {
		ApiToken mac = token("mac-1");
		ApiToken linux = token("linux-2");
		checkout(clerk);
		holds().record(reload(), mac, "LibreOffice");

		// Released and checked out again in the old web page, which records no device
		DocumentManager.get().unlock(doc.getId(), history(clerk, DocumentEvent.UNLOCKED));
		checkout(clerk);

		assertTrue(holds().current(reload()).isEmpty());
		assertTrue(holds().heldHere(reload(), linux));
		assertTrue(holds().heldHere(reload(), mac));
	}

	@Test
	public void testNotCheckedOutOrChangedHands() throws Exception {
		ApiToken mac = token("mac-1");
		checkout(clerk);
		holds().record(reload(), mac, "LibreOffice");

		// Released: nothing is held
		DocumentManager.get().unlock(doc.getId(), history(clerk, DocumentEvent.UNLOCKED));
		assertTrue(holds().current(reload()).isEmpty());

		// Someone else's check-out never shows clerk's device
		checkout(boss);
		assertTrue(holds().current(reload()).isEmpty());
	}

	@Test
	public void testClearKeepsTheRow() throws Exception {
		ApiToken mac = token("mac-1");
		checkout(clerk);
		holds().record(reload(), mac, "LibreOffice");
		jdbc().update("update hd_hold_device set hd_lastsaveid = 'save-1' where hd_docid = ?", doc.getId());

		holds().clear(reload());

		Map<String, Object> row = jdbc().queryForMap(
				"select hd_device, hd_tokenid, hd_app, hd_lastsaveid from hd_hold_device where hd_docid = ?",
				doc.getId());
		assertNull(row.get("hd_device"));
		assertNull(row.get("hd_tokenid"));
		assertNull(row.get("hd_app"));
		assertEquals("save-1", row.get("hd_lastsaveid"));
		assertTrue(holds().current(reload()).isEmpty());
		assertTrue(holds().heldHere(reload(), token("linux-2")));
	}

	@Test
	public void testLastSaveId() throws Exception {
		ApiToken mac = token("mac-1");
		assertNull(holds().lastSaveId(reload()));

		// A save on closing of a check-out with no record: a row for the id alone, which holds nothing
		holds().clear(reload(), save("save-1"));
		assertEquals("save-1", holds().lastSaveId(reload()));
		checkout(clerk);
		assertTrue(holds().current(reload()).isEmpty());
		assertTrue(holds().heldHere(reload(), mac));

		// Saved while editing: the device and the id in one write
		holds().record(reload(), mac, "LibreOffice", save("save-2"));
		assertEquals("save-2", holds().lastSaveId(reload()));
		assertEquals("mac-1", holds().current(reload()).orElseThrow().getDevice());

		// Writes without an id keep the last one, and so does the end of the check-out
		holds().record(reload(), mac, "Writer");
		assertEquals("save-2", holds().lastSaveId(reload()));
		DocumentManager.get().unlock(doc.getId(), history(clerk, DocumentEvent.UNLOCKED));
		holds().clear(reload());
		assertEquals("save-2", holds().lastSaveId(reload()));
		holds().clear(reload(), save("save-3"));
		assertEquals("save-3", holds().lastSaveId(reload()));
		assertEquals(1, (int) jdbc().queryForObject("select count(*) from hd_hold_device where hd_docid = ?",
				Integer.class, doc.getId()));
	}

	@Test
	public void testRepeat() throws Exception {
		ApiToken mac = token("mac-1");
		assertEquals(Repeat.NO, holds().repeat(reload(), "save-1"));

		// A save on closing: told it's stored while the document's file is the one it stored
		holds().clear(reload(), save("save-1"));
		assertEquals(Repeat.STORED, holds().repeat(reload(), "save-1"));
		assertEquals(Repeat.NO, holds().repeat(reload(), "save-2"));
		assertEquals(Repeat.NO, holds().repeat(reload(), null));

		// Content checked in another way, as from the web page: the file isn't the save's any more
		checkout(clerk);
		checkin("two");
		assertEquals("1.1", reload().getFileVersion());
		assertEquals(Repeat.SUPERSEDED, holds().repeat(reload(), "save-1"));

		// The next save with an id names its own version, and writes without one keep it
		checkout(clerk);
		holds().record(reload(), mac, "LibreOffice", save("save-2"));
		assertEquals(Repeat.STORED, holds().repeat(reload(), "save-2"));
		assertEquals(Repeat.NO, holds().repeat(reload(), "save-1"));
		holds().record(reload(), mac, "Writer");
		holds().clear(reload());
		assertEquals(Repeat.STORED, holds().repeat(reload(), "save-2"));

		// Its version deleted, CE numbers the next file as it was numbered: the same fileVersion, but not its file
		DocumentManager.get().deleteVersion(VersionDAO.get().findByVersion(doc.getId(), "1.1").getId(),
				history(clerk, DocumentEvent.VERSION_DELETED));
		assertEquals("1.0", reload().getFileVersion());
		checkin("three");
		assertEquals("1.1", reload().getFileVersion());
		assertEquals(Repeat.SUPERSEDED, holds().repeat(reload(), "save-2"));

		// A record that doesn't name the version, as one kept before schema version 5
		holds().clear(reload(), new Save("save-3", null));
		assertEquals(Repeat.UNKNOWN, holds().repeat(reload(), "save-3"));
	}

	@Test
	public void testMove() throws Exception {
		ApiToken mac = token("mac-1");
		ApiToken linux = token("linux-2");
		checkout(clerk);
		holds().record(reload(), mac, "LibreOffice");
		int checkouts = checkouts();

		holds().move(reload(), linux, "LibreOffice");

		assertEquals("linux-2", holds().current(reload()).orElseThrow().getDevice());
		assertTrue(holds().heldHere(reload(), linux));
		assertFalse(holds().heldHere(reload(), mac));
		// Recorded in the history, as clerk's check-out
		assertEquals(checkouts + 1, checkouts());
		assertEquals(clerk.getId(), jdbc().queryForObject("select ld_userid from ld_history where ld_docid = ? and "
				+ "ld_event = 'event.checkedout' order by ld_date desc, ld_id desc limit 1", Long.class, doc.getId())
				.longValue());
	}

	@Test
	public void testRecordWritesOnlyChanges() throws Exception {
		ApiToken mac = token("mac-1");
		checkout(clerk);
		holds().record(reload(), mac, "LibreOffice");
		List<Map<String, Object>> before = jdbc().queryForList("select * from hd_hold_device");

		holds().record(reload(), mac, "LibreOffice");

		assertEquals(before, jdbc().queryForList("select * from hd_hold_device"));
	}

	private DeviceHolds holds() {
		return DeviceHolds.get();
	}

	private ApiToken token(String device) throws Exception {
		return ApiTokens.get().issue(clerk, "Laptop", device, null).row();
	}

	private Document reload() throws Exception {
		return DocumentDAO.get().findById(doc.getId());
	}

	/** As the old web page checks a document out */
	private void checkout(User as) throws Exception {
		DocumentManager.get().checkout(doc.getId(), history(as, DocumentEvent.CHECKEDOUT));
	}

	/** As the old web page checks content in, for clerk, who has it checked out */
	private void checkin(String content) throws Exception {
		DocumentManager.get().checkin(doc.getId(), new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
				"a.docx", false, null, history(clerk, DocumentEvent.CHECKEDIN)).get();
		awaitVersionSaves();
	}

	/**
	 * @return a save with the id, as the API keeps it after storing the
	 *         document's file as it is now: with the version record of that
	 *         file
	 */
	private Save save(String id) throws Exception {
		Document now = reload();
		return new Save(id, VersionDAO.get().findByVersion(now.getId(), now.getFileVersion()).getId());
	}

	private DocumentHistory history(User as, DocumentEvent event) {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(event);
		return history;
	}

	private int checkouts() {
		return jdbc().queryForObject(
				"select count(*) from ld_history where ld_docid = ? and ld_event = 'event.checkedout'", Integer.class,
				doc.getId());
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	/**
	 * Waits for CE's background version saves, which every new document
	 * starts
	 */
	private static void awaitVersionSaves() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 20000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				throw new AssertionError("Background version saves didn't finish");
			Thread.sleep(50L);
		}
	}
}
