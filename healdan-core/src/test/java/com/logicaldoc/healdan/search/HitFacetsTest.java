// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
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

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
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
import com.logicaldoc.healdan.listing.Facets;
import com.logicaldoc.healdan.listing.FolderListing;
import com.logicaldoc.healdan.listing.ListingQuery;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.security.FolderReads;
import com.logicaldoc.healdan.support.BulkHits;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * A search's counts (M6 spec section 9.2): taken over many hits a thousand at
 * a time, they are the counts of the same documents taken at once, and they
 * never count a document the caller can't read, even when it is passed in.
 */
public class HitFacetsTest extends AbstractHealdanTestCase {

	private static final int HITS = 2500;

	private static final Set<String> ALL = Set.copyOf(Facets.NAMES);

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private User alice;

	private Folder open;

	/** Open's documents, 2,500 of them: three chunks */
	private final List<Long> hits = new ArrayList<>();

	/** In Closed, which only the admin reads */
	private List<Long> closed;

	/** In Open, deleted */
	private List<Long> deleted;

	/** In Open, not published */
	private List<Long> unpublished;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		admin = fx.admin();
		alice = fx.user("alice");
		User jclarke = fx.user("jclarke");
		User treeve = fx.user("treeve");
		Folder store = fx.store("Main");
		fx.grant(store, alice.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		open = fx.folder(documents, "Open");
		Folder secret = fx.folder(documents, "Closed");
		secret = FolderDAO.get().findById(secret.getId(), true);
		secret.setSecurityRef(null);
		FolderDAO.get().store(secret, fx.folderHistory(admin));
		fx.grant(secret, admin.getUserGroup(), Permission.READ, Permission.WRITE);

		Document first = fx.upload(open, "first.pdf", "first", admin);
		awaitAsyncWork();
		BulkHits bulk = new BulkHits();
		String[] types = { ".pdf", ".docx", ".xlsx", ".eml", ".odt" };
		hits.add(first.getId());
		// The hit at index j is a types[j % 5], the first included
		hits.addAll(bulk.copies(first.getId(), open, HITS - 1, i -> "copy-" + i + types[(i + 1) % types.length]));

		// Authors, classes and matters spread unevenly, across the chunks
		for (String name : new String[] { HealdanConstants.ATTR_AUTHOR, HealdanConstants.ATTR_DOCCLASS })
			bulk.set(hits, name, Attribute.Type.STRING, null, null);
		bulk.set(hits, HealdanConstants.ATTR_CLIENT, Attribute.Type.STRING, "998", null);
		bulk.set(hits, HealdanConstants.ATTR_MATTER, Attribute.Type.STRING, "1", null);
		bulk.set(hits.subList(0, 1300), HealdanConstants.ATTR_AUTHOR, Attribute.Type.USER, jclarke.getUsername(),
				jclarke.getId());
		bulk.set(hits.subList(1300, 2200), HealdanConstants.ATTR_AUTHOR, Attribute.Type.USER, treeve.getUsername(),
				treeve.getId());
		List<Long> letters = new ArrayList<>();
		for (int i = 0; i < HITS; i += 3)
			letters.add(hits.get(i));
		bulk.set(letters, HealdanConstants.ATTR_DOCCLASS, Attribute.Type.STRING, "Letters", null);
		bulk.set(hits.subList(900, 1700), HealdanConstants.ATTR_DOCCLASS, Attribute.Type.STRING, "Advice: Tax (UK)",
				null);
		bulk.set(hits.subList(2000, HITS), HealdanConstants.ATTR_MATTER, Attribute.Type.STRING, "2", null);
		bulk.set(hits.subList(2400, HITS), HealdanConstants.ATTR_CLIENT, Attribute.Type.STRING, "999", null);

		closed = bulk.copies(first.getId(), secret, 5, i -> "closed-" + i + ".pdf");
		bulk.set(closed, HealdanConstants.ATTR_AUTHOR, Attribute.Type.USER, treeve.getUsername(), treeve.getId());
		deleted = bulk.copies(first.getId(), open, 4, i -> "deleted-" + i + ".pdf");
		unpublished = bulk.copies(first.getId(), open, 3, i -> "unpublished-" + i + ".pdf");
		JdbcTemplate jdbc = jdbc();
		for (long id : deleted)
			jdbc.update("update ld_document set ld_deleted = 1 where ld_id = ?", id);
		for (long id : unpublished)
			jdbc.update("update ld_document set ld_published = 0 where ld_id = ?", id);
		List<Long> all = new ArrayList<>(hits);
		all.addAll(closed);
		all.addAll(deleted);
		all.addAll(unpublished);
		bulk.evict(all);
	}

