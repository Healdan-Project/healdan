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

public class SmokeOrganisingSeedTest extends AbstractHealdanTestCase {

	/** What the smoke-fixture step gives Everyone in its template */
	private static final Set<Permission> FIXTURE = Set.of(Permission.READ, Permission.WRITE, Permission.ADD,
			Permission.DOWNLOAD, Permission.PREVIEW);

	private SmokeOrganisingSeed seed(String flag) {
		return new SmokeOrganisingSeed(() -> flag);
	}

	@Test
	public void testGivesEveryoneOrganisingInTheTemplateAndItsMatter() throws Exception {
		SeedRunner runner = Context.get(SeedRunner.class);
		// A stack kept from before the step: the fixture's template and matter 1.1 are there already
		runner.runPending(List.of(new SmokeFixtureSeed(() -> "true")));
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		Folder template = template();
		Matter matter = MatterDAO.get().findByClientAndNumber(ClientDAO.get().findByNumber(1).getId(), 1);
		assertEquals(FIXTURE, granted(template.getId(), everyone));
		assertFalse(granted(matter.getFolderId(), everyone).contains(Permission.DELETE));

		SmokeOrganisingSeed seed = seed("true");
		assertEquals("smoke-organising", seed.name());
		runner.runPending(List.of(seed));

		Set<Permission> expected = new HashSet<>(FIXTURE);
		expected.addAll(SmokeOrganisingSeed.ORGANISING);
		assertEquals(expected, granted(template.getId(), everyone));
		assertEquals(expected, granted(matter.getFolderId(), everyone));
		assertEquals("done", HealdanSettings.get().get("seed.smoke-organising"));
		// The fixture's own step is as it shipped
		assertEquals("done", HealdanSettings.get().get("seed.smoke-fixture"));
	}

	@Test
	public void testNothingWithoutTheFlag() throws Exception {
		for (String flag : new String[] { null, "", "false" }) {
			SmokeOrganisingSeed seed = seed(flag);
			assertFalse(String.valueOf(flag), seed.enabled());
			Context.get(SeedRunner.class).runPending(List.of(seed));
		}
		assertNull(HealdanSettings.get().get("seed.smoke-organising"));
	}

	@Test
	public void testWithoutTheTemplateItDoesNothing() throws Exception {
		Context.get(SeedRunner.class).runPending(List.of(seed("true")));
		assertEquals("done", HealdanSettings.get().get("seed.smoke-organising"));
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		assertTrue(FolderDAO.get().findByName(templates, "Smoke template", Tenant.DEFAULT_ID, false).isEmpty());
	}

	private static Folder template() throws Exception {
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		return FolderDAO.get().findByName(templates, "Smoke template", Tenant.DEFAULT_ID, false).get(0);
	}

	private static Set<Permission> granted(long folderId, Group everyone) throws Exception {
		FolderAccessControlEntry ace = FolderDAO.get().findById(folderId, true).getAccessControlEntry(everyone.getId());
		assertNotNull(ace);
		return ace.grantedPermissions();
	}
}
