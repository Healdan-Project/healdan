// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * For the smoke test only: when {@code HEALDAN_SMOKE_FIXTURE} is {@code true},
 * gives the Everyone group the Rename, Move and Delete permissions in the
 * {@code smoke-fixture} step's {@code Smoke template} and in every matter made
 * from it, so the User app's browser tests can organise documents as the
 * non-admin {@code webuser}. It is a step of its own, as a shipped step never
 * changes, and it runs after {@code smoke-fixture}: on a fresh stack before
 * {@code smoke-storage} makes matters 1.2 and 1.3 from the template, and on a
 * stack kept from before it, over the matters already made.
 * <p>
 * Without the template, as on a stack whose fixture step was skipped, it does
 * nothing and counts as done. Without the variable the step does nothing and
 * writes no marker, so it can run on a later start.
 */
@Component
public class SmokeOrganisingSeed implements SeedStep {

	static final Set<Permission> ORGANISING = Set.of(Permission.RENAME, Permission.MOVE, Permission.DELETE);

	private static final String TEMPLATE = "Smoke template";

	private static final Logger log = LoggerFactory.getLogger(SmokeOrganisingSeed.class);

	private final Supplier<String> flag;

	@Autowired
	public SmokeOrganisingSeed() {
		this(() -> System.getenv(SmokeFixtureSeed.ENV));
	}

	SmokeOrganisingSeed(Supplier<String> flag) {
		this.flag = flag;
	}

	@Override
	public String name() {
		return "smoke-organising";
	}

	@Override
	public boolean enabled() {
		return "true".equals(flag.get());
	}

	/** In one transaction, so a failure leaves the permissions as they were for the next start to try again */
	@Override
	public void run() throws Exception {
		new TransactionTemplate(Context.get(PlatformTransactionManager.class)).execute(status -> {
			try {
				grant();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
			return null;
		});
	}

	private void grant() throws Exception {
		FolderDAO dao = FolderDAO.get();
		Long templatesId = HealdanSettings.get().templatesStoreId();
		Folder templates = templatesId != null ? dao.findById(templatesId) : null;
		List<Folder> found = templates != null ? dao.findByName(templates, TEMPLATE, Tenant.DEFAULT_ID, false)
				: List.of();
		if (found.isEmpty()) {
			log.warn("No {} to give Everyone Rename, Move and Delete in: the smoke-fixture step hasn't made it", TEMPLATE);
			return;
		}
		Folder template = found.get(0);
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);

		add(template, everyone);
		// A matter's workspace has its own copy of the template's permissions, and its folders follow it
		for (Matter matter : MatterDAO.get().findLive())
			if (Long.valueOf(template.getId()).equals(matter.getTemplateId()))
				add(dao.findById(matter.getFolderId()), everyone);
	}

	/** Adds the permissions to the group's own entry in the folder, when it has one */
	private void add(Folder folder, Group everyone) throws Exception {
		if (folder == null)
			return;
		Folder current = FolderDAO.get().findById(folder.getId(), true);
		FolderAccessControlEntry ace = current.getAccessControlEntry(everyone.getId());
		if (ace == null)
			return;
		Set<Permission> granted = new HashSet<>(ace.grantedPermissions());
		granted.addAll(ORGANISING);
		ace.grantPermissions(granted);
		FolderDAO.get().store(current, history());
	}

	/* As the smoke-fixture step's: the system user labels the history, with id 0 */
	private FolderHistory history() {
		FolderHistory history = new FolderHistory();
		history.setUserId(0L);
		history.setUserLogin("_system");
		history.setUsername("System");
		history.setTenantId(Tenant.DEFAULT_ID);
		history.setEvent(FolderEvent.PERMISSION);
		history.setComment("Healdan smoke fixture (user id %d)".formatted(User.USERID_SYSTEM));
		return history;
	}
}
