package com.logicaldoc.healdan.seed.demo;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientService;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;
import com.logicaldoc.healdan.searchfolder.SearchFolderService;
import com.logicaldoc.healdan.seed.demo.DemoManifest.ClassSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.ClientSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.FolderSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.GroupSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.MatterSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.SearchFolderSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.StoreSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.TemplateSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.UserSpec;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * The demo firm's structure (demo spec §4.1–§4.4): its groups, users,
 * Classes, Stores, templates, clients and matters, made as an admin would
 * make them in the web page. Then the lookups the timeline uses.
 */
public final class DemoFirm {

	private final DemoManifest manifest;

	private final String password;

	private final Map<String, User> users = new HashMap<>();

	private final Map<String, Folder> stores = new HashMap<>();

	private final Map<String, Folder> templates = new HashMap<>();

	private final Map<String, Matter> matters = new HashMap<>();

	private User admin;

	public DemoFirm(DemoManifest manifest, String password) {
		this.manifest = manifest;
		this.password = password;
	}

	public DemoFirm build() throws Exception {
		admin = UserDAO.get().findByUsername("admin");
		for (GroupSpec spec : manifest.groups())
			createGroup(spec);
		for (UserSpec spec : manifest.users())
			createUser(spec);
		createClasses();
		for (StoreSpec spec : manifest.stores())
			createStore(spec);
		for (TemplateSpec spec : manifest.templates())
			createTemplate(spec);
		createMatters();
		return this;
	}

	public User admin() {
		return admin;
	}

	public User user(String username) {
		User user = users.get(username);
		if (user == null)
			throw new IllegalArgumentException("The manifest has no user %s".formatted(username));
		return user;
	}

	public Folder workspace(String ref) throws PersistenceException {
		Matter matter = matters.get(ref);
		if (matter == null)
			throw new IllegalArgumentException("The manifest has no matter %s".formatted(ref));
		return FolderDAO.get().findById(matter.getFolderId());
	}

	/**
	 * @param path a folder inside the matter's workspace, such as
	 *        {@code Correspondence/Superseded}
	 */
	public Folder folder(String ref, String path) throws PersistenceException {
		Folder folder = workspace(ref);
		for (String name : path.split("/")) {
			List<Folder> found = FolderDAO.get().findByName(folder, name, Tenant.DEFAULT_ID, true);
			if (found.isEmpty())
				throw new IllegalArgumentException("Matter %s has no folder %s".formatted(ref, path));
			folder = found.get(0);
		}
		return folder;
	}

	private void createGroup(GroupSpec spec) throws PersistenceException {
		Group group = new Group();
		group.setName(spec.name());
		group.setDescription(spec.description());
		group.setTenantId(Tenant.DEFAULT_ID);
		GroupDAO.get().insert(group, 0);
	}

	private void createUser(UserSpec spec) throws Exception {
		User user = new User();
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setUsername(spec.username());
		user.setFirstName(spec.firstName());
		user.setName(spec.name());
		user.setEmail(spec.email());
		user.setLanguage("en");
		user.setType(User.Type.DEFAULT);
		user.setEnabled(true);
		// CE's password policy runs on this store, so a weak demo password fails here
		user.setDecodedPassword(password);
		user.addGroup(group(HealdanConstants.EVERYONE_GROUP));
		for (String name : spec.groups())
			user.addGroup(group(name));
		UserHistory history = new UserHistory();
		history.setAuthor("System");
		UserDAO.get().store(user, history);
		users.put(spec.username(), UserDAO.get().findById(user.getId()));
	}

	private static Group group(String name) throws PersistenceException {
		Group group = GroupDAO.get().findByName(name, Tenant.DEFAULT_ID);
		if (group == null)
			throw new IllegalArgumentException("No group %s".formatted(name));
		return group;
	}

