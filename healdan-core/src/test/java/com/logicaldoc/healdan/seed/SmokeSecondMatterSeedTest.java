// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

public class SmokeSecondMatterSeedTest extends AbstractHealdanTestCase {

	private SmokeSecondMatterSeed seed(String flag) {
		return new SmokeSecondMatterSeed(() -> flag);
	}

	@Test
	public void testMakesMatter14WithTheTemplatesPermissions() throws Exception {
		SeedRunner runner = Context.get(SeedRunner.class);
		runner.runPending(List.of(new SmokeFixtureSeed(() -> "true")));
		runner.runPending(List.of(new SmokeOrganisingSeed(() -> "true")));

		SmokeSecondMatterSeed seed = seed("true");
		assertEquals("smoke-second-matter", seed.name());
		assertTrue(seed.enabled());
		runner.runPending(List.of(seed));

		long client = ClientDAO.get().findByNumber(1).getId();
		Matter matter = MatterDAO.get().findByClientAndNumber(client, 4);
		assertNotNull(matter);
		assertEquals("Second Matter", matter.getName());

		FolderDAO dao = FolderDAO.get();
		Folder store = dao.findByNameAndParentId("Smoke", dao.findRoot(Tenant.DEFAULT_ID).getId()).get(0);
		assertEquals(store.getId(), dao.findById(matter.getFolderId()).getParentId());
		assertEquals(1, dao.findByName(dao.findById(matter.getFolderId()), "Documents", Tenant.DEFAULT_ID, false).size());

		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		Set<Permission> expected = new HashSet<>(
				Set.of(Permission.READ, Permission.WRITE, Permission.ADD, Permission.DOWNLOAD, Permission.PREVIEW));
		expected.addAll(SmokeOrganisingSeed.ORGANISING);
		FolderAccessControlEntry ace = dao.findById(matter.getFolderId(), true).getAccessControlEntry(everyone.getId());
		assertNotNull(ace);
		assertEquals(expected, ace.grantedPermissions());
		assertEquals("done", HealdanSettings.get().get("seed.smoke-second-matter"));
	}

	@Test
	public void testAnExistingMatter14IsLeftAlone() throws Exception {
		SeedRunner runner = Context.get(SeedRunner.class);
		runner.runPending(List.of(new SmokeFixtureSeed(() -> "true")));
		runner.runPending(List.of(seed("true")));
		Matter first = MatterDAO.get().findByClientAndNumber(ClientDAO.get().findByNumber(1).getId(), 4);

		HealdanSettings.get().delete("seed.smoke-second-matter");
		runner.runPending(List.of(seed("true")));

		Matter again = MatterDAO.get().findByClientAndNumber(ClientDAO.get().findByNumber(1).getId(), 4);
		assertEquals(first.getId(), again.getId());
		assertEquals("done", HealdanSettings.get().get("seed.smoke-second-matter"));
	}

	@Test
	public void testNothingWithoutTheFlag() throws Exception {
		for (String flag : new String[] { null, "", "false" }) {
			SmokeSecondMatterSeed seed = seed(flag);
			assertFalse(String.valueOf(flag), seed.enabled());
			Context.get(SeedRunner.class).runPending(List.of(seed));
		}
		assertNull(HealdanSettings.get().get("seed.smoke-second-matter"));
	}

	@Test
	public void testWithoutTheFixtureItDoesNothing() throws Exception {
		Context.get(SeedRunner.class).runPending(List.of(seed("true")));
		assertEquals("done", HealdanSettings.get().get("seed.smoke-second-matter"));
		assertNull(ClientDAO.get().findByNumber(1));
	}
}
