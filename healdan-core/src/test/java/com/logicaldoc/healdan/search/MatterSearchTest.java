package com.logicaldoc.healdan.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.Search;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.searchengine.SearchOptions;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterNumbering;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NumberingMode;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * Spec §5.1 and §8.1: the Matters and Clients scopes list live matter
 * workspaces the user can read, as folder hits.
 */
public class MatterSearchTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	/** May read the Store "Main" only */
	private User bob;

	private Folder store;

	private Client healdan;

	private Client acme;

	@Before
	public void createLayout() throws Exception {
		admin = fx.admin();
		Group readers = fx.group("readers");
		bob = fx.user("bob", readers.getName());
		store = fx.store("Main");
		fx.grant(store, readers, Permission.READ);
		healdan = fx.client(998, "Healdan");
		acme = fx.client(999, "Acme Healthcare");
	}

	@After
	@Override
	public void tearDown() throws IOException {
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	@Test
	public void testHitsAreTheWorkspaces() throws Exception {
		Matter demo = fx.matter(healdan, 1, "Healdan Demo", store);

		List<Hit> hits = search(HealdanConstants.SEARCH_MATTERS, "998.1", admin, 40).getHits();

		assertEquals(1, hits.size());
		Hit hit = hits.get(0);
		assertEquals("folder", hit.getType());
		assertEquals(demo.getFolderId(), hit.getId());
		// The workspace itself, so that opening the hit's folder opens the matter
		assertEquals(demo.getFolderId(), hit.getFolder().getId());
		assertEquals("998.1 \u2013 Healdan Demo", hit.getFileName());
		assertEquals(FolderDAO.get().findById(demo.getFolderId()).getName(), hit.getFolder().getName());
	}

	@Test
	public void testPerClientNumberListsEveryMatterWithIt() throws Exception {
		Matter h1 = fx.matter(healdan, 1, "Healdan One", store);
		fx.matter(healdan, 2, "Healdan Two", store);
		Matter a1 = fx.matter(acme, 1, "Acme One", store);
		Matter o1 = fx.matter(fx.client(1000, "Other"), 1, "Other One", store);

		assertEquals(List.of(h1.getFolderId(), a1.getFolderId(), o1.getFolderId()),
				ids(HealdanConstants.SEARCH_MATTERS, "1", admin));

		// At most the hits asked for, and the search says there are more
		Search limited = search(HealdanConstants.SEARCH_MATTERS, "1", admin, 2);
		assertEquals(List.of(h1.getFolderId(), a1.getFolderId()), ids(limited));
		assertTrue(limited.isMoreHitsPresent());
		Search exact = search(HealdanConstants.SEARCH_MATTERS, "1", admin, 3);
		assertEquals(3, exact.getHits().size());
		assertFalse(exact.isMoreHitsPresent());
	}

	@Test
	public void testSystemWideNumberIsASingleHit() throws Exception {
		MatterNumbering.get().setMode(NumberingMode.SYSTEM_WIDE);
		Matter h1 = fx.matter(healdan, 1, "Healdan One", store);
		Matter a2 = fx.matter(acme, 2, "Acme Two", store);

		assertEquals(List.of(h1.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, "1", admin));
		assertEquals(List.of(a2.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, "2", admin));
	}

	@Test
	public void testClientDotMatterIsExact() throws Exception {
		Matter h1 = fx.matter(healdan, 1, "Healdan One", store);
		Matter h10 = fx.matter(healdan, 10, "Healdan Ten", store);
		fx.matter(acme, 1, "Acme One", store);

		assertEquals(List.of(h1.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, "998.1", admin));
		assertEquals(List.of(h1.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, " 998.01 ", admin));
		assertEquals(List.of(h10.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, "998.10", admin));
		assertEquals(List.of(), ids(HealdanConstants.SEARCH_MATTERS, "998.3", admin));
		assertEquals(List.of(), ids(HealdanConstants.SEARCH_MATTERS, "997.1", admin));
	}

	@Test
	public void testTextIsCaseInsensitive() throws Exception {
		Matter demo = fx.matter(healdan, 1, "Healdan Demo", store);
		Matter other = fx.matter(acme, 1, "Other matter", store);
		MatterService.get().update(other.getId(), "Other matter", "Merger REVIEW for Demo Ltd", admin.getId(), admin);
		Matter underscore = fx.matter(acme, 2, "A_B deal", store);
		fx.matter(acme, 3, "AxB deal", store);

		assertEquals(List.of(demo.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, "healdan demo", admin));
		assertEquals(List.of(demo.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, "HEALDAN DEMO", admin));
		// The description too
		assertEquals(List.of(other.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, "merger review", admin));
		assertEquals(List.of(demo.getFolderId(), other.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, "eMo", admin));
		// What the user types is text, never a pattern
		assertEquals(List.of(underscore.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, "a_b", admin));
		assertEquals(List.of(), ids(HealdanConstants.SEARCH_MATTERS, "%", admin));
		assertEquals(List.of(), ids(HealdanConstants.SEARCH_MATTERS, "  ", admin));
	}

	@Test
	public void testDeletedAndUnreadableMattersAreHidden() throws Exception {
		Folder secret = fx.store("Secret");
		Matter readable = fx.matter(healdan, 1, "Alpha One", store);
		Matter hidden = fx.matter(healdan, 2, "Alpha Two", secret);
		Matter trashed = fx.matter(healdan, 3, "Alpha Three", store);
		Matter deleted = fx.matter(healdan, 4, "Alpha Four", store);
		FolderDAO.get().delete(trashed.getFolderId(), fx.folderHistory(admin));
		MatterDAO.get().delete(deleted.getId(), Matter.DELETED_CODE_DEFAULT);

		assertEquals(List.of(readable.getFolderId(), hidden.getFolderId()),
				ids(HealdanConstants.SEARCH_MATTERS, "alpha", admin));
		assertEquals(List.of(readable.getFolderId()), ids(HealdanConstants.SEARCH_MATTERS, "alpha", bob));
		assertEquals(List.of(), ids(HealdanConstants.SEARCH_MATTERS, "998.2", bob));
		assertEquals(List.of(), ids(HealdanConstants.SEARCH_MATTERS, "998.3", admin));
		assertEquals(List.of(), ids(HealdanConstants.SEARCH_MATTERS, "998.4", admin));
		assertEquals(List.of(readable.getFolderId()), ids(HealdanConstants.SEARCH_CLIENTS, "998", bob));

		// Unreadable matters don't count towards the hits asked for
		Matter second = fx.matter(healdan, 5, "Alpha Five", store);
		Search limited = search(HealdanConstants.SEARCH_MATTERS, "alpha", bob, 2);
		assertEquals(List.of(readable.getFolderId(), second.getFolderId()), ids(limited));
		assertFalse(limited.isMoreHitsPresent());
	}

	@Test
	public void testClientsByNumberAndByName() throws Exception {
		Matter h1 = fx.matter(healdan, 1, "One", store);
		Matter h2 = fx.matter(healdan, 2, "Two", store);
		Matter a1 = fx.matter(acme, 1, "One", store);
		Matter s1 = fx.matter(fx.client(7, "Seven"), 1, "One", store);
		Matter g1 = fx.matter(fx.client(1000, "Agent 007"), 1, "One", store);

		// The number exactly, or the name containing the text
		assertEquals(List.of(h1.getFolderId(), h2.getFolderId()), ids(HealdanConstants.SEARCH_CLIENTS, "998", admin));
		assertEquals(List.of(s1.getFolderId(), g1.getFolderId()), ids(HealdanConstants.SEARCH_CLIENTS, " 7 ", admin));
		assertEquals(List.of(), ids(HealdanConstants.SEARCH_CLIENTS, "99", admin));
		assertEquals(List.of(h1.getFolderId(), h2.getFolderId(), a1.getFolderId()),
				ids(HealdanConstants.SEARCH_CLIENTS, "HEAL", admin));
		assertEquals(List.of(a1.getFolderId()), ids(HealdanConstants.SEARCH_CLIENTS, "acme healthcare", admin));
		assertEquals(List.of(), ids(HealdanConstants.SEARCH_CLIENTS, "One", admin));

		Search limited = search(HealdanConstants.SEARCH_CLIENTS, "heal", admin, 1);
		assertEquals(List.of(h1.getFolderId()), ids(limited));
		assertTrue(limited.isMoreHitsPresent());
	}

	private static Search search(int type, String expression, User user, int maxHits) throws Exception {
		SearchOptions options = Search.newOptions(type);
		options.setExpression(expression);
		options.setUserId(user.getId());
		options.setMaxHits(maxHits);
		Search search = Search.get(options);
		search.search();
		return search;
	}

	private static List<Long> ids(int type, String expression, User user) throws Exception {
		return ids(search(type, expression, user, 40));
	}

	private static List<Long> ids(Search search) {
		return search.getHits().stream().map(Hit::getId).collect(Collectors.toList());
	}
}
