package com.logicaldoc.healdan.matter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.logicaldoc.core.PersistenceException;
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
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.LocalValueDAO;
import com.logicaldoc.healdan.profile.Propagation;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.security.PermissionRules;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.util.BasicPlane;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.html.HTMLSanitizer;
import com.logicaldoc.util.spring.Context;

/**
 * Creating and editing matters (spec 5.3). Admins only.
 * <p>
 * A matter is created from a workspace template in one transaction: the
 * template is copied into the Store as the workspace ({@link TemplateCopier}),
 * the matter's row is recorded, client and matter are stamped on every folder,
 * and the owner's own group gets every permission on the workspace, which
 * then passes the permission rules (P2). Any refusal leaves nothing behind.
 * <p>
 * Deleting and restoring a matter go through CE's trash: deleting the
 * workspace hides the matter and keeps its row, so its number stays taken for
 * good; restoring the workspace brings the matter back as it was.
 */
@Component
public class MatterService {

	/** The longest folder name, and so the longest display name */
	static final int MAX_DISPLAY_LENGTH = 255;

	/** The longest description the matter's row and the folder hold */
	static final int MAX_DESCRIPTION_LENGTH = 4000;

	static final String ONLY_ADMINS = "Only an administrator can create or edit %s.";

	static final String BAD_NAME = "A %s name can't be empty, contain '/' or be longer than %d characters.";

	static final String UNSAFE_NAME = "A %s name can't contain '\\' or HTML tags.";

	static final String OUTSIDE_PLANE_NAME = "A %s name can't contain emoji or other characters the database can't store.";

	static final String LONG_DESCRIPTION = "A %s description can't be longer than %d characters.";

	static final String NO_CLIENT = "That client no longer exists.";

	static final String NO_STORE = "That Store no longer exists.";

	static final String NO_TEMPLATE = "That template no longer exists.";

	static final String NO_OWNER = "That owner no longer exists.";

	static final String DISABLED_OWNER = "The owner %s is disabled.";

	static final String NO_MATTER = "That %s no longer exists.";

	private final FilingRules filingRules;

	private final MatterNumbering numbering;

	private final MatterLocator locator;

	private final TemplateCopier copier;

	private final PermissionRules permissionRules;

	private final LocalValueDAO locals;

	private final Propagation propagation;

	private final HealdanSettings settings;

	private final CacheEvictor evictor;

	private final JdbcTemplate jdbc;