	@After
	@Override
	public void tearDown() throws IOException {
		try {
			awaitAsyncWork();
		} catch (Exception e) {
			// The database goes anyway
		}
		super.tearDown();
	}

	@Test
	public void testChunksCountAsOne() throws Exception {
		List<ListingQuery> queries = List.of(ListingQuery.of(null, false, 0, 0),
				ListingQuery.of(null, false, 0, 0).withKinds(Set.of("pdf", "word")),
				ListingQuery.of(null, false, 0, 0).withAuthors(Set.of("treeve")).withClasses(Set.of("Letters")),
				ListingQuery.of(null, false, 0, 0).withMatters(Set.of("998.2", "999.2")));
		for (ListingQuery query : queries) {
			Facets inOneGo = FolderListing.get().facets(open, alice, query, ALL);
			assertEquals(query.toString(), inOneGo, HitFacets.count(hits, alice, query, ALL));
		}

		Map<String, Map<String, Long>> counts = HitFacets.count(hits, alice, ListingQuery.of(null, false, 0, 0), ALL)
				.counts();
		assertEquals(Map.of("pdf", 500L, "word", 500L, "excel", 500L, "email", 500L, "opendocument", 500L),
				counts.get(Facets.TYPE));
		assertEquals(Map.of("jclarke", 1300L, "treeve", 900L), counts.get(Facets.AUTHOR));
		assertEquals(Map.of("998.1", 2000L, "998.2", 400L, "999.2", 100L), counts.get(Facets.MATTER));
		assertEquals(Map.of("998", 2400L, "999", 100L), counts.get(Facets.CLIENT));
		long letters = (HITS + 2) / 3;
		assertEquals(Map.of("Advice: Tax (UK)", 800L, "Letters", letters - countEveryThird(900, 1700)),
				counts.get(Facets.CLASS));
	}

	/** How many of the indexes from {@code from} to before {@code to} are multiples of 3 */
	private static long countEveryThird(int from, int to) {
		long n = 0;
		for (int i = from; i < to; i++)
			if (i % 3 == 0)
				n++;
		return n;
	}

	@Test
	public void testWhatCantBeReadIsNeverCounted() throws Exception {
		ListingQuery none = ListingQuery.of(null, false, 0, 0);
		Facets readable = HitFacets.count(hits, alice, none, ALL);

		// Passed in by mistake: Closed's documents, deleted ones and unpublished ones
		List<Long> mistaken = new ArrayList<>(hits);
		mistaken.addAll(closed);
		mistaken.addAll(deleted);
		mistaken.addAll(unpublished);
		mistaken.addAll(hits.subList(0, 10));
		assertEquals(readable, HitFacets.count(mistaken, alice, none, ALL));
		assertEquals(Map.of(), HitFacets.count(closed, alice, none, ALL).counts().get(Facets.AUTHOR));

		// The admin reads Closed and sees unpublished documents; nobody is shown the deleted ones
		Map<String, Long> adminTypes = HitFacets.count(mistaken, admin, none, ALL).counts().get(Facets.TYPE);
		assertEquals(500L + 5 + 3, (long) adminTypes.get("pdf"));
		assertEquals(905L, (long) HitFacets.count(mistaken, admin, none, ALL).counts().get(Facets.AUTHOR)
				.get("treeve"));
		assertNotEquals(readable, HitFacets.count(mistaken, admin, none, ALL));
	}

