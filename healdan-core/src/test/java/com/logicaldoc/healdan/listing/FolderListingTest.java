// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

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
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.listing.ListingQuery.Edited;
import com.logicaldoc.healdan.listing.ListingQuery.Sort;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * {@link FolderListing}: a folder's documents paged, sorted, filtered and
 * counted in the database (M6 spec sections 9.2 and 9.5), showing exactly what
 * {@link AccessChecks#readableDocuments} allows.
 * <p>
 * Alice, Bob and Carol read matter 998.1 through the Store; Carol is a
 * publisher; Outsider has no grant. {@link #ruleLayout()} fills Open, whose
 * documents differ in their own ACLs, publishing, deletion and doc #, and
 * Closed, which has an ACL of its own that grants the admin alone.
 * {@link #sortedLayout()} fills Sorted with five documents whose title, doc #,
 * version, author, class and edit date are set to sort and filter by.
 * <p>
 * Uploads are slow, about a second each, so the layouts upload a few
 * documents and copy the rest, rows and properties and ACL, with JDBC.
 */
public class FolderListingTest extends AbstractHealdanTestCase {

	/** The day the edit-date presets count from, at noon */
	private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

	private static final ZoneId ZONE = ZoneId.systemDefault();

	private static final Clock CLOCK = Clock.fixed(TODAY.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private User alice;

	private User bob;

	private User carol;

	private User outsider;

	private Folder documents;

	private Folder open;

	private Folder closed;

	private Folder sorted;

	/** The next id {@link #copy} gives */
	private long nextId;

	/** In Open: no ACL of its own */
	private long a;

	/** In Open: Alice's group may read it */
	private long b;

	/** In Open: Alice's group has a row that grants something, but not READ */
	private long c;

	/** In Open: only another group has rows */
	private long d;

	/** In Open: not published */
	private long e;

	/** In Open: deleted */
	private long f;

	/** In Open: no doc #, or one that isn't 1 to 18 digits */
	private final List<Long> g = new ArrayList<>();

	/** In Open: Alice's group has a row that grants nothing */
	private long h;

	/** In Closed: no ACL of its own */
	private long closedPlain;

	/** In Closed: Alice's group may read it */
	private long closedGranted;

	/** In Closed: Alice's group has a row without READ */
	private long closedNoRead;

	/** In Closed: Alice's group has a row that grants nothing */
	private long closedEmpty;

	/** In Sorted */
	private long s1, s2, s3, s4, s5;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		admin = fx.admin();
		alice = fx.user("alice");
		bob = fx.user("bob");
		carol = fx.user("carol", "publisher");
		outsider = fx.user("outsider");

		Folder store = fx.store("Main");
		for (User reader : List.of(alice, bob, carol))
			fx.grant(store, reader.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		nextId = jdbc().queryForObject("select coalesce(max(ld_id), 0) from ld_document", Long.class) + 100_000;
	}

	/**
	 * Fills Open and Closed
	 */
	private void ruleLayout() throws Exception {
		Group it = fx.group("IT");
		open = fx.folder(documents, "Open");
		closed = fx.folder(documents, "Closed");

		a = fx.upload(open, "a.pdf", "a", admin).getId();
		b = fx.upload(open, "b.pdf", "b", admin).getId();
		c = fx.upload(open, "c.pdf", "c", admin).getId();
		d = fx.upload(open, "d.docx", "d", admin).getId();
		e = fx.upload(open, "e.pdf", "e", admin).getId();
		h = fx.upload(open, "h.docx", "h", admin).getId();
		awaitAsyncWork();

		own(b, alice.getUserGroup(), Permission.READ);
		own(c, alice.getUserGroup(), Permission.DOWNLOAD);
		own(d, it, Permission.READ, Permission.WRITE);
		own(h, alice.getUserGroup());
		Document current = DocumentDAO.get().findById(e, true);
		current.setPublished(false);
		DocumentDAO.get().store(current, fx.documentHistory(admin));

		// Closed: an ACL of its own, which grants the admin alone
		Folder secret = FolderDAO.get().findById(closed.getId(), true);
		secret.setSecurityRef(null);
		FolderDAO.get().store(secret, fx.folderHistory(admin));
		fx.grant(closed, admin.getUserGroup(), Permission.READ, Permission.WRITE);
		awaitAsyncWork();

		// What a listener could change goes in last
		f = copy(a, open, "f.pdf");
		jdbc().update("update ld_document set ld_deleted = 1 where ld_id = ?", f);
		for (String customId : new String[] { null, "ABC", "1234567890123456789", "77 ", "" }) {
			long odd = copy(a, open, "g.pdf");
			jdbc().update("update ld_document set ld_customid = ? where ld_id = ?", customId, odd);
			g.add(odd);
		}
		closedPlain = copy(a, closed, "plain.pdf");
		closedGranted = copy(b, closed, "granted.pdf");
		closedNoRead = copy(c, closed, "noread.pdf");
		closedEmpty = copy(h, closed, "empty.pdf");
		evict();
	}

	/**
	 * Fills Sorted. The two titles alike but for case tie.
	 */
	private void sortedLayout() throws Exception {
		User zed = fx.user("zed");
		User abe = fx.user("abe");
		sorted = fx.folder(documents, "Sorted");
		s1 = fx.upload(sorted, "beta.docx", "1", admin).getId();
		awaitAsyncWork();
		s2 = copy(s1, sorted, "alpha.pdf");
		s3 = copy(s1, sorted, "Alpha.pdf");
		s4 = copy(s1, sorted, "gamma.xlsx");
		s5 = copy(s1, sorted, "delta.msg");

		JdbcTemplate jdbc = jdbc();
		jdbc.update("update ld_user set ld_firstname = 'Aaron', ld_name = 'Zed' where ld_id = ?", zed.getId());
		jdbc.update("update ld_user set ld_firstname = 'Zoe', ld_name = 'Abe' where ld_id = ?", abe.getId());
		set(s1, "8000009", "2.10", abe, "Letters", LocalDateTime.of(2026, 6, 15, 9, 0));
		set(s2, "80000010", "10.0", zed, "Letters", LocalDateTime.of(2026, 6, 12, 23, 59));
		set(s3, "6000007", "1.0", null, null, LocalDateTime.of(2026, 6, 1, 0, 0));
		set(s4, "8000011", "2.9", zed, "Contracts", LocalDateTime.of(2026, 2, 1, 10, 0));
		set(s5, "90000001", "2.0", abe, null, LocalDateTime.of(2025, 12, 31, 23, 0));
		evict();
	}

	@After
	@Override
	public void tearDown() throws IOException {
		try {
			awaitAsyncWork();
		} catch (Exception ex) {
			// The database goes anyway
		}
		super.tearDown();
	}

	/**
	 * Replaces the document's ACL with one row. A row that grants nothing,
	 * which a save refuses, is written with JDBC instead, so it's added to the
	 * rows the document has: give it to a document with none.
	 */
	private void own(long docId, Group group, Permission... permissions) throws Exception {
		if (permissions.length == 0) {
			// A save of the document's permissions refuses a row that grants nothing
			fx.documentRow(docId, group.getId());
			return;
		}
		Document current = DocumentDAO.get().findById(docId, true);
		current.getAccessControlList().clear();
		DocumentAccessControlEntry ace = new DocumentAccessControlEntry(group.getId());
		ace.grantPermissions(EnumSet.copyOf(List.of(permissions)));
		current.addAccessControlEntry(ace);
		DocumentHistory history = fx.documentHistory(admin);
		history.setEvent(DocumentEvent.PERMISSION);
		DocumentDAO.get().store(current, history);
	}

	/**
	 * Copies a document as a new one with JDBC: its row, its properties and
	 * its ACL, with a new id and doc #, the name and the type given, in the
	 * folder given
	 *
	 * @return the copy's id
	 */
	private long copy(long templateId, Folder folder, String fileName) {
		return bulk(templateId, folder, List.of(fileName)).get(0);
	}

	/**
	 * Copies a document many times; see {@link #copy}
	 *
	 * @return the copies' ids, in the order of the names
	 */
	private List<Long> bulk(long templateId, Folder folder, List<String> fileNames) {
		JdbcTemplate jdbc = jdbc();
		long first = nextId;
		nextId += fileNames.size();
		List<Long> ids = new ArrayList<>();
		List<Object[]> documentRows = new ArrayList<>();
		Map<String, List<Object[]>> childRows = new LinkedHashMap<>();
		Map<String, List<String>> childColumns = new LinkedHashMap<>();
		Map<String, Object> row = jdbc.queryForMap("select * from ld_document where ld_id = ?", templateId);
		List<String> columns = new ArrayList<>(row.keySet());
		for (String table : new String[] { "ld_document_ext", "ld_document_acl" }) {
			List<Map<String, Object>> rows = jdbc.queryForList("select * from " + table + " where ld_docid = ?",
					templateId);
			childRows.put(table, new ArrayList<>());
			childColumns.put(table, rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet()));
			for (int i = 0; i < fileNames.size(); i++) {
				long id = first + i;
				for (Map<String, Object> child : rows)
					childRows.get(table).add(childColumns.get(table).stream()
							.map(col -> "LD_DOCID".equalsIgnoreCase(col) ? (Object) id : child.get(col)).toArray());
			}
		}
		for (int i = 0; i < fileNames.size(); i++) {
			String name = fileNames.get(i);
			long id = first + i;
			ids.add(id);
			Map<String, Object> copy = new LinkedHashMap<>();
			for (String col : columns)
				copy.put(col.toUpperCase(), row.get(col));
			copy.put("LD_ID", id);
			copy.put("LD_CUSTOMID", Long.toString(5_000_000L + id));
			copy.put("LD_FILENAME", name);
			copy.put("LD_TYPE", name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "");
			copy.put("LD_FOLDERID", folder.getId());
			documentRows.add(columns.stream().map(col -> copy.get(col.toUpperCase())).toArray());
		}
		jdbc.batchUpdate(insert("ld_document", columns), documentRows);
		for (String table : childRows.keySet())
			if (!childRows.get(table).isEmpty())
				jdbc.batchUpdate(insert(table, childColumns.get(table)), childRows.get(table));
		return ids;
	}

	private static String insert(String table, List<String> columns) {
		return "insert into %s (%s) values (%s)".formatted(table, String.join(", ", columns),
				String.join(", ", columns.stream().map(col -> "?").toList()));
	}

	/** Sets what Sorted's documents sort and filter by */
	private void set(long docId, String docNumber, String version, User author, String docClass,
			LocalDateTime edited) {
		JdbcTemplate jdbc = jdbc();
		jdbc.update("update ld_document set ld_customid = ?, ld_version = ?, ld_fileversion = ?, ld_lastmodified = ?, ld_date = ? where ld_id = ?",
				docNumber, version, version, Timestamp.valueOf(edited), Timestamp.valueOf(edited), docId);
		attribute(docId, HealdanConstants.ATTR_AUTHOR, Attribute.Type.USER,
				author == null ? null : author.getUsername(), author == null ? null : author.getId());
		attribute(docId, HealdanConstants.ATTR_DOCCLASS, Attribute.Type.STRING, docClass, null);
	}

	private void attribute(long docId, String name, Attribute.Type type, String stringValue, Long intValue) {
		JdbcTemplate jdbc = jdbc();
		if (jdbc.update("update ld_document_ext set ld_stringvalue = ?, ld_intvalue = ? where ld_docid = ? and ld_name = ?",
				stringValue, intValue, docId, name) == 0)
			jdbc.update("""
					insert into ld_document_ext (ld_docid, ld_name, ld_mandatory, ld_type, ld_editor, ld_position,
					                             ld_hidden, ld_readonly, ld_multiple, ld_stringvalue, ld_intvalue)
					values (?, ?, 0, ?, 0, 0, 0, 0, 0, ?, ?)
					""", docId, name, type.ordinal(), stringValue, intValue);
	}

	private void evict() {
		Set<Long> ids = new HashSet<>(jdbc().queryForList("select ld_id from ld_document", Long.class));
		Context.get(CacheEvictor.class).evictDocuments(ids);
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private static ListingQuery query(Sort sort, boolean descending) {
		return ListingQuery.of(sort, descending, 0, 100);
	}

	private static ListingQuery all() {
		return query(Sort.TITLE, false);
	}

	private static Set<Long> set(long... ids) {
		Set<Long> set = new HashSet<>();
		for (long id : ids)
			set.add(id);
		return set;
	}

	private static List<Long> list(long... ids) {
		List<Long> list = new ArrayList<>();
		for (long id : ids)
			list.add(id);
		return list;
	}

	/** What the batched read check of today's listing shows, with a numeric doc # */
	private static Set<Long> expected(Folder folder, User user) throws Exception {
		List<Document> all = DocumentDAO.get().findByFolder(folder.getId(), null);
		return AccessChecks.readableDocuments(folder, all, user).stream()
				.filter(doc -> doc.getCustomId() != null && doc.getCustomId().matches("[0-9]{1,18}"))
				.map(Document::getId).collect(Collectors.toSet());
	}

	@Test
	public void testRule() throws Exception {
		ruleLayout();

		ListedPage page = FolderListing.get().page(open, alice, all());
		assertEquals(set(a, b, d, h), new HashSet<>(page.docIds()));
		assertEquals(4, page.total());

		// A folder Alice can't read shows only what her own grants open
		ListedPage shut = FolderListing.get().page(closed, alice, all());
		assertEquals(list(closedGranted), shut.docIds());
		assertEquals(1, shut.total());
		ListedPage nothing = FolderListing.get().page(closed, bob, all());
		assertTrue(nothing.docIds().isEmpty());
		assertEquals(0, nothing.total());

		// The publisher sees the unpublished one; the admin that and C
		assertTrue(FolderListing.get().page(open, carol, all()).docIds().contains(e));
		assertEquals(set(a, b, c, d, e, h), new HashSet<>(FolderListing.get().page(open, admin, all()).docIds()));
		assertEquals(set(closedPlain, closedGranted, closedNoRead, closedEmpty),
				new HashSet<>(FolderListing.get().page(closed, admin, all()).docIds()));
		assertTrue(FolderListing.get().page(open, outsider, all()).docIds().isEmpty());

		// Exactly what the read check of today's listing shows, for everyone
		for (User user : List.of(admin, alice, bob, carol, outsider))
			for (Folder folder : List.of(open, closed)) {
				ListedPage listed = FolderListing.get().page(folder, user, all());
				String where = user.getUsername() + " in " + folder.getName();
				assertEquals(where, expected(folder, user), new HashSet<>(listed.docIds()));
				assertEquals(where, listed.docIds().size(), listed.total());
			}

		// A filter never opens what the read check closes
		ListedPage pdfs = FolderListing.get().page(open, alice, all().withKinds(Set.of("pdf")));
		assertEquals(set(a, b), new HashSet<>(pdfs.docIds()));
		assertEquals(set(closedGranted),
				new HashSet<>(FolderListing.get().page(closed, alice, all().withKinds(Set.of("pdf"))).docIds()));
	}

	@Test
	public void testFacetsCountOnlyWhatCanBeRead() throws Exception {
		ruleLayout();
		Facets facets = FolderListing.get().facets(open, alice, all(), Set.of("type", "author"));

		// A and B are PDFs, D and H Word: C, E, F and the G's are PDFs too, and never counted
		assertEquals(Map.of("pdf", 2L, "word", 2L), facets.counts().get("type"));
		assertEquals(Map.of("admin", 4L), facets.counts().get("author"));
		assertEquals(Set.of("type", "author"), facets.counts().keySet());

		assertEquals(Map.of("pdf", 1L),
				FolderListing.get().facets(closed, alice, all(), Set.of("type")).counts().get("type"));
		assertEquals(Map.of(), FolderListing.get().facets(closed, bob, all(), Set.of("type")).counts().get("type"));
		// Carol, a publisher, reads the folder: A, B, C and E, D and H
		assertEquals(Map.of("pdf", 4L, "word", 2L),
				FolderListing.get().facets(open, carol, all(), Set.of("type")).counts().get("type"));
	}

	@Test
	public void testSorting() throws Exception {
		sortedLayout();
		Map<Sort, List<Long>> ascending = new LinkedHashMap<>();
		// alpha.pdf and Alpha.pdf tie, so the older id comes first
		ascending.put(Sort.TITLE, list(s2, s3, s1, s5, s4));
		// Numerically, not as text, where 80000010 would come before 8000009
		ascending.put(Sort.DOC_NUMBER, list(s3, s1, s4, s2, s5));
		// 1.0, 2.0, 2.9, 2.10, 10.0
		ascending.put(Sort.VERSION, list(s3, s5, s4, s1, s2));
		// Aaron Zed, then Zoe Abe, by name, not username; no author last
		ascending.put(Sort.AUTHOR, list(s2, s4, s1, s5, s3));
		ascending.put(Sort.EDITED, list(s5, s4, s3, s2, s1));

		Map<Sort, List<Long>> descending = new LinkedHashMap<>();
		descending.put(Sort.TITLE, list(s4, s5, s1, s3, s2));
		descending.put(Sort.DOC_NUMBER, list(s5, s2, s4, s1, s3));
		descending.put(Sort.VERSION, list(s2, s1, s4, s5, s3));
		// No author stays last either way
		descending.put(Sort.AUTHOR, list(s5, s1, s4, s2, s3));
		descending.put(Sort.EDITED, list(s1, s2, s3, s4, s5));

		for (Sort sort : Sort.values()) {
			assertEquals(sort + " ascending", ascending.get(sort),
					FolderListing.get().page(sorted, bob, query(sort, false)).docIds());
			assertEquals(sort + " descending", descending.get(sort),
					FolderListing.get().page(sorted, bob, query(sort, true)).docIds());
		}

		// No sort is by title
		assertEquals(ascending.get(Sort.TITLE),
				FolderListing.get().page(sorted, bob, ListingQuery.of(null, false, 0, 100)).docIds());
		// Pages of one follow the same order
		List<Long> paged = new ArrayList<>();
		for (int offset = 0; offset < 5; offset++)
			paged.addAll(FolderListing.get().page(sorted, bob, ListingQuery.of(Sort.VERSION, false, offset, 1))
					.docIds());
		assertEquals(ascending.get(Sort.VERSION), paged);
	}

	@Test
	public void testPaging() throws Exception {
		Folder bulk = fx.folder(documents, "Bulk");
		long template = fx.upload(bulk, "template.pdf", "t", admin).getId();
		awaitAsyncWork();
		String[] types = { "pdf", "docx", "xlsx", "eml", "odt" };
		List<String> names = new ArrayList<>();
		for (int i = 0; i < 1999; i++)
			names.add("doc-%04d.%s".formatted(i % 700, types[i % types.length]));
		List<Long> copies = bulk(template, bulk, names);
		// Many share a version and an edit date, so ties have to be broken
		List<Object[]> values = new ArrayList<>();
		for (int i = 0; i < copies.size(); i++)
			values.add(new Object[] { "%d.%d".formatted(1 + i % 3, i % 2),
					Timestamp.valueOf(LocalDateTime.of(2026, 5, 1, 0, 0).plusHours(i % 50)),
					Timestamp.valueOf(LocalDateTime.of(2026, 5, 1, 0, 0).plusHours(i % 50)), copies.get(i) });
		jdbc().batchUpdate("update ld_document set ld_fileversion = ?, ld_lastmodified = ?, ld_date = ? where ld_id = ?", values);
		Set<Long> all = new HashSet<>(copies);
		all.add(template);
		evict();

		CountingDataSource counting = new CountingDataSource(Context.get(DataSource.class));
		FolderListing listing = new FolderListing(counting.dataSource());
		for (Sort sort : Sort.values())
			for (boolean descending : new boolean[] { false, true }) {
				List<Long> seen = new ArrayList<>();
				for (int offset = 0; offset < 2000; offset += 100) {
					counting.reset();
					ListedPage page = listing.page(bulk, bob, ListingQuery.of(sort, descending, offset, 100));
					assertEquals(sort + " " + descending, 2000, page.total());
					assertEquals(100, page.docIds().size());
					seen.addAll(page.docIds());

					// The page's own rows, and a count: never the whole folder
					List<CountingDataSource.Query> queries = counting.queries();
					assertEquals(queries.toString(), 2, queries.size());
					assertTrue(queries.get(0).sql, queries.get(0).sql.endsWith("limit ? offset ?"));
					assertEquals(queries.get(0).sql, 100, queries.get(0).rows);
					assertTrue(queries.get(1).sql, queries.get(1).sql.startsWith("select count(*)"));
					assertEquals(1, queries.get(1).rows);
				}
				assertEquals(sort + " " + descending + " showed a document twice", 2000, new HashSet<>(seen).size());
				assertEquals(all, new HashSet<>(seen));
			}

		// The last page, short, says the total itself
		counting.reset();
		ListedPage last = listing.page(bulk, bob, ListingQuery.of(Sort.TITLE, false, 1950, 100));
		assertEquals(50, last.docIds().size());
		assertEquals(2000, last.total());
		assertEquals(1, counting.queries().size());
		// Past the end: nothing, and still the total
		ListedPage beyond = listing.page(bulk, bob, ListingQuery.of(Sort.TITLE, false, 2000, 100));
		assertTrue(beyond.docIds().isEmpty());
		assertEquals(2000, beyond.total());
		// A limit of 0 counts alone
		counting.reset();
		ListedPage counted = listing.page(bulk, bob, ListingQuery.of(Sort.TITLE, false, 0, 0));
		assertTrue(counted.docIds().isEmpty());
		assertEquals(2000, counted.total());
		assertEquals(1, counting.queries().size());
	}

	@Test
	public void testFilters() throws Exception {
		sortedLayout();
		assertEquals(set(s2, s3), filtered(all().withKinds(Set.of("pdf"))));
		assertEquals(set(s1, s2, s3), filtered(all().withKinds(Set.of("pdf", "word"))));
		assertEquals(set(s5), filtered(all().withKinds(Set.of("email"))));
		assertEquals(Set.of(), filtered(all().withKinds(Set.of("picture"))));

		assertEquals(set(s2, s4), filtered(all().withAuthors(Set.of("zed"))));
		assertEquals(set(s1, s2, s4, s5), filtered(all().withAuthors(Set.of("zed", "abe"))));
		assertEquals(set(s1, s2), filtered(all().withClasses(Set.of("Letters"))));
		assertEquals(set(s1, s2, s4), filtered(all().withClasses(Set.of("Letters", "Contracts"))));

		assertEquals(set(s1), filtered(all().withEdited(Edited.preset("today", CLOCK))));
		assertEquals(set(s1, s2), filtered(all().withEdited(Edited.preset("7d", CLOCK))));
		assertEquals(set(s1, s2, s3), filtered(all().withEdited(Edited.preset("30d", CLOCK))));
		assertEquals(set(s1, s2, s3, s4), filtered(all().withEdited(Edited.preset("year", CLOCK))));
		// Both ends count, the last day to its end
		assertEquals(set(s2, s3),
				filtered(all().withEdited(new Edited(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12)))));
		assertEquals(set(s1, s2), filtered(all().withEdited(new Edited(LocalDate.of(2026, 6, 2), null))));
		assertEquals(set(s4, s5), filtered(all().withEdited(new Edited(null, LocalDate.of(2026, 5, 31)))));

		assertEquals(set(s1, s2, s3, s4, s5), filtered(all().withClients(Set.of("998"))));
		assertEquals(Set.of(), filtered(all().withClients(Set.of("999"))));
		assertEquals(set(s1, s2, s3, s4, s5), filtered(all().withMatters(Set.of("998.1"))));
		assertEquals(Set.of(), filtered(all().withMatters(Set.of("998.2"))));
		assertEquals(Set.of(), filtered(all().withMatters(Set.of("1"))));

		// Combined, every filter applies
		assertEquals(set(s2), filtered(all().withKinds(Set.of("pdf")).withEdited(Edited.preset("7d", CLOCK))));
		assertEquals(set(s2), filtered(all().withAuthors(Set.of("zed")).withClasses(Set.of("Letters"))));
		assertEquals(Set.of(),
				filtered(all().withKinds(Set.of("word")).withAuthors(Set.of("zed")).withClasses(Set.of("Letters"))));

		// The total follows the filters, and so do the pages
		ListingQuery pdfs = ListingQuery.of(Sort.DOC_NUMBER, true, 0, 1).withKinds(Set.of("pdf"));
		ListedPage first = FolderListing.get().page(sorted, bob, pdfs);
		assertEquals(2, first.total());
		assertEquals(list(s2), first.docIds());
		assertEquals(list(s3), FolderListing.get().page(sorted, bob, pdfs.page(1, 1)).docIds());
	}

	@Test
	public void testOtherKind() throws Exception {
		sortedLayout();
		long text = copy(s1, sorted, "notes.txt");
		long none = copy(s1, sorted, "README");
		evict();

		assertEquals(set(text, none), filtered(all().withKinds(Set.of("other"))));
		assertEquals(set(text, none, s5), filtered(all().withKinds(Set.of("other", "email"))));
		Facets facets = FolderListing.get().facets(sorted, bob, all(), Set.of("type"));
		assertEquals(Long.valueOf(2), facets.counts().get("type").get("other"));
	}

	private Set<Long> filtered(ListingQuery query) throws Exception {
		ListedPage page = FolderListing.get().page(sorted, bob, query);
		assertEquals(page.docIds().size(), page.total());
		return new HashSet<>(page.docIds());
	}

	@Test
	public void testEachFacetIgnoresItsOwnFilter() throws Exception {
		sortedLayout();
		Set<String> which = Set.of("type", "author", "class", "client", "matter");
		Facets none = FolderListing.get().facets(sorted, bob, all(), which);
		Map<String, Long> types = Map.of("word", 1L, "pdf", 2L, "excel", 1L, "email", 1L);
		assertEquals(types, none.counts().get("type"));
		assertEquals(Map.of("abe", 2L, "zed", 2L), none.counts().get("author"));
		assertEquals(Map.of("Letters", 2L, "Contracts", 1L), none.counts().get("class"));
		assertEquals(Map.of("998", 5L), none.counts().get("client"));
		assertEquals(Map.of("998.1", 5L), none.counts().get("matter"));
		// In the order the filters show them, and most first
		assertEquals(List.of("type", "author", "class", "client", "matter"), List.copyOf(none.counts().keySet()));
		assertEquals(List.of("pdf", "email", "excel", "word"), List.copyOf(none.counts().get("type").keySet()));

		// Ticking Word leaves File Type as it was, and narrows the others
		Facets word = FolderListing.get().facets(sorted, bob, all().withKinds(Set.of("word")), which);
		assertEquals(types, word.counts().get("type"));
		assertEquals(Map.of("abe", 1L), word.counts().get("author"));
		assertEquals(Map.of("Letters", 1L), word.counts().get("class"));
		assertEquals(Map.of("998", 1L), word.counts().get("client"));
		assertEquals(Map.of("998.1", 1L), word.counts().get("matter"));

		// Word and Aaron Zed: each of the two counts applies the other filter alone
		Facets both = FolderListing.get().facets(sorted, bob,
				all().withKinds(Set.of("word")).withAuthors(Set.of("zed")), which);
		assertEquals(Map.of("pdf", 1L, "excel", 1L), both.counts().get("type"));
		assertEquals(Map.of("abe", 1L), both.counts().get("author"));
		assertEquals(Map.of(), both.counts().get("class"));

		// The matter's own filter doesn't narrow Matter; the client's does
		Facets elsewhere = FolderListing.get().facets(sorted, bob, all().withMatters(Set.of("998.2")), which);
		assertEquals(Map.of("998.1", 5L), elsewhere.counts().get("matter"));
		assertEquals(Map.of(), elsewhere.counts().get("type"));

		try {
			FolderListing.get().facets(sorted, bob, all(), Set.of("size"));
			fail("size isn't a facet");
		} catch (IllegalArgumentException expected) {
			// As it should
		}
	}

	@Test
	public void testListOfIds() throws Exception {
		sortedLayout();
		// A search folder's documents, already read-checked, in its own order
		List<Long> given = list(s4, s1, s5, s2);
		FolderListing listing = FolderListing.get();

		assertEquals(given, listing.page(given, ListingQuery.of(null, false, 0, 100)).docIds());
		assertEquals(list(s2, s5, s1, s4), listing.page(given, ListingQuery.of(null, true, 0, 100)).docIds());
		assertEquals(list(s1, s5), listing.page(given, ListingQuery.of(null, false, 1, 2)).docIds());
		assertEquals(4, listing.page(given, ListingQuery.of(null, false, 1, 2)).total());
		assertEquals(list(s2, s1, s5, s4), listing.page(given, query(Sort.TITLE, false)).docIds());
		ListedPage pdfs = listing.page(given, ListingQuery.of(null, false, 0, 100).withKinds(Set.of("pdf")));
		// Never one that wasn't given
		assertEquals(list(s2), pdfs.docIds());
		assertEquals(1, pdfs.total());

		Facets facets = listing.facets(given, all().withAuthors(Set.of("abe")), Set.of("type", "author"));
		assertEquals(Map.of("word", 1L, "email", 1L), facets.counts().get("type"));
		assertEquals(Map.of("abe", 2L, "zed", 2L), facets.counts().get("author"));
		assertTrue(listing.page(List.of(), all()).docIds().isEmpty());
		assertEquals(Map.of(), listing.facets(List.of(), all(), Set.of("type")).counts().get("type"));

		List<Long> tooMany = new ArrayList<>();
		for (long i = 1; i <= FolderListing.MAX_IDS + 1; i++)
			tooMany.add(i);
		try {
			listing.page(tooMany, all());
			fail("More than " + FolderListing.MAX_IDS + " ids");
		} catch (IllegalArgumentException expected) {
			// As it should
		}
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