	@Autowired
	public MatterService(FilingRules filingRules, MatterNumbering numbering, MatterLocator locator,
			TemplateCopier copier, PermissionRules permissionRules, LocalValueDAO locals, Propagation propagation,
			HealdanSettings settings, CacheEvictor evictor, DataSource dataSource) {
		this.filingRules = filingRules;
		this.numbering = numbering;
		this.locator = locator;
		this.copier = copier;
		this.permissionRules = permissionRules;
		this.locals = locals;
		this.propagation = propagation;
		this.settings = settings;
		this.evictor = evictor;
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the service bean
	 */
	public static MatterService get() {
		return Context.get(MatterService.class);
	}

	/**
	 * Creates a matter and its workspace from a template, all or nothing.
	 *
	 * @param req what the admin asks for
	 * @param actor the user who asks
	 * @return the new matter; its workspace is {@code folderId}
	 * @throws PersistenceException a refusal (a {@link HealdanRuleException}
	 *         with a message for the user), or an error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public Matter create(NewMatter req, User actor) throws PersistenceException {
		return run(HealdanOperation.Kind.MATTER_CREATE, () -> doCreate(req, actor));
	}

	private Matter doCreate(NewMatter req, User actor) throws PersistenceException {
		requireAdmin(actor);
		Client client = ClientDAO.get().findById(req.clientId());
		if (client == null)
			throw new HealdanRuleException(NO_CLIENT);
		String name = cleanName(req.name(), client.getNumber(), req.number());
		String display = MatterDisplay.format(client.getNumber(), req.number(), name);
		String description = cleanDescription(req.description());
		Folder store = FolderDAO.get().findById(req.storeId());
		if (store == null || locator.locate(store).kind() != Kind.STORE)
			throw new HealdanRuleException(NO_STORE);
		Folder template = template(req.templateFolderId());
		if (template == null)
			throw new HealdanRuleException(NO_TEMPLATE);
		User owner = enabledOwner(req.ownerId());
		/*
		 * Per client, the unique index on (client, number) backs this check up;
		 * across all clients only this check holds, so two admins creating the
		 * same number for two clients at the same moment could both succeed (out
		 * of scope for M1)
		 */
		numbering.checkAvailable(client.getId(), req.number());

		FolderHistory tx = history(actor);
		Map<Long, Long> copies = copier.copy(template, store, display, tx);
		long workspaceId = copies.get(template.getId());
		Folder workspace = FolderDAO.get().findById(workspaceId, true);
		requireDisplayName(workspace, display);

		/*
		 * The row before the owner's permissions: the permission rules read the
		 * owner from it for P2's exception (the owner's own group passes if the
		 * owner can read the Store)
		 */
		Matter matter = new Matter();
		matter.setClientId(client.getId());
		matter.setNumber(req.number());
		matter.setName(name);
		matter.setDescription(description);
		matter.setFolderId(workspaceId);
		matter.setOwnerId(owner.getId());
		matter.setTemplateId(template.getId());
		MatterDAO.get().store(matter);

		// Client and matter, the description, and the owner's own group with every permission
		Inheritance.get().stampFixed(workspace, Inheritance.get().fixedValues(matter));
		workspace.setDescription(description);
		grantAll(workspace, ownGroup(owner.getId()));
		FolderDAO.get().store(workspace, history(tx, FolderEvent.PERMISSION));
		locals.setLocal(LocalValueDAO.FOLDER, workspaceId, HealdanConstants.ATTR_CLIENT, true);
		locals.setLocal(LocalValueDAO.FOLDER, workspaceId, HealdanConstants.ATTR_MATTER, true);
		// The folders copied before the row existed get client and matter too
		propagation.restampSubtree(workspaceId);

		permissionRules.checkP2(workspaceId, store.getId(), owner.getId());
		return matter;
	}

	/**
	 * Edits a matter: renames its workspace when the name changes, and on an
	 * owner change gives the new owner's own group every permission on the
	 * workspace and takes the old owner's entry away. The client and the number
	 * never change.
	 *
	 * @param matterId the matter
	 * @param name the new name; it is trimmed
	 * @param description the new description, null for none
	 * @param ownerId the owner
	 * @param actor the user who asks
	 * @return the matter
	 * @throws PersistenceException a refusal (a {@link HealdanRuleException}
	 *         with a message for the user), or an error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public Matter update(long matterId, String name, String description, long ownerId, User actor)
			throws PersistenceException {
		return run(HealdanOperation.Kind.MATTER_EDIT, () -> doUpdate(matterId, name, description, ownerId, actor));
	}

	private Matter doUpdate(long matterId, String name, String description, long ownerId, User actor)
			throws PersistenceException {
		requireAdmin(actor);
		Matter matter = MatterDAO.get().findById(matterId);
		Folder workspace = matter != null ? FolderDAO.get().findById(matter.getFolderId(), true) : null;
		Client client = matter != null ? ClientDAO.get().findById(matter.getClientId()) : null;
		if (workspace == null || client == null)
			throw new HealdanRuleException(NO_MATTER.formatted(label()));
		String clean = cleanName(name, client.getNumber(), matter.getNumber());
		String cleanDescription = cleanDescription(description);
		long oldOwnerId = matter.getOwnerId();
		User owner = ownerId != oldOwnerId ? enabledOwner(ownerId) : null;

		// The row first: the permission rules read the owner from it (P2)
		matter.setName(clean);
		matter.setDescription(cleanDescription);
		matter.setOwnerId(ownerId);
		MatterDAO.get().store(matter);

		FolderHistory tx = history(actor);
		String display = MatterDisplay.format(client.getNumber(), matter.getNumber(), clean);
		boolean renamed = !display.equals(workspace.getName());
		if (renamed || !Objects.equals(cleanDescription, workspace.getDescription())) {
			workspace.setName(display);
			workspace.setDescription(cleanDescription);
			FolderDAO.get().store(workspace, history(tx, renamed ? FolderEvent.RENAMED : FolderEvent.CHANGED));
			requireDisplayName(workspace, display);
		}

		if (owner != null) {
			workspace = FolderDAO.get().findById(workspace.getId(), true);
			// An inheriting workspace takes what it inherits as its own, to change it
			if (workspace.getSecurityRef() != null && workspace.getSecurityRef() != workspace.getId())
				TemplateCopier.setOwnAcl(workspace, AclMath.effective(workspace.getId()));
			for (long oldGroup : ownGroupIds(oldOwnerId))
				workspace.removeAccessControlEntries(oldGroup);
			grantAll(workspace, ownGroup(owner.getId()));
			// The folders below that granted the old owner's group are narrowed with it (P1)
			FolderDAO.get().store(workspace, history(tx, FolderEvent.PERMISSION));
			permissionRules.checkP2(workspace.getId(), workspace.getParentId(), owner.getId());
		}
		return matter;
	}

	/**
	 * @return the live folders directly under the Templates Store that can be
	 *         copied as a workspace; none if the Templates Store is gone
	 * @throws PersistenceException error at data layer
	 */
	public List<Folder> templates() throws PersistenceException {
		Long templatesId = settings.templatesStoreId();
		if (templatesId == null || FolderDAO.get().findById(templatesId) == null)
			return List.of();
		return FolderDAO.get().findChildren(templatesId, null).stream().filter(MatterService::copiable).toList();
	}