	/**
	 * The documents' own ACLs decide as {@link AccessChecks#readableDocuments}
	 * does, in a folder the caller reads and in one it doesn't
	 */
	@Test
	public void testOwnAclsDecideAsTheJavaRuleDoes() throws Exception {
		BulkHits bulk = new BulkHits();
		Group it = fx.group("IT");
		Folder secret = FolderDAO.get().findById(
				jdbc().queryForObject("select ld_folderid from ld_document where ld_id = ?", Long.class, closed.get(0)));
		// In Open, which Alice reads: her group's row grants Download alone; only IT's; hers grants nothing
		long downloadOnly = bulk.copies(hits.get(0), open, 1, i -> "download.pdf").get(0);
		acl(downloadOnly, alice.getUserGroup().getId(), 0, 1);
		long otherGroup = bulk.copies(hits.get(0), open, 1, i -> "other.pdf").get(0);
		acl(otherGroup, it.getId(), 1, 0);
		long nothing = bulk.copies(hits.get(0), open, 1, i -> "nothing.pdf").get(0);
		acl(nothing, alice.getUserGroup().getId(), 0, 0);
		// In Closed, which she doesn't: her group's row grants Read
		long granted = bulk.copies(hits.get(0), secret, 1, i -> "granted.pdf").get(0);
		acl(granted, alice.getUserGroup().getId(), 1, 0);
		long notGranted = bulk.copies(hits.get(0), secret, 1, i -> "notgranted.pdf").get(0);
		acl(notGranted, alice.getUserGroup().getId(), 0, 1);
		List<Long> cases = List.of(downloadOnly, otherGroup, nothing, granted, notGranted);
		bulk.evict(cases);

		List<Long> javaReadable = new ArrayList<>();
		for (Folder folder : List.of(open, secret)) {
			List<Document> inFolder = new ArrayList<>();
			for (long id : cases) {
				Document d = DocumentDAO.get().findById(id);
				if (d.getFolder().getId() == folder.getId())
					inFolder.add(d);
			}
			AccessChecks.readableDocuments(folder, inFolder, alice).forEach(d -> javaReadable.add(d.getId()));
		}
		assertEquals(Set.of(otherGroup, nothing, granted), Set.copyOf(javaReadable));
		// The same answers for documents of any folders, each folder's READ asked once
		List<Document> mixed = new ArrayList<>();
		for (long id : cases)
			mixed.add(DocumentDAO.get().findById(id));
		assertEquals(Set.copyOf(javaReadable), Set.copyOf(AccessChecks
				.readableDocuments(mixed, new FolderReads(alice)).stream().map(Document::getId).toList()));

		ListingQuery none = ListingQuery.of(null, false, 0, 0);
		assertEquals(Map.of("pdf", 3L), HitFacets.count(cases, alice, none, Set.of(Facets.TYPE)).counts()
				.get(Facets.TYPE));
		for (long id : cases)
			assertEquals(Long.toString(id), javaReadable.contains(id) ? Map.of("pdf", 1L) : Map.of(),
					HitFacets.count(List.of(id), alice, none, Set.of(Facets.TYPE)).counts().get(Facets.TYPE));
	}

	/** Gives the group a row of the document's own ACL */
	private static void acl(long docId, long groupId, int read, int download) {
		jdbc().update("""
				insert into ld_document_acl (ld_docid, ld_groupid, ld_read, ld_write, ld_security, ld_immutable,
				    ld_delete, ld_customid, ld_rename, ld_sign, ld_preview, ld_archive, ld_workflow, ld_download,
				    ld_calendar, ld_subscription, ld_print, ld_password, ld_move, ld_email, ld_automation,
				    ld_readingreq, ld_revision)
				values (?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0)
				""", docId, groupId, read, download);
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	@Test
	public void testNoHits() throws Exception {
		Facets none = HitFacets.count(List.of(), alice, ListingQuery.of(null, false, 0, 0), Set.of("type", "author"));
		assertEquals(Map.of("type", Map.of(), "author", Map.of()), none.counts());
		assertEquals(List.of("type", "author"), List.copyOf(none.counts().keySet()));
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
