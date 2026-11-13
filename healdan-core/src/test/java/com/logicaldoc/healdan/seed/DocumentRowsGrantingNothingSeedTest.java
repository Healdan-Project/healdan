// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanStartup;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

public class DocumentRowsGrantingNothingSeedTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private static List<Long> groups(long docId) {
		return jdbc().queryForList("select ld_groupid from ld_document_acl where ld_docid = ? order by ld_groupid",
				Long.class, docId);
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
	public void testDeletesOnlyRowsThatGrantNothing() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		User admin = fx.admin();
		Folder store = fx.store("Main");
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		long first = fx.upload(documents, "first.txt", "first", admin).getId();
		long second = fx.upload(documents, "second.txt", "second", admin).getId();
		HealdanFixtures.awaitVersionSaves();
		long nothing = fx.group("Nothing").getId();
		fx.documentRow(first, nothing);
		fx.documentRow(second, nothing);

		// A row for each permission a document's row can grant, from CE's entity, not Healdan's column list
		DocumentAccessControlEntry every = new DocumentAccessControlEntry();
		every.grantPermissions(EnumSet.allOf(Permission.class));
		List<Long> granting = new ArrayList<>();
		for (Permission permission : every.grantedPermissions()) {
			long group = fx.group("Only " + permission).getId();
			fx.documentRow(first, group, permission);
			granting.add(group);
		}
		assertEquals(21, granting.size());
		Collections.sort(granting);

		// Back to the state before the seed
		jdbc().update("delete from hd_setting where name = 'seed.document-rows-granting-nothing'");
		Context.get(HealdanStartup.class).run();

		assertEquals(granting, groups(first));
		assertEquals(List.of(), groups(second));
		assertEquals("done", jdbc().queryForObject(
				"select value from hd_setting where name = 'seed.document-rows-granting-nothing'", String.class));
	}
}
