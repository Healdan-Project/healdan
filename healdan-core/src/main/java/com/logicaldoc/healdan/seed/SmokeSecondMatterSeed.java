// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * For the smoke test only: when {@code HEALDAN_SMOKE_FIXTURE} is {@code true},
 * makes matter 1.4, {@code Second Matter}, in the Store {@code Smoke} from the
 * {@code Smoke template}, if it isn't there, so the User app's browser tests
 * can move a folder from matter 1.1 into another matter without relying on
 * what another smoke step leaves (matters 1.2 and 1.3 belong to the storage
 * overlay, and step 5f moves 1.2). It is a step of its own, as a shipped step
 * never changes, and it runs after {@code smoke-web-user}, so the matter's
 * folders have the template's permissions, Rename, Move and Delete included
 * once {@code smoke-organising} has given them to the template.
 * <p>
 * Without the Store, the template or client 1, as on a stack whose fixture
 * step was skipped, or without the user {@code admin}, who makes it, it does
 * nothing and counts as done. Without the variable
 * the step does nothing and writes no marker, so it can run on a later start.
 */
@Component
public class SmokeSecondMatterSeed implements SeedStep {

	static final int CLIENT = 1;

	static final int NUMBER = 4;

	static final String NAME = "Second Matter";

	private static final String STORE = "Smoke";

	private static final String TEMPLATE = "Smoke template";

	private static final Logger log = LoggerFactory.getLogger(SmokeSecondMatterSeed.class);

	private final Supplier<String> flag;

	@Autowired
	public SmokeSecondMatterSeed() {
		this(() -> System.getenv(SmokeFixtureSeed.ENV));
	}

	SmokeSecondMatterSeed(Supplier<String> flag) {
		this.flag = flag;
	}

	@Override
	public String name() {
		return "smoke-second-matter";
	}

	@Override
	public boolean enabled() {
		return "true".equals(flag.get());
	}

	/** In one transaction, so a failure leaves nothing for the next start to trip over */
	@Override
	public void run() throws Exception {
		new TransactionTemplate(Context.get(PlatformTransactionManager.class)).execute(status -> {
			try {
				create();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
			return null;
		});
	}

	private void create() throws Exception {
		FolderDAO dao = FolderDAO.get();
		Folder root = dao.findRoot(Tenant.DEFAULT_ID);
		List<Folder> stores = dao.findByNameAndParentId(STORE, root.getId());
		Long templatesId = HealdanSettings.get().templatesStoreId();
		List<Folder> templates = templatesId != null ? dao.findByNameAndParentId(TEMPLATE, templatesId) : List.of();
		Client client = ClientDAO.get().findByNumber(CLIENT);
		if (stores.isEmpty() || templates.isEmpty() || client == null) {
			log.warn("No Store {}, {} or client {} to make matter {}.{} in: the smoke-fixture step hasn't made them",
					STORE, TEMPLATE, CLIENT, CLIENT, NUMBER);
			return;
		}
		if (MatterDAO.get().findByClientAndNumber(client.getId(), NUMBER) != null)
			return;

		User admin = UserDAO.get().findByUsername("admin", true);
		if (admin == null) {
			log.warn("No user admin to make matter {}.{} as", CLIENT, NUMBER);
			return;
		}
		MatterService.get().create(new NewMatter(client.getId(), NUMBER, NAME, null, stores.get(0).getId(),
				admin.getId(), templates.get(0).getId()), admin);
	}
}
