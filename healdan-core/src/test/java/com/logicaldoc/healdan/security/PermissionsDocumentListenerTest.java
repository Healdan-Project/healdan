// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.HealdanHooks;
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
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
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

	private static final String KEEPS_NO_ONE_OUT = " A row that grants nothing keeps no one out: they'd still have the "
			+ "folder's access. Tick what they may do, or remove the row. To keep someone out of a document, move it "
			+ "to a folder they can't read.";

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
		HealdanFixtures.awaitVersionSaves();
	}

	@After
	@Override
	public void tearDown() throws IOException {
		try {
			HealdanFixtures.awaitVersionSaves();
		} catch (Exception e) {
			// The database goes anyway
		}
		super.tearDown();
	}

	@Test
	public void testRefusesARowThatGrantsNothing() throws Exception {
		savePermissions(row(litigation, Permission.READ));
		try {
			savePermissions(row(demo.getUserGroup()));
			fail("A row that grants nothing was saved");
		} catch (HealdanRuleException e) {
			assertEquals("Nothing is ticked for " + demo.getFullName() + "." + KEEPS_NO_ONE_OUT, e.getMessage());
		}
		// The refused save left the rows as they were
		assertEquals(1, rows());
		assertEquals(List.of(litigation.getId()), groupsThatRead());
	}

	@Test
	public void testNamesEveryRowThatGrantsNothing() throws Exception {
		savePermissions(row(litigation, Permission.READ, Permission.WRITE));
		try {
			savePermissions(row(litigation), row(demo.getUserGroup()), row(admin.getUserGroup(), Permission.READ));
			fail("Rows that grant nothing were saved");
		} catch (HealdanRuleException e) {
			assertEquals("Nothing is ticked for " + demo.getFullName() + " and Litigation." + KEEPS_NO_ONE_OUT,
					e.getMessage());
		}
		assertEquals(1, rows());
		assertEquals(List.of(litigation.getId()), groupsThatRead());
	}

	@Test
	public void testRefusesAReadOnlyAccountsRowWithOnlyChangesTicked() throws Exception {
		User reader = fx.user("reader");
		reader.setType(User.Type.READONLY);
		UserDAO.get().store(reader);

		// CE takes Write and Move off a read-only account's row before the listeners see it
		try {
			savePermissions(row(reader.getUserGroup(), Permission.WRITE, Permission.MOVE));
			fail("A read-only account's row that grants nothing it can have was saved");
		} catch (HealdanRuleException e) {
			assertEquals("Nothing is ticked for " + reader.getFullName() + " that a read-only account can have: "
					+ "Write, Delete, Move and the other changes don't count for one." + KEEPS_NO_ONE_OUT,
					e.getMessage());
		}
		assertEquals(0, rows());

		savePermissions(row(reader.getUserGroup(), Permission.READ, Permission.WRITE));
		assertEquals(List.of(reader.getUserGroup().getId()), groupsThatRead());
		assertEquals(0, (int) jdbc().queryForObject("select ld_write from ld_document_acl where ld_docid = ?",
				Integer.class, doc.getId()));
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
		HealdanFixtures.awaitVersionSaves();

		assertEquals("Signed copy", DocumentDAO.get().findById(doc.getId()).getComment());
		assertEquals(1, rows());
	}

	/**
	 * The Classic Bulk Update's loop logs a refusal and goes on, so it checks
	 * its rows first through the hook, before CE has taken anything off a
	 * guest's row
	 */
	@Test
	public void testTheHookChecksRowsBeforeTheyAreStored() throws Exception {
		User reader = fx.user("reader");
		reader.setType(User.Type.READONLY);
		UserDAO.get().store(reader);

		HealdanRuleException e = assertThrows(HealdanRuleException.class,
				() -> HealdanHooks.get().beforeDocumentPermissions(List.of(row(litigation, Permission.READ),
						row(demo.getUserGroup()), row(reader.getUserGroup(), Permission.WRITE, Permission.MOVE))));
		assertEquals("Nothing is ticked for " + demo.getFullName() + ". Nothing is ticked for " + reader.getFullName()
				+ " that a read-only account can have: Write, Delete, Move and the other changes don't count for one."
				+ KEEPS_NO_ONE_OUT, e.getMessage());

		HealdanHooks.get().beforeDocumentPermissions(
				List.of(row(litigation, Permission.READ), row(reader.getUserGroup(), Permission.READ, Permission.WRITE)));
		assertEquals(0, rows());
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
		HealdanFixtures.awaitVersionSaves();
	}

	private int rows() {
		return jdbc().queryForObject("select count(*) from ld_document_acl where ld_docid = ?", Integer.class,
				doc.getId());
	}

	/** @return the groups whose stored row grants Read */
	private List<Long> groupsThatRead() {
		return jdbc().queryForList(
				"select ld_groupid from ld_document_acl where ld_docid = ? and ld_read = 1 order by ld_groupid",
				Long.class, doc.getId());
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}
}