	/** Healdan ships the Class Docs only (plan call 9) */
	private void createClasses() throws PersistenceException {
		long setId = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true)
				.getId();
		AttributeOptionDAO dao = AttributeOptionDAO.get();
		List<String> existing = dao.findByAttribute(setId, HealdanConstants.ATTR_DOCCLASS).stream()
				.map(AttributeOption::getValue).toList();
		for (ClassSpec spec : manifest.classes()) {
			if (!existing.contains(spec.name()))
				dao.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCCLASS, spec.name()));
			for (String subclass : spec.subclasses())
				dao.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCSUBCLASS, subclass, spec.name()));
		}
	}

	private void createStore(StoreSpec spec) throws Exception {
		FolderDAO dao = FolderDAO.get();
		Folder vo = new Folder(spec.name());
		vo.setType(Folder.TYPE_WORKSPACE);
		vo.setTenantId(Tenant.DEFAULT_ID);
		Folder store = dao.create(dao.findRoot(Tenant.DEFAULT_ID), vo, false, systemHistory(FolderEvent.CREATED));
		setAcl(store, spec.acl(), null);
		stores.put(spec.name(), dao.findById(store.getId()));
	}

	private void createTemplate(TemplateSpec spec) throws Exception {
		FolderDAO dao = FolderDAO.get();
		Folder parent = dao.findById(HealdanSettings.get().templatesStoreId());
		Folder template = dao.create(parent, new Folder(spec.name()), true, systemHistory(FolderEvent.CREATED));
		// P1: a folder in a matter can't grant more than its parent, so the template's root has its own ACL
		setAcl(template, spec.acl(), null);
		template = dao.findById(template.getId());
		createFolders(template, spec.folders());
		for (SearchFolderSpec search : spec.searchFolders()) {
			SearchFolderRule.Kind kind = search.kind() == null ? SearchFolderRule.Kind.ALL
					: SearchFolderRule.Kind.valueOf(search.kind());
			SearchFolderService.get().create(template.getId(), search.name(), new SearchFolderRule(0, search.days(),
					search.onlyMine(), kind, search.docClass(), search.subclass()), admin);
		}
		templates.put(spec.name(), template);
	}

	private void createFolders(Folder parent, List<FolderSpec> specs) throws Exception {
		FolderDAO dao = FolderDAO.get();
		for (FolderSpec spec : specs) {
			Folder folder = dao.create(parent, new Folder(spec.name()), true, systemHistory(FolderEvent.CREATED));
			if (spec.acl() != null)
				setAcl(folder, spec.acl(), null);
			createFolders(dao.findById(folder.getId()), spec.folders());
		}
	}

	private void createMatters() throws Exception {
		Map<Long, Client> clients = new HashMap<>();
		for (ClientSpec spec : manifest.clients())
			clients.put(spec.number(), ClientService.get().create(spec.number(), spec.name(), admin));
		for (MatterSpec spec : manifest.matters()) {
			String[] number = spec.ref().split("\\.");
			if (number.length != 2)
				throw new IllegalArgumentException("Matter %s isn't client.number".formatted(spec.ref()));
			Client client = clients.get(Long.parseLong(number[0]));
			if (client == null)
				throw new IllegalArgumentException("The manifest has no client %s".formatted(number[0]));
			User owner = user(spec.owner());
			Matter matter = MatterService.get()
					.create(new NewMatter(client.getId(), Long.parseLong(number[1]), spec.name(), spec.description(),
							named(stores, spec.store()).getId(), owner.getId(),
							named(templates, spec.template()).getId()), admin);
			if (spec.acl() != null)
				setAcl(FolderDAO.get().findById(matter.getFolderId()), spec.acl(), owner);
			matters.put(spec.ref(), matter);
		}
	}

	private static Folder named(Map<String, Folder> folders, String name) {
		Folder folder = folders.get(name);
		if (folder == null)
			throw new IllegalArgumentException("The manifest has no Store or template %s".formatted(name));
		return folder;
	}

	/**
	 * Replaces a folder's ACL, as smoke-fixture does
	 *
	 * @param owner when not null, also gets every permission through their
	 *        personal group, as a matter's owner does (plan call 8)
	 */
	private static void setAcl(Folder folder, Map<String, List<String>> acl, User owner) throws Exception {
		FolderDAO dao = FolderDAO.get();
		Folder current = dao.findById(folder.getId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		for (Map.Entry<String, List<String>> entry : acl.entrySet()) {
			FolderAccessControlEntry ace = new FolderAccessControlEntry(group(entry.getKey()).getId());
			Set<Permission> permissions = EnumSet.noneOf(Permission.class);
			entry.getValue().forEach(p -> permissions.add(Permission.valueOf(p)));
			ace.grantPermissions(permissions);
			current.addAccessControlEntry(ace);
		}
		if (owner != null) {
			FolderAccessControlEntry ace = new FolderAccessControlEntry(
					UserDAO.get().findById(owner.getId()).getUserGroup().getId());
			ace.grantPermissions(Permission.all());
			current.addAccessControlEntry(ace);
		}
		dao.store(current, systemHistory(FolderEvent.PERMISSION));
	}

	/*
	 * The system user only labels the history. Its id stays 0 because with a
	 * real user id CE adds that user's group to a new folder's ACL.
	 */
	private static FolderHistory systemHistory(FolderEvent event) {
		FolderHistory history = new FolderHistory();
		history.setUserId(0L);
		history.setUserLogin("_system");
		history.setUsername("System");
		history.setTenantId(Tenant.DEFAULT_ID);
		history.setEvent(event);
		history.setComment("Healdan demo fixture");
		return history;
	}
}
