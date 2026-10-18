package com.logicaldoc.healdan.seed;

import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientService;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * For the smoke test only: when {@code HEALDAN_SMOKE_FIXTURE} is {@code true},
 * creates the Store {@code Smoke}, the template {@code Smoke template} with a
 * {@code Documents} folder, client 1 and matter 1.1, owned by the admin.
 * Healdan refuses documents outside a matter, so the smoke test needs one.
 * <p>
 * Without the variable the step does nothing and writes no marker, so it can
 * run on a later start.
 */
@Component
public class SmokeFixtureSeed implements SeedStep {

	static final String ENV = "HEALDAN_SMOKE_FIXTURE";

	private static final Set<Permission> EVERYONE_GETS = Set.of(Permission.READ, Permission.WRITE, Permission.ADD,
			Permission.DOWNLOAD, Permission.PREVIEW);

	private final Supplier<String> flag;

	@Autowired
	public SmokeFixtureSeed() {
		this(() -> System.getenv(ENV));
	}

	SmokeFixtureSeed(Supplier<String> flag) {
		this.flag = flag;
	}

	@Override
	public String name() {
		return "smoke-fixture";
	}

	@Override
	public boolean enabled() {
		return "true".equals(flag.get());
	}

	@Override
	public void run() throws Exception {
		FolderDAO dao = FolderDAO.get();
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);

		// P2: whoever reads the matter reads its Store
		Folder root = dao.findRoot(Tenant.DEFAULT_ID);
		Folder vo = new Folder("Smoke");
		vo.setType(Folder.TYPE_WORKSPACE);
		vo.setTenantId(Tenant.DEFAULT_ID);
		Folder store = dao.create(root, vo, false, history(FolderEvent.CREATED));
		setAcl(store, everyone, Set.of(Permission.READ));

		/*
		 * P1: a folder inside a matter can't grant more than its parent, so the
		 * template's root has an ACL of its own and Documents inherits it
		 */
		Folder templates = dao.findById(HealdanSettings.get().templatesStoreId());
		Folder template = dao.create(templates, new Folder("Smoke template"), true, history(FolderEvent.CREATED));
		setAcl(template, everyone, EVERYONE_GETS);
		template = dao.findById(template.getId());
		dao.create(template, new Folder("Documents"), true, history(FolderEvent.CREATED));

		User admin = UserDAO.get().findByUsername("admin", true);
		Client client = ClientService.get().create(1, "Smoke Client", admin);
		MatterService.get().create(new NewMatter(client.getId(), 1, "Smoke Matter", null, store.getId(), admin.getId(),
				template.getId()), admin);
	}

	private void setAcl(Folder folder, Group group, Set<Permission> permissions) throws Exception {
		Folder current = FolderDAO.get().findById(folder.getId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		FolderAccessControlEntry ace = new FolderAccessControlEntry(group.getId());
		ace.grantPermissions(permissions);
		current.addAccessControlEntry(ace);
		FolderDAO.get().store(current, history(FolderEvent.PERMISSION));
	}

	/*
	 * The system user only labels the history. Its id stays 0 because with a
	 * real user id CE adds that user's group to the new folder's ACL.
	 */
	private FolderHistory history(FolderEvent event) {
		FolderHistory history = new FolderHistory();
		history.setUserId(0L);
		history.setUserLogin("_system");
		history.setUsername("System");
		history.setTenantId(Tenant.DEFAULT_ID);
		history.setEvent(event);
		history.setComment("Healdan smoke fixture (user id %d)".formatted(User.USERID_SYSTEM));
		return history;
	}
}
