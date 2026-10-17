package com.logicaldoc.healdan.seed;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * Finds or creates the Templates Store, a workspace without any ACL entry so
 * that only admins can see it, and records its id.
 */
@Component
public class TemplatesStoreSeed implements SeedStep {

	private final HealdanSettings settings;

	@Autowired
	public TemplatesStoreSeed(HealdanSettings settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return "templates-store";
	}

	@Override
	public void run() throws Exception {
		FolderDAO dao = FolderDAO.get();
		Folder root = dao.findRoot(Tenant.DEFAULT_ID);

		Folder store = dao.findByName(root, HealdanConstants.TEMPLATES_STORE_NAME, Tenant.DEFAULT_ID, false).stream()
				.filter(f -> f.getType() == Folder.TYPE_WORKSPACE).findFirst().orElse(null);

		if (store == null) {
			Folder vo = new Folder(HealdanConstants.TEMPLATES_STORE_NAME);
			vo.setType(Folder.TYPE_WORKSPACE);
			vo.setTenantId(Tenant.DEFAULT_ID);
			store = dao.create(root, vo, false, systemTransaction());
		}
		settings.set(HealdanSettings.STORE_TEMPLATES, Long.toString(store.getId()));
	}

	/*
	 * The system user only labels the history. Its id stays 0 because with a
	 * real user id CE adds that user's group to the new folder's ACL.
	 */
	private FolderHistory systemTransaction() {
		FolderHistory history = new FolderHistory();
		history.setUserId(0L);
		history.setUserLogin("_system");
		history.setUsername("System");
		history.setTenantId(Tenant.DEFAULT_ID);
		history.setComment("Healdan first-start setup (user id %d)".formatted(User.USERID_SYSTEM));
		return history;
	}
}
