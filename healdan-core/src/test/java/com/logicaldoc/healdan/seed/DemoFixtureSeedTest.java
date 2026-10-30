package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.navigation.MyMatters;
import com.logicaldoc.healdan.seed.demo.DemoClock;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

public class DemoFixtureSeedTest extends AbstractHealdanTestCase {

	static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

	static final String PASSWORD = "Demo-Firm-2026";

	static final String TEST_DIR = "src/test/resources/demo";

	static DemoFixtureSeed seed(String dir) {
		Map<String, String> env = new HashMap<>();
		env.put(DemoFixtureSeed.ENV, "true");
		env.put(DemoFixtureSeed.DIR_ENV, dir);
		env.put(DemoFixtureSeed.PASSWORD_ENV, PASSWORD);
		return new DemoFixtureSeed(env::get, () -> TODAY);
	}

	static void run(DemoFixtureSeed seed) throws Exception {
		HealdanOperation.run(Kind.SEED, seed::run);
	}

	static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	static Matter matter(long client, long number) throws Exception {
		return MatterDAO.get().findByClientAndNumber(ClientDAO.get().findByNumber(client).getId(), number);
	}

	private static Set<Permission> granted(Folder folder, String group) throws Exception {
		long groupId = GroupDAO.get().findByName(group, Tenant.DEFAULT_ID).getId();
		FolderAccessControlEntry ace = FolderDAO.get().findById(folder.getId(), true).getAccessControlEntry(groupId);
		assertNotNull(group, ace);
		return ace.grantedPermissions();
	}

	@Test
	public void testOffWithoutTheFlag() {
		assertFalse(new DemoFixtureSeed(name -> null, () -> TODAY).enabled());
		assertEquals("demo-fixture", seed(TEST_DIR).name());
		assertTrue(seed(TEST_DIR).enabled());
		assertTrue(SeedRunner.ORDER.contains("demo-fixture"));
	}

