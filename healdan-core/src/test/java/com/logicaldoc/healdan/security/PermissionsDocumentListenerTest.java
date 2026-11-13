// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

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
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * A row of a document's own ACL that grants nothing is refused when the
 * document's permissions are saved: CE's {@code getAllowedPermissions} reads
 * such a row as no row, so it would keep no one out (spec
 * 2026-11-13-document-rows-that-grant-nothing).
 */
public class PermissionsDocumentListenerTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private User demo;

	private Group litigation;

	private Document doc;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		admin = fx.admin();
		demo = fx.user("demo");
		litigation = fx.group("Litigation");
		Folder store = fx.store("Main");
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		doc = fx.upload(documents, "will.txt", "the will", admin);
		awaitAsyncWork();
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
	public void testRefusesARowThatGrantsNothing() throws Exception {
		try {
			savePermissions(row(demo.getUserGroup()));
			fail("A row that grants nothing was saved");
		} catch (HealdanRuleException e) {
			assertEquals("Nothing is ticked for " + demo.getFullName() + ", and a row with nothing ticked keeps no "
					+ "one out: they'd still have the folder's access. To keep someone out of a document, move it to "
					+ "a folder they can't read.", e.getMessage());
		}
		assertEquals(0, rows());
	}

	@Test
	public void testNamesEveryRowThatGrantsNothing() throws Exception {
		try {
			savePermissions(row(litigation), row(demo.getUserGroup()), row(admin.getUserGroup(), Permission.READ));
			fail("Rows that grant nothing were saved");
		} catch (HealdanRuleException e) {
			assertTrue(e.getMessage(), e.getMessage()
					.startsWith("Nothing is ticked for " + demo.getFullName() + " and Litigation, and"));
		}
		assertEquals(0, rows());
	}

	@Test
	public void testSavesRowsThatGrantSomething() throws Exception {
		// A row without READ still decides for its group, so it isn't refused
		savePermissions(row(demo.getUserGroup(), Permission.PREVIEW), row(litigation, Permission.READ));
		assertEquals(2, rows());
		assertEquals(EnumSet.of(Permission.PREVIEW),
				DocumentDAO.get().getAllowedPermissions(doc.getId(), demo.getId()));
	}

	@Test
	public void testOtherSavesPass() throws Exception {
		// A row CE's own copies could write, past the listeners
		fx.documentRow(doc.getId(), demo.getUserGroup().getId());

		Document current = DocumentDAO.get().findById(doc.getId(), true);
		DocumentDAO.get().initialize(current);
		current.setComment("Signed copy");
		DocumentHistory history = fx.documentHistory(admin);
		history.setEvent(DocumentEvent.CHANGED);
		DocumentDAO.get().store(current, history);
		awaitAsyncWork();

		assertEquals("Signed copy", DocumentDAO.get().findById(doc.getId()).getComment());
		assertEquals(1, rows());
	}

	private static DocumentAccessControlEntry row(Group group, Permission... permissions) {
		DocumentAccessControlEntry ace = new DocumentAccessControlEntry(group.getId());
		ace.grantPermissions(permissions.length == 0 ? EnumSet.noneOf(Permission.class)
				: EnumSet.copyOf(List.of(permissions)));
		return ace;
	}

	/** Replaces the document's ACL, as its Security tab does */
	private void savePermissions(DocumentAccessControlEntry... rows) throws Exception {
		Document current = DocumentDAO.get().findById(doc.getId(), true);
		DocumentDAO.get().initialize(current);
		current.getAccessControlList().clear();
		current.getAccessControlList().addAll(Set.of(rows));
		DocumentHistory history = fx.documentHistory(admin);
		history.setEvent(DocumentEvent.PERMISSION);
		DocumentDAO.get().store(current, history);
		awaitAsyncWork();
	}

	private int rows() {
		return jdbc().queryForObject("select count(*) from ld_document_acl where ld_docid = ?", Integer.class,
				doc.getId());
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private static void awaitAsyncWork() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 20000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				fail("Background version saves didn't finish");
			Thread.sleep(50);
		}
	}
}
