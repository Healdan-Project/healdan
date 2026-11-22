// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.spring.Context;

/**
 * Reading a document, recorded as {@code event.viewed} (Admin app spec section 8.8): the memory of recent views,
 * bounded, and a write that fails without failing the read or a caller's transaction. The routes that record views
 * are tested in healdan-api ({@code PreviewResourceTest}, {@code MessageResourceTest}).
 */
public class DocumentViewsTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private User clerk;

	private Session session;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		clerk = fx.user("clerk");
		Folder store = fx.store("Client Work");
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		session = SessionManager.get().newSession(clerk.getUsername(), HealdanFixtures.PASSWORD,
				new Client("web", "192.0.2.7", "clerk.example"));
	}

	@After
	public void putBack() {
		DocumentViewsTestHooks.reset();
	}

	private Document file(String name) throws Exception {
		Document d = fx.upload(documents, name, "The text of " + name, fx.admin());
		HealdanFixtures.awaitVersionSaves();
		return DocumentDAO.get().findById(d.getId());
	}

	private List<Map<String, Object>> views() {
		return jdbc.queryForList("select * from ld_history where ld_event = 'event.viewed' order by ld_id");
	}

	@Test
	public void testAnOlderVersionIsRecordedAsTheVersionThatMadeItsFile() throws Exception {
		Document d = file("brief.txt");
		DocumentManager.get().checkout(d.getId(), fx.documentHistory(fx.admin()));
		DocumentManager.get().checkin(d.getId(),
				new ByteArrayInputStream("The second text".getBytes(StandardCharsets.UTF_8)), "brief v2.txt", false,
				null, fx.documentHistory(fx.admin()));
		HealdanFixtures.awaitVersionSaves();
		Document current = DocumentDAO.get().findById(d.getId());

		DocumentViews.get().viewed(current, clerk, session, "1.0");
		DocumentViews.get().viewed(current, fx.admin(), session, current.getFileVersion());

		List<Map<String, Object>> views = views();
		assertEquals(2, views.size());
		assertEquals("1.0", views.get(0).get("ld_version"));
		assertEquals("1.0", views.get(0).get("ld_fileversion"));
		assertEquals("brief.txt", views.get(0).get("ld_filename"));
		assertEquals(documents.getId(), ((Number) views.get(0).get("ld_folderid")).longValue());
		// The current file is the current version's
		assertEquals(current.getVersion(), views.get(1).get("ld_version"));
		assertEquals(current.getFileVersion(), views.get(1).get("ld_fileversion"));
		assertEquals("brief v2.txt", views.get(1).get("ld_filename"));
	}

	@Test
	public void testEntriesPastTheWindowAreDropped() throws Exception {
		Document first = file("first.txt");
		Document second = file("second.txt");
		Document third = file("third.txt");
		DocumentViews views = DocumentViews.get();
		views.viewed(first, clerk, session, first.getFileVersion());
		views.viewed(second, clerk, session, second.getFileVersion());
		assertEquals(2, views.remembered());

		DocumentViewsTestHooks.passTime(DocumentViews.WINDOW);
		views.viewed(third, clerk, session, third.getFileVersion());

		// Dropped as the third was written, and so recorded again
		assertEquals(1, views.remembered());
		views.viewed(first, clerk, session, first.getFileVersion());
		assertEquals(4, views().size());
		assertEquals(2, views.remembered());
	}

	@Test
	public void testTheMemoryHoldsAtMostItsCap() {
		DocumentViews views = DocumentViews.get();
		for (long docId = 1; docId <= DocumentViews.MAX_REMEMBERED + 1; docId++)
			assertNotNull(views.claim(clerk.getId(), docId));

		assertEquals(DocumentViews.MAX_REMEMBERED, views.remembered());
		// The oldest went, and the next oldest is still remembered
		assertNull(views.claim(clerk.getId(), 2));
		assertNotNull(views.claim(clerk.getId(), 1));
		assertEquals(DocumentViews.MAX_REMEMBERED, views.remembered());
	}

	@Test
	public void testAFailedWriteIsWarnedOfAndNotRemembered() throws Exception {
		Document d = file("brief.txt");
		DocumentViewsTestHooks.failWrites();

		try (LogCapture log = LogCapture.of(DocumentViews.class)) {
			DocumentViews.get().viewed(d, clerk, session, d.getFileVersion());

			assertEquals(List.of("WARN Couldn't record a view of document " + d.getId()
					+ ": org.springframework.dao.DataAccessResourceFailureException"), log.lines());
		}
		assertEquals(0, views().size());
		assertEquals(0, DocumentViews.get().remembered());

		// So the next read tries again
		DocumentViewsTestHooks.reset();
		DocumentViews.get().viewed(d, clerk, session, d.getFileVersion());
		assertEquals(1, views().size());
	}

	@Test
	public void testAFailedWriteLeavesTheCallersTransactionToGoOn() throws Exception {
		Document d = file("brief.txt");
		// A statement that fails in the write's own transaction, which on PostgreSQL ends that transaction
		DocumentViewsTestHooks.failWrites(() -> jdbc.queryForObject("select cast('x' as integer)", Integer.class));
		TransactionTemplate transaction = new TransactionTemplate(Context.get(PlatformTransactionManager.class));

		try (LogCapture log = LogCapture.of(DocumentViews.class)) {
			transaction.executeWithoutResult(status -> {
				jdbc.update("insert into hd_setting (name, value) values ('test.before', 'kept')");
				DocumentViews.get().viewed(d, clerk, session, d.getFileVersion());
				jdbc.update("insert into hd_setting (name, value) values ('test.after', 'kept')");
			});

			assertEquals(1, log.lines().size());
		}
		assertEquals(2, jdbc.queryForObject(
				"select count(*) from hd_setting where name in ('test.before', 'test.after') and value = 'kept'",
				Integer.class).intValue());
		assertEquals(0, views().size());
	}
}
