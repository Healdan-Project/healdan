package com.logicaldoc.healdan.support;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Builds the users, groups, Stores, clients, matters, folders and documents
 * that Healdan's tests need.
 * <p>
 * Until Task 15, {@link #matter} builds the workspace directly; Task 15 points
 * it at {@code MatterService}.
 */
public class HealdanFixtures {

	/** The password of the users {@link #user} creates */
	public static final String PASSWORD = "Kx7!Rmq-Tz8vW";

	/**
	 * @param username the login
	 * @param groups the names of the groups to put the user in, besides the ones
	 *        CE gives every user
	 * @return the stored user, with its groups
	 * @throws Exception if the user can't be stored
	 */
	public User user(String username, String... groups) throws Exception {
		User user = new User();
		user.setUsername(username);
		user.setName(username);
		user.setFirstName(username);
		user.setEmail(username + "@example.com");
		user.setDecodedPassword(PASSWORD);
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setType(User.Type.DEFAULT);
		for (String name : groups) {
			Group group = GroupDAO.get().findByName(name, Tenant.DEFAULT_ID);
			user.addGroup(group != null ? group : group(name));
		}
		UserDAO.get().store(user);
		return UserDAO.get().findByUsername(username, true);
	}

	/**
	 * @param name the group name
	 * @return the stored group
	 * @throws Exception if the group can't be stored
	 */
	public Group group(String name) throws Exception {
		Group group = new Group();
		group.setName(name);
		group.setTenantId(Tenant.DEFAULT_ID);
		GroupDAO.get().store(group);
		return GroupDAO.get().findByName(name, Tenant.DEFAULT_ID);
	}

	/**
	 * @param name the name of the Store
	 * @return a new workspace directly under the root
	 * @throws Exception if the folder can't be created
	 */
	public Folder store(String name) throws Exception {
		Folder vo = new Folder(name);
		vo.setType(Folder.TYPE_WORKSPACE);
		vo.setTenantId(Tenant.DEFAULT_ID);
		return FolderDAO.get().create(FolderDAO.get().findRoot(Tenant.DEFAULT_ID), vo, false, folderHistory());
	}

	/**
	 * @param number the client number
	 * @param name the client name
	 * @return the stored client
	 * @throws Exception if the client can't be stored
	 */
	public Client client(long number, String name) throws Exception {
		Client client = new Client();
		client.setNumber(number);
		client.setName(name);
		ClientDAO.get().store(client);
		return client;
	}

	/**
	 * @param client the client
	 * @param number the matter number
	 * @param name the matter name
	 * @param store the Store to put the matter in
	 * @param topFolders the names of the folders to create in the workspace
	 * @return the stored matter; its workspace is {@code folderId}
	 * @throws Exception if something can't be created
	 */
	public Matter matter(Client client, long number, String name, Folder store, String... topFolders)
			throws Exception {
		return HealdanOperation.run(HealdanOperation.Kind.MATTER_CREATE, () -> {
			Folder vo = new Folder(MatterDisplay.format(client.getNumber(), number, name));
			vo.setTenantId(Tenant.DEFAULT_ID);
			Folder workspace = FolderDAO.get().create(store, vo, true, folderHistory());
			for (String top : topFolders)
				folder(workspace, top);

			// The row comes after the folders, as when Task 15 creates a matter
			Matter matter = new Matter();
			matter.setClientId(client.getId());
			matter.setNumber(number);
			matter.setName(name);
			matter.setFolderId(workspace.getId());
			matter.setOwnerId(admin().getId());
			MatterDAO.get().store(matter);

			workspace = FolderDAO.get().findById(workspace.getId(), true);
			workspace.setTemplate(ProfileSchema.get().template());
			workspace.setValue(HealdanConstants.ATTR_CLIENT, Long.toString(client.getNumber()));
			workspace.setValue(HealdanConstants.ATTR_MATTER, Long.toString(number));
			FolderDAO.get().store(workspace, folderHistory());
			return matter;
		});
	}

	/**
	 * @param parent the parent folder
	 * @param name the folder name
	 * @return a new folder that inherits the parent's security
	 * @throws Exception if the folder can't be created
	 */
	public Folder folder(Folder parent, String name) throws Exception {
		return folder(parent, name, admin());
	}

	/**
	 * @param parent the parent folder
	 * @param name the folder name
	 * @param as the user who creates it
	 * @return a new folder that inherits the parent's security
	 * @throws Exception if the folder can't be created
	 */
	public Folder folder(Folder parent, String name, User as) throws Exception {
		Folder vo = new Folder(name);
		vo.setTenantId(Tenant.DEFAULT_ID);
		return FolderDAO.get().create(parent, vo, true, folderHistory(as));
	}

	/**
	 * @param parent the parent folder
	 * @param name the folder name
	 * @return a new search folder (type 1001), created by the admin as Healdan's
	 *         search-folder service will
	 * @throws Exception if the folder can't be created
	 */
	public Folder searchFolder(Folder parent, String name) throws Exception {
		return HealdanOperation.run(HealdanOperation.Kind.SEARCH_FOLDER, () -> {
			Folder vo = new Folder(name);
			vo.setType(HealdanConstants.FOLDER_TYPE_SEARCH);
			vo.setTenantId(Tenant.DEFAULT_ID);
			return FolderDAO.get().create(parent, vo, true, folderHistory());
		});
	}

	/**
	 * Turns a new document into a shortcut to another, with JDBC, as data from
	 * before Healdan may hold: Healdan refuses to create shortcuts inside Stores.
	 *
	 * @param target the document the shortcut points to
	 * @param folder where to put the shortcut
	 * @return the shortcut
	 * @throws Exception if the document can't be created
	 */
	public Document legacyShortcut(Document target, Folder folder) throws Exception {
		Document shortcut = upload(folder, target.getFileName(), "shortcut", admin());
		jdbc().update("update ld_document set ld_docref = ? where ld_id = ?", target.getId(), shortcut.getId());
		Context.get(CacheEvictor.class).evictDocuments(List.of(shortcut.getId()));
		return DocumentDAO.get().findById(shortcut.getId(), true);
	}

	/**
	 * Turns a new folder into an alias of another, with JDBC, as data from
	 * before Healdan may hold: Healdan refuses to create folder aliases inside
	 * Stores.
	 *
	 * @param parent where to put the alias
	 * @param name the alias name
	 * @param target the folder the alias points to
	 * @return the alias
	 * @throws Exception if the folder can't be created
	 */
	public Folder legacyAlias(Folder parent, String name, Folder target) throws Exception {
		Folder alias = folder(parent, name);
		jdbc().update("update ld_folder set ld_type = ?, ld_foldref = ? where ld_id = ?", Folder.TYPE_ALIAS,
				target.getId(), alias.getId());
		Context.get(CacheEvictor.class).evictFolders(List.of(alias.getId()));
		return FolderDAO.get().findById(alias.getId());
	}

	/**
	 * Gives the group exactly these permissions on the folder.
	 * 
	 * @param folder the folder
	 * @param group the group
	 * @param permissions the permissions to grant
	 * @throws Exception if the folder can't be stored
	 */
	public void grant(Folder folder, Group group, Permission... permissions) throws Exception {
		Folder current = FolderDAO.get().findById(folder.getId(), true);
		current.removeAccessControlEntries(group.getId());
		FolderAccessControlEntry ace = new FolderAccessControlEntry(group.getId());
		ace.grantPermissions(java.util.Set.of(permissions));
		current.addAccessControlEntry(ace);
		FolderDAO.get().store(current, folderHistory());
	}

	/**
	 * @param folder where to put the document
	 * @param fileName the file name
	 * @param text the content
	 * @param as the user who uploads
	 * @return the stored document
	 * @throws Exception if the document can't be created
	 */
	public Document upload(Folder folder, String fileName, String text, User as) throws Exception {
		Document vo = new Document();
		vo.setFileName(fileName);
		vo.setFolder(folder);
		vo.setTenantId(Tenant.DEFAULT_ID);
		vo.setLanguage("en");
		DocumentHistory transaction = new DocumentHistory();
		transaction.setSessionId("fixtures");
		transaction.setUser(as);
		return DocumentManager.get()
				.create(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), vo, transaction).getDocument();
	}

	/**
	 * @return the database as it is now, to prove later that it didn't change
	 */
	public DbSnapshot snapshot() {
		return DbSnapshot.take(jdbc());
	}

	/**
	 * @return the admin user, with its groups
	 * @throws Exception if the user can't be read
	 */
	public User admin() throws Exception {
		return UserDAO.get().findByUsername("admin", true);
	}

	/**
	 * @param as the user, null for none
	 * @return a folder history entry naming the user
	 */
	public FolderHistory folderHistory(User as) {
		FolderHistory history = new FolderHistory();
		history.setSessionId("fixtures");
		if (as != null)
			history.setUser(as);
		return history;
	}

	/**
	 * @param as the user, null for none
	 * @return a document history entry naming the user
	 */
	public DocumentHistory documentHistory(User as) {
		DocumentHistory history = new DocumentHistory();
		history.setSessionId("fixtures");
		if (as != null)
			history.setUser(as);
		return history;
	}

	private FolderHistory folderHistory() throws Exception {
		return folderHistory(admin());
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}
}