	@Test
	public void testNeedsItsFolder() {
		DemoFixtureSeed noDir = new DemoFixtureSeed(name -> DemoFixtureSeed.ENV.equals(name) ? "true" : null,
				() -> TODAY);
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(noDir));
		assertTrue(e.getMessage(), e.getMessage().contains(DemoFixtureSeed.DIR_ENV));
	}

	@Test
	public void testBuildsTheFirm() throws Exception {
		run(seed(TEST_DIR));

		User pnair = UserDAO.get().findByUsername("pnair", true);
		assertEquals("Priya", pnair.getFirstName());
		assertTrue(pnair.isMemberOf("author"));
		assertTrue(pnair.isMemberOf(HealdanConstants.EVERYONE_GROUP));
		User hashcombe = UserDAO.get().findByUsername("hashcombe", true);
		assertTrue(hashcombe.isMemberOf("Partners"));

		long setId = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true)
				.getId();
		List<String> classes = AttributeOptionDAO.get().findByAttribute(setId, HealdanConstants.ATTR_DOCCLASS)
				.stream().map(AttributeOption::getValue).toList();
		assertTrue(classes.toString(), classes.containsAll(List.of("Docs", "Correspondence")));
		assertEquals(Set.of("Letter", "Email"),
				AttributeOptionDAO.get()
						.findByAttributeAndCategory(setId, HealdanConstants.ATTR_DOCSUBCLASS, "Correspondence")
						.stream().map(AttributeOption::getValue).collect(Collectors.toSet()));

		FolderDAO dao = FolderDAO.get();
		Folder store = dao.findByName(dao.findRoot(Tenant.DEFAULT_ID), "Client Work", Tenant.DEFAULT_ID, true).get(0);
		assertEquals(Folder.TYPE_WORKSPACE, store.getType());
		assertEquals(Set.of(Permission.READ), granted(store, HealdanConstants.EVERYONE_GROUP));

		Folder templates = dao.findById(HealdanSettings.get().templatesStoreId());
		Folder template = dao.findByName(templates, "Litigation", Tenant.DEFAULT_ID, true).get(0);
		Folder privileged = dao.findByName(template, "Privileged", Tenant.DEFAULT_ID, true).get(0);
		assertEquals(Set.of(Permission.READ, Permission.PREVIEW, Permission.DOWNLOAD, Permission.WRITE),
				granted(privileged, "Partners"));
		assertFalse(dao.findByName(template, "My Recent Docs Here", Tenant.DEFAULT_ID, true).isEmpty());

		Matter charter = matter(1001, 1);
		// An escape for the en dash, because the build reads Java sources as ISO-8859-1 and the manifest as UTF-8
		assertEquals("Charter Dispute \u2013 MV Northern Star", charter.getName());
		Matter sale = matter(1001, 2);
		assertTrue(dao.isReadAllowed(charter.getFolderId(), pnair.getId()));
		assertFalse(dao.isReadAllowed(sale.getFolderId(), pnair.getId()));
		assertTrue(dao.isReadAllowed(sale.getFolderId(), hashcombe.getId()));
	}

	@Test
	public void testDatesTheStructureBeforeTheTimeline() throws Exception {
		run(seed(TEST_DIR));
		Timestamp created = jdbc().queryForObject("select ld_creation from hd_client where hd_number = 1001",
				Timestamp.class);
		assertTrue(created.toString(), created.toLocalDateTime().toLocalDate().isBefore(TODAY));
	}

	/** Demo spec §5.3: any document at all is real data, so nothing is built beside it */
	@Test
	public void testRefusesARepositoryWithDocuments() throws Exception {
		jdbc().update("insert into ld_document (ld_id, ld_lastmodified, ld_recordversion, ld_deleted, ld_tenantid, "
				+ "ld_immutable, ld_creation, ld_publisherid, ld_creatorid, ld_signed, ld_stamped, ld_exportstatus, "
				+ "ld_published, ld_pages, ld_previewpages, ld_nature, ld_links, ld_docattrs) "
				+ "values (987654, current_timestamp, 1, 0, 1, 0, current_timestamp, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0)");

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(seed(TEST_DIR)));
		assertTrue(e.getMessage(), e.getMessage().contains("down -v"));
		// Nothing was built beside it
		assertNull(ClientDAO.get().findByNumber(1001));
		assertNull(UserDAO.get().findByUsername("pnair"));
	}

	@Test
	public void testAnUnknownGroupIsRefused() throws Exception {
		Path dir = tempDir.toPath().resolve("bad-group");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("manifest.json"), """
				{"users": [{"username": "x", "firstName": "X", "name": "Y", "email": "x@demo.example",
				"groups": ["Nobody"]}]}
				""");
		Exception e = assertThrows(Exception.class, () -> run(seed(dir.toString())));
		assertTrue(e.getMessage(), e.getMessage().contains("Nobody"));
	}

	@Test
	public void testPlaysTheTimeline() throws Exception {
		run(seed(TEST_DIR));
		DemoClock clock = new DemoClock(TODAY, ZoneId.systemDefault());
		DocumentDAO docs = DocumentDAO.get();
		User pnair = UserDAO.get().findByUsername("pnair");
		User hashcombe = UserDAO.get().findByUsername("hashcombe");

		Document letter = byName("Letter to Owners.txt");
		// CE records a move as a subversion: 1.0 filed, 2.0 checked in, 2.1 moved. The tests' context.properties
		// keeps a document's newest two versions (document.maxversions=2), where the image keeps them all
		assertEquals("2.1", letter.getVersion());
		assertEquals(List.of("2.0", "2.1"), jdbc().queryForList(
				"select ld_version from ld_version where ld_documentid = ? and ld_deleted = 0 order by ld_versiondate",
				String.class, letter.getId()));
		assertEquals(List.of(clock.at(-8, null).getTime(), clock.at(-2, null).getTime()), jdbc()
				.queryForList("select ld_versiondate from ld_version where ld_documentid = ? and ld_deleted = 0 "
						+ "order by ld_versiondate", Timestamp.class, letter.getId())
				.stream().map(Timestamp::getTime).toList());
		// CE keeps a user property's id in intValue and its username in stringValue (Attribute.setValue)
		assertEquals(Long.valueOf(hashcombe.getId()),
				letter.getAttribute(HealdanConstants.ATTR_AUTHOR).getIntValue());
		assertEquals(Long.valueOf(pnair.getId()), letter.getAttribute(HealdanConstants.ATTR_TYPIST).getIntValue());
		assertEquals("Correspondence", letter.getValue(HealdanConstants.ATTR_DOCCLASS));
		assertEquals("Letter", letter.getValue(HealdanConstants.ATTR_DOCSUBCLASS));
		assertEquals(Set.of("charter", "urgent"), new HashSet<>(docs.findTags(letter.getId())));
		assertEquals(correspondenceOf(matter(1001, 2)), letter.getFolder().getId());
		assertEquals(1, count("select count(*) from ld_note where ld_docid = ?", letter.getId()));

		// Dates are read through JDBC: in the tests Hibernate reads them in another zone than JDBC writes them
		// Filed on day -10 at 09:30, though CE's create stamped it with now
		assertEquals(clock.at(-10, "09:30").getTime(), documentDate("ld_creation", letter).getTime());
		// Its last change was the move, on day -2 at 09:00
		assertEquals(clock.at(-2, null).getTime(), documentDate("ld_lastmodified", letter).getTime());
		// The check-in on day -8 gave the file its date
		assertEquals(clock.at(-8, null).getTime(), documentDate("ld_date", letter).getTime());
		// Each action is in the history as its user, at its time
		assertEquals(clock.at(-8, null).getTime(),
				eventDate(letter, "event.checkedin", hashcombe).getTime());
		assertEquals(clock.at(-10, "09:30").getTime(), eventDate(letter, "event.stored", pnair).getTime());
		assertEquals(clock.at(-8, "10:00").getTime(), jdbc().queryForObject(
				"select ld_date from ld_note where ld_docid = ?", Timestamp.class, letter.getId()).getTime());

		// Healdan names an email after its subject, with the colon replaced (M1 spec section 6)
		Document reply = byName("Re_ MV Northern Star - laytime.eml");
		assertEquals("Re: MV Northern Star - laytime", reply.getValue(HealdanConstants.ATTR_EMAILSUBJECT));
		// Healdan's listener wrote this date and Hibernate reads it back as it was written
		assertEquals(clock.at(-9, "11:00").getTime() - 25 * 60_000L,
				((Date) reply.getValue(HealdanConstants.ATTR_EMAILSENT)).getTime());
		assertTrue(reply.getPassword() != null && !reply.getPassword().isEmpty());

		Document memo = byName("Attendance Note.txt");
		assertEquals(DocumentStatus.CHECKEDOUT, memo.getStatus());
		assertEquals(Long.valueOf(pnair.getId()), memo.getLockUserId());
		// A note is the memo's last action: CE saves the document for it, with the real time, and marks it to index
		assertEquals(clock.at(-3, "11:00").getTime(), documentDate("ld_lastmodified", memo).getTime());
		assertEquals(1, count("select count(*) from ld_note where ld_docid = ?", memo.getId()));

		Document terms = byName("Heads of Terms.txt");
		assertEquals(DocumentStatus.LOCKED, terms.getStatus());
		assertTrue(docs.isReadAllowed(terms.getId(), pnair.getId()));
		assertFalse(FolderDAO.get().isReadAllowed(terms.getFolder().getId(), pnair.getId()));
		// Enough to work with the document, not only to know of it (demo spec section 4.4)
		assertTrue(docs.isPermissionAllowed(Permission.PREVIEW, terms.getId(), pnair.getId()));
		assertTrue(docs.isPermissionAllowed(Permission.DOWNLOAD, terms.getId(), pnair.getId()));
		assertFalse(docs.isPermissionAllowed(Permission.WRITE, terms.getId(), pnair.getId()));

		// Indexed inside their actions, so the background indexer has nothing left to do and re-date them
		assertEquals(0, count("select count(*) from ld_document where ld_deleted = 0 and ld_indexed <> ?",
				IndexingStatus.INDEXED.ordinal()));

		// The Recycle Bin lists a deleted document by the day it was deleted
		List<Timestamp> deleted = jdbc().queryForList("select ld_lastmodified from ld_document where ld_deleted <> 0",
				Timestamp.class);
		assertEquals(1, deleted.size());
		assertEquals(clock.at(-5, null).getTime(), deleted.get(0).getTime());
		assertEquals(1, count("select count(*) from ld_folder where ld_deleted <> 0 and ld_name = 'Superseded'"));
		assertEquals(1, count("select count(*) from ld_link"));
		assertEquals(1, count("select count(*) from ld_ticket"));
		// The link is live: it expires about 30 days after the real today, not after the timeline's day
		long expires = jdbc().queryForObject("select ld_expired from ld_ticket", Timestamp.class).getTime();
		long day = 24 * 3_600_000L;
		assertTrue(Long.toString(expires), Math.abs(expires - (System.currentTimeMillis() + 30 * day)) < 2 * day);
		assertEquals(1, count("select count(*) from ld_bookmark where ld_userid = ? and ld_docid = ?",
				pnair.getId(), memo.getId()));
		assertEquals(1, count("select count(*) from ld_systemmessage"));
		assertEquals(clock.at(-1, null).getTime(),
				jdbc().queryForObject("select ld_sentdate from ld_systemmessage", Timestamp.class).getTime());
		assertEquals(1, MyMatters.get().list(pnair.getId()).size());

		// Doc #s follow the order of filing
		List<Long> numbers = jdbc().queryForList("select ld_customid from ld_document order by ld_creation, ld_id",
				String.class).stream().map(Long::valueOf).toList();
		assertEquals(numbers.stream().sorted().toList(), numbers);

		// Nothing is dated today
		Timestamp latest = jdbc().queryForObject("select max(ld_date) from ld_history", Timestamp.class);
		assertTrue(latest.toString(), latest.toLocalDateTime().toLocalDate().isBefore(TODAY));
		// Rows a deletion hides aren't looked at: CE re-saves a deleted document's notes and links as it deletes
		// them. A deleted document is looked at above
		for (String table : List.of("ld_document", "ld_note", "ld_link", "ld_ticket", "ld_bookmark",
				"ld_systemmessage")) {
			Timestamp changed = jdbc().queryForObject(
					"select max(ld_lastmodified) from " + table + " where ld_deleted = 0", Timestamp.class);
			assertTrue(table + " " + changed, changed.toLocalDateTime().toLocalDate().isBefore(TODAY));
		}
		// A version is listed by its own date. Its last-modified date isn't shown anywhere, and a later action
		// on its document re-saves some (the reply's, here), so it isn't looked at
		Timestamp versioned = jdbc().queryForObject("select max(ld_versiondate) from ld_version", Timestamp.class);
		assertTrue(versioned.toString(), versioned.toLocalDateTime().toLocalDate().isBefore(TODAY));
		// The folders the stack made before the demo, such as the Templates Store, are dated today, so look at
		// the ones the demo made: none was saved today, the deleted one included
		assertEquals(0, count("select count(*) from ld_folder where ld_creation < ? and ld_lastmodified >= ?",
				Timestamp.valueOf(TODAY.atStartOfDay()), Timestamp.valueOf(TODAY.atStartOfDay())));
		assertEquals(clock.at(-5, "09:30").getTime(), jdbc().queryForObject(
				"select ld_lastmodified from ld_folder where ld_name = 'Superseded'", Timestamp.class).getTime());
	}

	@Test
	public void testLogsTheWebPagesEvents() throws Exception {
		// CE's history starts with an empty event, and stores it so if nothing sets one
		String blank = "select count(*) from %s where ld_event is null or ld_event = ''";
		int documentRows = count(blank.formatted("ld_history"));
		int folderRows = count(blank.formatted("ld_folder_history"));
		run(seed(TEST_DIR));

		// A row without an event would show blank in Document History
		assertEquals(documentRows, count(blank.formatted("ld_history")));
		assertEquals(folderRows, count(blank.formatted("ld_folder_history")));

		DemoClock clock = new DemoClock(TODAY, ZoneId.systemDefault());
		User pnair = UserDAO.get().findByUsername("pnair");
		User hashcombe = UserDAO.get().findByUsername("hashcombe");
		// The tag is a change, as the web page's save logs it, so Recent Matters counts it
		assertEquals(clock.at(-7, null).getTime(),
				eventDate(byName("Letter to Owners.txt"), DocumentEvent.CHANGED.toString(), pnair).getTime());
		assertEquals(clock.at(-3, "09:30").getTime(),
				eventDate(byName("Heads of Terms.txt"), DocumentEvent.PERMISSION.toString(), hashcombe).getTime());
		// CE's checkout and lock set their events only on a null one, and a new history's is empty
		assertEquals(clock.at(-4, null).getTime(),
				eventDate(byName("Attendance Note.txt"), DocumentEvent.CHECKEDOUT.toString(), pnair).getTime());
		assertEquals(clock.at(-3, null).getTime(),
				eventDate(byName("Heads of Terms.txt"), DocumentEvent.LOCKED.toString(), hashcombe).getTime());
		// A check-in of a document nobody has checked out checks it out first, as its user
		assertEquals(clock.at(-8, null).getTime(),
				eventDate(byName("Letter to Owners.txt"), DocumentEvent.CHECKEDOUT.toString(), hashcombe).getTime());
		assertEquals(clock.at(-5, null).getTime(), jdbc().queryForObject(
				"select h.ld_date from ld_history h join ld_document d on d.ld_id = h.ld_docid "
						+ "where d.ld_filename = 'Old Draft.txt' and h.ld_event = ? and h.ld_userid = ?",
				Timestamp.class, DocumentEvent.DELETED.toString(), pnair.getId()).getTime());
	}

	/**
	 * The firm that ships in {@code docker/demo}: every step of its timeline
	 * runs as its user, with its files, and leaves what the screenshots need.
	 * The working directory is {@code healdan-core}.
	 */
	@Test
	public void testTheRealManifest() throws Exception {
		String blank = "select count(*) from %s where ld_event is null or ld_event = ''";
		int documentRows = count(blank.formatted("ld_history"));
		int folderRows = count(blank.formatted("ld_folder_history"));
		run(seed("../docker/demo"));

		// The firm: six people, twelve matters
		assertEquals(6, count("select count(*) from ld_user where ld_username in "
				+ "('hashcombe','treeve','pnair','jclarke','cdoyle','sokafor')"));
		assertEquals(12, count("select count(*) from hd_matter"));
		int documents = count("select count(*) from ld_document where ld_deleted = 0");
		assertTrue("documents: " + documents, documents >= 55 && documents <= 70);
		// The trash isn't empty: three documents and a subfolder
		assertEquals(3, count("select count(*) from ld_document where ld_deleted <> 0"));
		assertEquals(1, count("select count(*) from ld_folder where ld_deleted <> 0 and ld_name = 'Old Drafts'"));

		// Every step ran as its user, and none left a history row without an event
		assertEquals(documentRows, count(blank.formatted("ld_history")));
		assertEquals(folderRows, count(blank.formatted("ld_folder_history")));

		// Every kind of file the screenshots show is there
		for (String ext : List.of("odt", "ods", "odg", "docx", "xlsx", "pdf", "eml", "png", "jpg"))
			assertTrue(ext, count("select count(*) from ld_document where ld_deleted = 0 and "
					+ "lower(ld_filename) like ?", "%." + ext) > 0);

		// Priya Nair can't read 998.3 HR, but she can read the one letter granted to her there
		User pnair = UserDAO.get().findByUsername("pnair");
		User treeve = UserDAO.get().findByUsername("treeve");
		User jclarke = UserDAO.get().findByUsername("jclarke");
		assertFalse(FolderDAO.get().isReadAllowed(matter(998, 3).getFolderId(), pnair.getId()));
		assertTrue(FolderDAO.get().isReadAllowed(matter(998, 3).getFolderId(), treeve.getId()));
		Document salary = byName("Letter to Priya Nair \u2013 Salary Review.pdf");
		assertTrue(DocumentDAO.get().isReadAllowed(salary.getId(), pnair.getId()));
		assertFalse(FolderDAO.get().isReadAllowed(salary.getFolder().getId(), pnair.getId()));

		// Jamie Clarke's checkout is still open (the column holds the status's ordinal)
		assertEquals(1, count("select count(*) from ld_document where ld_deleted = 0 and ld_status = ? and "
				+ "ld_lockuserid = ?", DocumentStatus.CHECKEDOUT.ordinal(), jclarke.getId()));
		Document statement = byName("Completion Statement.ods");
		assertEquals(DocumentStatus.CHECKEDOUT, statement.getStatus());
		assertEquals(Long.valueOf(jclarke.getId()), statement.getLockUserId());

		// Indexed inside their actions, so the background indexer has nothing left to do and re-date them
		assertEquals(0, count("select count(*) from ld_document where ld_deleted = 0 and ld_indexed <> ?",
				IndexingStatus.INDEXED.ordinal()));

		// Nothing is dated today. Dates are read through JDBC, not the entities, because in the tests Hibernate
		// reads them in another zone than JDBC writes them
		Timestamp today = Timestamp.valueOf(TODAY.atStartOfDay());
		List<String> datedToday = new ArrayList<>();
		datedToday.addAll(dated("document", "select ld_filename, ld_lastmodified from ld_document "
				+ "where ld_deleted = 0 and ld_lastmodified >= ?", today));
		datedToday.addAll(dated("history", "select ld_event, ld_date from ld_history where ld_date >= ?", today));
		// The folders the stack made before the demo, such as the Templates Store, are dated today, so look at
		// the ones the demo made
		datedToday.addAll(dated("folder history", "select h.ld_event || ' ' || h.ld_path, h.ld_date "
				+ "from ld_folder_history h join ld_folder f on f.ld_id = h.ld_folderid "
				+ "where f.ld_creation < ? and h.ld_date >= ?", today, today));
		datedToday.addAll(dated("version", "select ld_version, ld_versiondate from ld_version "
				+ "where ld_versiondate >= ?", today));
		datedToday.addAll(dated("folder", "select ld_name, ld_lastmodified from ld_folder "
				+ "where ld_creation < ? and ld_lastmodified >= ?", today, today));
		assertEquals(List.of(), datedToday);
	}

	/** What a table holds that the query finds, each as its name and date */
	private static List<String> dated(String what, String sql, Object... args) {
		return jdbc().query(sql, (rs, row) -> what + " " + rs.getString(1) + " " + rs.getTimestamp(2), args);
	}

	@Test
	public void testRefusesASecondRun() throws Exception {
		run(seed(TEST_DIR));
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(seed(TEST_DIR)));
		assertTrue(e.getMessage(), e.getMessage().contains("down -v"));
	}

	private static Document byName(String name) throws Exception {
		List<Long> ids = jdbc().queryForList("select ld_id from ld_document where ld_filename = ? and ld_deleted = 0",
				Long.class, name);
		assertEquals(name, 1, ids.size());
		// Initialized, because the DAO's initialize returns a loaded copy and leaves its argument lazy
		return DocumentDAO.get().findById(ids.get(0), true);
	}

	private static Timestamp documentDate(String column, Document doc) {
		return jdbc().queryForObject("select %s from ld_document where ld_id = ?".formatted(column), Timestamp.class,
				doc.getId());
	}

	private static Timestamp eventDate(Document doc, String event, User user) {
		return jdbc().queryForObject("select ld_date from ld_history where ld_docid = ? and ld_event = ? and ld_userid = ?",
				Timestamp.class, doc.getId(), event, user.getId());
	}

	private static long correspondenceOf(Matter matter) throws Exception {
		FolderDAO dao = FolderDAO.get();
		return dao.findByName(dao.findById(matter.getFolderId()), "Correspondence", Tenant.DEFAULT_ID, true).get(0)
				.getId();
	}

	private static int count(String sql, Object... args) {
		return jdbc().queryForObject(sql, Integer.class, args);
	}
}
