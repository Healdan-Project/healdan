package com.logicaldoc.healdan.docnumber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Date;
import java.util.List;
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
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.Search;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.searchengine.SearchOptions;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * Spec §6.5: {@code #n}, {@code #n.N} and {@code #n.N.M} look a document up in
 * the database by its doc #, and a document the user can't read is one that
 * doesn't exist. The {@code Search} of type 101 lists what the lookup finds,
 * and nothing else.
 * <p>
 * One document, as each costs over a second, in matter 998.1 of a Store that
 * Alice (readers) may read and Bob may not.
 */
public class DocNumberLookupTest extends AbstractHealdanTestCase {

	/** A word that only this test's document contains */
	private static final String WORD = "quokkanumber";

	private static final long DAY = 24L * 60 * 60 * 1000;

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private User admin;

	private User alice;

	private User bob;

	private Folder documents;

	private Document doc;

	/** The document's doc #, as text */
	private String n;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		admin = fx.admin();
		Group readers = fx.group("readers");
		alice = fx.user("alice", readers.getName());
		bob = fx.user("bob");

		Folder store = fx.store("Main");
		fx.grant(store, readers, Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		doc = fx.upload(documents, "memo.txt", WORD + " abc", admin);
		awaitAsyncWork();
		doc = DocumentDAO.get().findById(doc.getId(), true);
		n = doc.getCustomId();
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
	public void testNumberFindsTheCurrentVersion() throws Exception {
		LookupResult result = DocNumberLookup.lookup("#" + n, alice.getId());

		assertTrue(result.found());
		assertEquals(Long.valueOf(doc.getId()), result.docId());
		assertEquals(Long.valueOf(documents.getId()), result.folderId());
		assertNull(result.versionId());
		assertEquals(doc.getVersion(), result.version());
		assertEquals(doc.getFileVersion(), result.fileVersion());
		assertNull(result.message());

		// Spaces and leading zeros
		assertEquals(Long.valueOf(doc.getId()), DocNumberLookup.lookup("# 0" + n + " ", alice.getId()).docId());
	}

	@Test
	public void testVersions() throws Exception {
		long v21 = addVersion("2.1", "2.0");
		addVersion("2.9", "2.0");
		long v210 = addVersion("2.10", "2.1");
		addVersion("3.0", "3.0");

		// The highest 2.x, compared as numbers
		LookupResult major = DocNumberLookup.lookup("#" + n + ".2", alice.getId());
		assertEquals(Long.valueOf(doc.getId()), major.docId());
		assertEquals(Long.valueOf(v210), major.versionId());
		assertEquals("2.10", major.version());
		assertEquals("2.1", major.fileVersion());
		assertEquals(Long.valueOf(v210), DocNumberLookup.lookup("#" + n + "v2", alice.getId()).versionId());

		// Exact
		LookupResult exact = DocNumberLookup.lookup("#" + n + ".2.1", alice.getId());
		assertEquals(Long.valueOf(v21), exact.versionId());
		assertEquals("2.1", exact.version());
		assertEquals("2.0", exact.fileVersion());
		assertEquals(Long.valueOf(v21), DocNumberLookup.lookup("#" + n + "V2.1", alice.getId()).versionId());

		assertNotFound("Document #%s has no version 2.2".formatted(n), "#" + n + ".2.2", alice);
		assertNotFound("Document #%s has no version 4".formatted(n), "#" + n + ".4", alice);
		assertNotFound("Document #%s has no version 2.3".formatted(n), "#0" + n + "v02.03", alice);
	}

	@Test
	public void testMessages() throws Exception {
		// Unreadable is the same as missing
		assertNotFound("No document #" + n, "#" + n, bob);
		assertNotFound("No document #" + n, "#" + n + ".1", bob);
		assertNotFound("No document #999", "#999", alice);
		assertNotFound("No document #99999999999999999999", "#99999999999999999999", alice);

		for (String input : new String[] { "#abc", "#1234.2.1.5", "#", "1234", "" })
			assertNotFound("'%s' isn't a document number. Type # and the number, e.g. #1234.".formatted(input), input,
					alice);
		assertNotFound("'' isn't a document number. Type # and the number, e.g. #1234.", null, alice);
	}

	@Test
	public void testDeletedDocumentIsNotFound() throws Exception {
		DocumentDAO.get().delete(doc.getId(), fx.documentHistory(admin));
		assertNotFound("No document #" + n, "#" + n, admin);
	}

	@Test
	public void testNumberNeverMatchesTheLegacyNumber() throws Exception {
		String legacy = Long.toString(Long.parseLong(n) + 1);
		Document current = DocumentDAO.get().findById(doc.getId(), true);
		current.setValue(HealdanConstants.ATTR_LEGACYDOCNUM, legacy);
		DocumentDAO.get().store(current, fx.documentHistory(admin));
		assertEquals(legacy, jdbc.queryForObject(
				"select ld_stringvalue from ld_document_ext where ld_docid = ? and ld_name = ?", String.class,
				doc.getId(), HealdanConstants.ATTR_LEGACYDOCNUM));

		assertNotFound("No document #" + legacy, "#" + legacy, admin);
	}

	@Test
	public void testUnpublishedIsHiddenFromNonPublishers() throws Exception {
		User carol = fx.user("carol", "readers", "publisher");
		Date yesterday = new Date(System.currentTimeMillis() - DAY);
		Date tomorrow = new Date(System.currentTimeMillis() + DAY);

		setPublishing(false, yesterday, null);
		assertNotFound("No document #" + n, "#" + n, alice);
		assertNotFound("No document #" + n, "#" + n + ".1", alice);
		assertEquals(List.of(), hitIds(HealdanConstants.SEARCH_DOC_NUMBER, "#" + n, alice));
		// Administrators and publishers see what CE's searches show them
		assertEquals(Long.valueOf(doc.getId()), DocNumberLookup.lookup("#" + n, admin.getId()).docId());
		assertEquals(Long.valueOf(doc.getId()), DocNumberLookup.lookup("#" + n, carol.getId()).docId());
		assertEquals(List.of(doc.getId()), hitIds(HealdanConstants.SEARCH_DOC_NUMBER, "#" + n, admin));

		// Not published yet
		setPublishing(true, tomorrow, null);
		assertNotFound("No document #" + n, "#" + n, alice);
		// No longer published
		setPublishing(true, new Date(System.currentTimeMillis() - 2 * DAY), yesterday);
		assertNotFound("No document #" + n, "#" + n, alice);
		// Published until tomorrow
		setPublishing(true, yesterday, tomorrow);
		assertEquals(Long.valueOf(doc.getId()), DocNumberLookup.lookup("#" + n, alice.getId()).docId());
		assertEquals(List.of(doc.getId()), hitIds(HealdanConstants.SEARCH_DOC_NUMBER, "#" + n, alice));
	}

	@Test
	public void testPublishedWithoutStartDateIsFound() throws Exception {
		// CE's rule (Document.isPublishing): no start date means published from the beginning
		setPublishing(true, null, null);
		assertEquals(Long.valueOf(doc.getId()), DocNumberLookup.lookup("#" + n, alice.getId()).docId());
		assertEquals(doc.getId(), com.logicaldoc.healdan.security.AccessChecks
				.readableDocument(Long.parseLong(n), alice).orElseThrow().getId());
	}

	@Test
	public void testSearchListsWhatTheLookupFinds() throws Exception {
		addVersion("2.1", "2.0");
		SearchEngine.get().unlock();
		SearchEngine.get().addHit(doc, WORD + " abc");
		// The full-text index would find the document
		assertEquals(List.of(doc.getId()), hitIds(SearchOptions.TYPE_FULLTEXT, WORD, admin));

		assertEquals(List.of(doc.getId()), hitIds(HealdanConstants.SEARCH_DOC_NUMBER, "#" + n, alice));
		assertEquals(List.of(doc.getId()), hitIds(HealdanConstants.SEARCH_DOC_NUMBER, "#" + n + ".2", alice));
		Hit hit = search(HealdanConstants.SEARCH_DOC_NUMBER, "#" + n, alice).getHits().get(0);
		assertEquals("txt", hit.getType());
		assertEquals(n, hit.getCustomId());
		assertEquals(documents.getId(), hit.getFolder().getId());

		// Nothing the lookup doesn't find, and never a full-text search instead
		for (String input : new String[] { "#" + WORD, WORD, "#abc", "#" + n + ".7", "#999", "#99999999999999999999" })
			assertEquals(input, List.of(), hitIds(HealdanConstants.SEARCH_DOC_NUMBER, input, alice));
		assertEquals(List.of(), hitIds(HealdanConstants.SEARCH_DOC_NUMBER, "#" + n, bob));
	}

	private static void assertNotFound(String message, String input, User user) throws Exception {
		LookupResult result = DocNumberLookup.lookup(input, user.getId());
		assertFalse(input, result.found());
		assertNull(result.docId());
		assertNull(result.folderId());
		assertNull(result.versionId());
		assertNull(result.version());
		assertNull(result.fileVersion());
		assertEquals(message, result.message());
	}

	private static Search search(int type, String expression, User user) throws Exception {
		SearchOptions options = Search.newOptions(type);
		options.setExpression(expression);
		options.setUserId(user.getId());
		Search search = Search.get(options);
		search.search();
		return search;
	}

	private static List<Long> hitIds(int type, String expression, User user) throws Exception {
		return search(type, expression, user).getHits().stream().map(Hit::getId).collect(Collectors.toList());
	}

	private void setPublishing(boolean published, Date start, Date stop) throws Exception {
		Document current = DocumentDAO.get().findById(doc.getId(), true);
		current.setPublished(published);
		current.setStartPublishing(start);
		current.setStopPublishing(stop);
		DocumentDAO.get().store(current, fx.documentHistory(admin));
	}

	/**
	 * Copies the document's version 1.0 row as another version, as many edits
	 * and check-ins would leave it
	 *
	 * @return the id of the new row
	 */
	private long addVersion(String version, String fileVersion) {
		Map<String, Object> row = jdbc.queryForMap(
				"select * from ld_version where ld_documentid = ? and ld_version = '1.0'", doc.getId());
		long id = jdbc.queryForObject("select max(ld_id) from ld_version", Long.class) + 1;
		row.put("ld_id", id);
		row.put("ld_version", version);
		row.put("ld_fileversion", fileVersion);
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