	/**
	 * @return the live Stores: the workspaces under the root except Default
	 *         and Templates
	 * @throws PersistenceException error at data layer
	 */
	public List<Folder> stores() throws PersistenceException {
		Long templatesId = settings.templatesStoreId();
		return FolderDAO.get().findWorkspaces(Tenant.DEFAULT_ID).stream()
				.filter(folder -> folder != null && folder.getId() != Folder.DEFAULTWORKSPACEID
						&& !Long.valueOf(folder.getId()).equals(templatesId))
				.toList();
	}

	/**
	 * @return the template, null if it is not a live folder directly under the
	 *         Templates Store that can be copied
	 */
	private Folder template(long templateId) throws PersistenceException {
		Long templatesId = settings.templatesStoreId();
		Folder template = FolderDAO.get().findById(templateId);
		if (template == null || templatesId == null || template.getParentId() != templatesId
				|| FolderDAO.get().findById(templatesId) == null || !copiable(template))
			return null;
		return template;
	}

	/**
	 * @return true for a plain folder: not a search folder, which a workspace
	 *         can't be, nor an alias
	 */
	private static boolean copiable(Folder folder) {
		return folder.getType() == Folder.TYPE_DEFAULT && folder.getFoldRef() == null;
	}

	private void requireAdmin(User actor) throws PersistenceException {
		if (!filingRules.isAdmin(actor))
			throw new HealdanRuleException(
					ONLY_ADMINS.formatted(settings.matterLabelPlural().toLowerCase(Locale.ROOT)));
	}

	/**
	 * @return the name, trimmed
	 * @throws HealdanRuleException if it is empty, holds '/', or makes the
	 *         display name, the workspace folder's name, too long; if it holds
	 *         a character beyond the Basic Multilingual Plane, which would fail
	 *         the insert on MariaDB (R33); or if CE would store the display name
	 *         as another folder name
	 */
	private String cleanName(String name, long clientNumber, long matterNumber) throws HealdanRuleException {
		String clean = name == null ? "" : name.strip();
		int max = MAX_DISPLAY_LENGTH - MatterDisplay.format(clientNumber, matterNumber, "").length();
		if (clean.isEmpty() || clean.contains("/") || clean.length() > max)
			throw new HealdanRuleException(BAD_NAME.formatted(label(), max));
		if (!BasicPlane.holds(clean))
			throw new HealdanRuleException(OUTSIDE_PLANE_NAME.formatted(label()));
		String display = MatterDisplay.format(clientNumber, matterNumber, clean);
		if (!display.equals(asFolderName(display)))
			throw new HealdanRuleException(UNSAFE_NAME.formatted(label()));
		return clean;
	}

	/**
	 * @return the name a folder store gives a folder named so: CE's
	 *         {@code HibernateFolderDAO.store} strips HTML, '/' and '\'
	 */
	private static String asFolderName(String name) {
		return HTMLSanitizer.sanitizeSimpleText(name).replace("/", "").replace("\\", "");
	}

	private String cleanDescription(String description) throws HealdanRuleException {
		String clean = description == null ? "" : description.strip();
		if (clean.length() > MAX_DESCRIPTION_LENGTH)
			throw new HealdanRuleException(LONG_DESCRIPTION.formatted(label(), MAX_DESCRIPTION_LENGTH));
		return clean.isEmpty() ? null : clean;
	}

	/**
	 * The workspace's name must be the display name: a backstop for
	 * {@link #asFolderName}, and for a name CE made unique with a suffix
	 */
	private void requireDisplayName(Folder workspace, String display) throws HealdanRuleException {
		if (!display.equals(workspace.getName())) {
			evictor.evictFoldersOnRollback(List.of(workspace.getId()));
			throw new HealdanRuleException(UNSAFE_NAME.formatted(label()));
		}
	}

	/**
	 * @return the owner
	 * @throws HealdanRuleException if the user doesn't exist or is disabled
	 */
	private static User enabledOwner(long ownerId) throws PersistenceException {
		User owner = UserDAO.get().findById(ownerId);
		if (owner == null)
			throw new HealdanRuleException(NO_OWNER);
		if (!owner.isEnabled())
			throw new HealdanRuleException(DISABLED_OWNER.formatted(owner.getUsername()));
		return owner;
	}

	private static Group ownGroup(long userId) throws PersistenceException {
		Group group = GroupDAO.get().findByName("_user_" + userId, Tenant.DEFAULT_ID);
		if (group == null)
			throw new HealdanRuleException(NO_OWNER);
		return group;
	}

	/**
	 * @return the user's own group, live or deleted: when CE deletes a user it
	 *         renames the group {@code _user_<user id>.<group id>}, marks it
	 *         deleted and keeps its ACL rows
	 */
	private List<Long> ownGroupIds(long userId) {
		String name = "_user_" + userId;
		return jdbc.query(
				"select ld_id, ld_name from ld_group where ld_tenantid = ? and (ld_name = ? or ld_name like ?)",
				(rs, i) -> {
					String found = rs.getString(2);
					// In LIKE '_' matches any character: keep the exact names only
					return found.equals(name) || found.startsWith(name + ".") ? rs.getLong(1) : null;
				}, Tenant.DEFAULT_ID, name, name + ".%").stream().filter(Objects::nonNull).toList();
	}

	private static void grantAll(Folder folder, Group group) {
		FolderAccessControlEntry entry = new FolderAccessControlEntry(group.getId());
		entry.grantPermissions(Permission.all());
		folder.addAccessControlEntry(entry);
	}

	private static FolderHistory history(User actor) {
		FolderHistory history = new FolderHistory();
		history.setUser(actor);
		return history;
	}

	private static FolderHistory history(FolderHistory tx, FolderEvent event) {
		FolderHistory history = new FolderHistory(tx);
		history.setEvent(event);
		return history;
	}

	private String label() {
		return settings.matterLabel().toLowerCase(Locale.ROOT);
	}

	/**
	 * Runs the work as a Healdan operation, passing its refusals on as they are
	 */
	private static <T> T run(HealdanOperation.Kind kind, Callable<T> work) throws PersistenceException {
		try {
			return HealdanOperation.run(kind, work);
		} catch (PersistenceException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new PersistenceException(e.getMessage(), e);
		}
	}
}
