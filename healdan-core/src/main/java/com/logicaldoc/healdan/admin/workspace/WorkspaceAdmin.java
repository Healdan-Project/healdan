// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicaldoc.core.HealdanTreeChecks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.profile.ProfileView;
import com.logicaldoc.healdan.profile.ProfileView.ProfileField;
import com.logicaldoc.healdan.profile.ProfileView.Source;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;
import com.logicaldoc.healdan.searchfolder.SearchFolderRuleDAO;
import com.logicaldoc.healdan.searchfolder.SearchFolderService;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.trash.TrashRules;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * A workspace's folders in the Workspace Editor (Admin app spec section 8.4): the tree, New Folder, rename, move, the
 * values a folder sets, and search folders. A workspace is a matter's own folder and everything in it, or a
 * template's: a folder directly in the Templates Store and everything in it ({@link #workspaceFolder}).
 * <p>
 * Every change goes through LogicalDOC's folder DAO, and a search folder's through {@link SearchFolderService}, so
 * Healdan's listeners and hooks decide as on every route: the filing rules, P1's narrowing inside a matter, and the
 * values passed down. The refusals people meet are checked here first, each with its sentence, so a refusal changes
 * nothing: the workspace's top folder, which a matter's or a template's own routes change; a name another folder in
 * the same folder has, ignoring case, a hidden one or an alias too, as LogicalDOC would otherwise give the folder a
 * name of its own making; a move out of the workspace; a document checked out in a folder that moves. Deletes go to
 * the Trash through the API's folder changes, which this class doesn't make.
 * <p>
 * Each change is recorded in the administration record inside its transaction, with the folder named by its path
 * from the workspace's top folder ({@link #label}). A change that changes nothing isn't recorded. Only administrators
 * make them.
 */
@Service
public class WorkspaceAdmin {

	/** A folder that isn't a workspace's, which the admin routes answer {@code 404} */
	public static final String NOT_IN_WORKSPACE = "That folder isn't in a workspace.";

	public static final String ONLY_ADMINS = "Only an administrator can change a workspace or a template.";

	/** A move into another workspace, with the matter's label */
	public static final String OTHER_WORKSPACE = "A folder moves only within its workspace. "
			+ "Move it to another %s in the User app.";

	static final String NO_FOLDERS_IN_SEARCH = "A search folder can't hold folders.";

	static final String NO_VALUES_IN_SEARCH = "A search folder sets no values.";

	static final String NO_SEARCH_FOLDER = "That search folder no longer exists.";

	/** A name another folder in the folder has: the folder's name, then the name */
	static final String TAKEN = "Another folder in %s is called %s.";

	static final String MATTER_TOP_RENAME = "The %1$s's own folder takes the %1$s's name: change it with Edit.";

	static final String TEMPLATE_TOP_RENAME = "The template's own folder takes the template's name: "
			+ "rename the template.";

	static final String MATTER_TOP_MOVE = "The %1$s's own folder moves with the %1$s, to another Store.";

	static final String TEMPLATE_TOP_MOVE = "A template's own folder can't be moved.";

	static final String MATTER_TOP_DELETE = "The %1$s's own folder is deleted with the %1$s.";

	static final String TEMPLATE_TOP_DELETE = "The template's own folder is deleted with the template.";

	static final String NO_TARGET = "There's no folder %d in a workspace to move it into.";

	static final String INTO_ITSELF = "A folder can't be moved into itself or a folder inside it.";

	/** The refusal FolderChanges gives for a tree that holds what the caller may not move */
	static final String MOVE_ITEMS = "It holds items you don't have permission to move.";

	/** The refusal FolderChanges gives for a document checked out in a tree */
	static final String CHECKED_OUT = "Documents in it are checked out. It can be moved once they're checked in.";

	/** What the administration record calls a folder, and a search folder */
	private static final String FOLDER = "folder";

	private static final String SEARCH_FOLDER = "search-folder";

	private final MatterLocator locator;

	private final HealdanSettings settings;

	private final FilingRules filingRules;

	private final StoredRows rows;

	private final SearchFolderRuleDAO rules;

	private final CacheEvictor evictor;

	private final AdminEvents events;

	@Autowired
	public WorkspaceAdmin(MatterLocator locator, HealdanSettings settings, FilingRules filingRules, StoredRows rows,
			SearchFolderRuleDAO rules, CacheEvictor evictor, AdminEvents events) {
		this.locator = locator;
		this.settings = settings;
		this.filingRules = filingRules;
		this.rows = rows;
		this.rules = rules;
		this.evictor = evictor;
		this.events = events;
	}

	/**
	 * @return the bean
	 */
	public static WorkspaceAdmin get() {
		return Context.get(WorkspaceAdmin.class);
	}

	/**
	 * The folders the admin routes act on, as {@code FolderAccessService.workspaceFolder} takes them.
	 *
	 * @param folderId a folder
	 * @return the folder, if it's a workspace's: a live matter's own folder or a folder or search folder in it, or a
	 *         template's top folder or a folder or search folder in it; null for anything else, such as a Store, the
	 *         Templates Store, the root, LogicalDOC's Default workspace, a search folder directly in the Templates
	 *         Store, an alias, a folder in the Trash or one that doesn't exist
	 * @throws PersistenceException error at data layer
	 */
	public Folder workspaceFolder(long folderId) throws PersistenceException {
		Folder folder = FolderDAO.get().findById(folderId);
		if (folder == null || folder.getDeleted() != 0 || folder.getType() == Folder.TYPE_ALIAS
				|| folder.getFoldRef() != null)
			return null;
		FolderPosition position = locator.locate(folder);
		return switch (position.kind()) {
		case MATTER_WORKSPACE, IN_MATTER -> position.matter() != null ? folder : null;
		// A template is a folder: a search folder directly in the Templates Store is no template's
		case IN_TEMPLATES -> position.searchFolder() && inTemplatesStore(folder) ? null : folder;
		default -> null;
		};
	}

	/**
	 * @param folder a workspace's folder
	 * @return true for the workspace's top folder: a matter's own, or a template's
	 * @throws PersistenceException error at data layer
	 */
	public boolean isTop(Folder folder) throws PersistenceException {
		FolderPosition position = locator.locate(folder);
		return position.kind() == FolderPosition.Kind.MATTER_WORKSPACE
				|| (position.kind() == FolderPosition.Kind.IN_TEMPLATES && inTemplatesStore(folder));
	}

	/**
	 * @param folderId a workspace's folder
	 * @return the workspace's top folder: a Store and the Templates Store are directly under the root, and a
	 *         workspace's top folder directly in one of them
	 * @throws PersistenceException error at data layer
	 */
	public long topOf(long folderId) throws PersistenceException {
		List<Folder> parents = FolderDAO.get().findParents(folderId);
		return parents.size() >= 3 ? parents.get(2).getId() : folderId;
	}

	/**
	 * @param folderId a workspace's folder
	 * @return how the administration record names it: its path from the workspace's top folder (a matter's display
	 *         name or a template's name), joined by {@code /}, as {@code Standard/Correspondence}
	 * @throws PersistenceException error at data layer
	 */
	public String label(long folderId) throws PersistenceException {
		FolderDAO folders = FolderDAO.get();
		List<Folder> chain = new ArrayList<>(folders.findParents(folderId));
		Folder folder = folders.findById(folderId);
		if (folder == null)
			return String.valueOf(folderId);
		chain.add(folder);
		// The root, then a Store or the Templates Store, then the workspace's top folder
		return chain.size() > 2 ? chain.subList(2, chain.size()).stream().map(Folder::getName)
				.collect(Collectors.joining("/")) : folder.getName();
	}

	/**
	 * @param workspaceFolderId a matter's own folder or a template's
	 * @return the workspace's top folder with every live folder and search folder in it, at every level, hidden ones
	 *         too; aliases left out. Each folder's children are its folders, then its search folders, each by name,
	 *         ignoring case. Null for any other folder
	 * @throws PersistenceException error at data layer
	 */
	public WorkspaceNode tree(long workspaceFolderId) throws PersistenceException {
		Folder top = workspaceFolder(workspaceFolderId);
		if (top == null || !isTop(top))
			return null;
		Map<Long, List<FolderRow>> byParent = new HashMap<>();
		for (FolderRow row : rows.descendants(workspaceFolderId))
			if (!row.alias())
				byParent.computeIfAbsent(row.parentId(), parent -> new ArrayList<>()).add(row);
		return node(FolderRow.of(top), byParent);
	}

	/**
	 * @param folderId a workspace's folder
	 * @return it as a node of the tree, without what's in it
	 */
	public WorkspaceNode node(long folderId) {
		return node(rows.folder(folderId), Map.of());
	}

	/**
	 * Refuses the delete of a workspace's top folder, which goes with its matter or template, through their own
	 * routes. Any other folder is deleted through the API's folder changes, to the Trash.
	 *
	 * @param folder a workspace's folder
	 * @throws PersistenceException the refusal, a {@link HealdanRuleException} with its sentence; or an error at data
	 *         layer
	 */
	public void refuseTopDelete(Folder folder) throws PersistenceException {
		if (isTop(folder))
			throw new HealdanRuleException(
					inTemplatesStore(folder) ? TEMPLATE_TOP_DELETE : MATTER_TOP_DELETE.formatted(matterLabel()));
	}

	private static WorkspaceNode node(FolderRow row, Map<Long, List<FolderRow>> byParent) {
		boolean search = row.type() == HealdanConstants.FOLDER_TYPE_SEARCH;
		List<FolderRow> children = new ArrayList<>(byParent.getOrDefault(row.id(), List.of()));
		children.sort(Comparator.comparing((FolderRow child) -> child.type() == HealdanConstants.FOLDER_TYPE_SEARCH)
				.thenComparing(FolderRow::name, String.CASE_INSENSITIVE_ORDER).thenComparing(FolderRow::name)
				.thenComparingLong(FolderRow::id));
		List<WorkspaceNode> nodes = new ArrayList<>();
		if (!search)
			for (FolderRow child : children)
				nodes.add(node(child, byParent));
		return new WorkspaceNode(row.id(), row.name(), search ? WorkspaceNode.SEARCH_FOLDER : WorkspaceNode.FOLDER,
				ownPermissions(row), nodes);
	}

	/**
	 * @return true when the folder has permissions of its own, false when it follows another folder's
	 */
	private static boolean ownPermissions(FolderRow row) {
		return row.securityRef() == null || row.securityRef() == row.id();
	}

	/**
	 * Creates a folder, which follows its parent's permissions and takes the values its parent passes down and, in a
	 * matter, the matter's client and matter.
	 *
	 * @param parentId a workspace's folder, its top folder included
	 * @param name the new folder's name, which the caller has checked as a folder name; it is trimmed
	 * @param actor an administrator
	 * @param sessionId the caller's session, for the history; null for none
	 * @return the new folder's id
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence; or an error at data
	 *         layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public long createFolder(long parentId, String name, User actor, String sessionId) throws PersistenceException {
		requireAdmin(actor);
		Folder parent = existing(parentId);
		if (search(parent))
			throw new HealdanRuleException(NO_FOLDERS_IN_SEARCH);
		String clean = StringUtils.strip(name);
		refuseTaken(parent, 0L, clean);

		Folder vo = new Folder(clean);
		vo.setType(Folder.TYPE_DEFAULT);
		vo.setTenantId(parent.getTenantId());
		FolderHistory history = history(actor, sessionId, FolderEvent.CREATED);
		Folder created = FolderDAO.get().create(FolderDAO.get().findById(parentId), vo, true, history);
		// The cache isn't transactional: a refusal further on mustn't leave the folder cached
		evictor.evictFoldersOnRollback(List.of(created.getId()));
		// LogicalDOC gives a folder a name of its own making when another took it meanwhile
		if (!clean.equals(created.getName()))
			throw new HealdanRuleException(TAKEN.formatted(parent.getName(), clean));

		Map<String, Change> changes = new HashMap<>();
		changes.put("name", new Change(null, clean));
		changes.put("in", new Change(null, label(parentId)));
		events.record(new AdminEvent(AdminArea.MATTERS, "folder.created", FOLDER, created.getId(),
				label(created.getId()), changes), actor);
		return created.getId();
	}

	/**
	 * Renames a folder or search folder below the workspace's top folder. Its own name changes nothing; changing only
	 * the case of it renames.
	 *
	 * @param folderId a workspace's folder
	 * @param name its new name, which the caller has checked as a folder name; it is trimmed
	 * @param actor an administrator
	 * @param sessionId the caller's session, for the history; null for none
	 * @return false if it has that name already
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence; or an error at data
	 *         layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public boolean rename(long folderId, String name, User actor, String sessionId) throws PersistenceException {
		requireAdmin(actor);
		Folder folder = existing(folderId);
		String clean = StringUtils.strip(name);
		if (clean.equals(folder.getName()))
			return false;
		if (isTop(folder))
			throw new HealdanRuleException(
					inTemplatesStore(folder) ? TEMPLATE_TOP_RENAME : MATTER_TOP_RENAME.formatted(matterLabel()));
		Folder parent = FolderDAO.get().findById(folder.getParentId());
		refuseTaken(parent, folderId, clean);

		FolderDAO folders = FolderDAO.get();
		Folder current = folders.findById(folderId, true);
		String old = current.getName();
		FolderHistory history = history(actor, sessionId, FolderEvent.RENAMED);
		history.setFilenameOld(old);
		history.setPathOld(folders.computePathExtended(folderId));
		current.setName(clean);
		evictor.evictFoldersOnRollback(List.of(folderId));
		folders.store(current, history);
		if (!clean.equals(current.getName()))
			throw new HealdanRuleException(TAKEN.formatted(parent.getName(), clean));

		events.record(new AdminEvent(AdminArea.MATTERS, "folder.renamed", FOLDER, folderId, label(folderId),
				Map.of("name", new Change(old, clean))), actor);
		return true;
	}

	/**
	 * Moves a folder or search folder below the workspace's top folder, with everything in it, into another folder of
	 * the same workspace, its top folder included. Inside a matter, one that would then grant more than its new parent
	 * is narrowed to fit, as on every route (P1). A move into the folder it's in changes nothing.
	 *
	 * @param folderId a workspace's folder
	 * @param targetId the folder to move it into
	 * @param actor an administrator
	 * @param sessionId the caller's session, for the history; null for none
	 * @return false if it's in that folder already
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence; or an error at data
	 *         layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public boolean move(long folderId, long targetId, User actor, String sessionId) throws PersistenceException {
		requireAdmin(actor);
		Folder folder = existing(folderId);
		if (isTop(folder))
			throw new HealdanRuleException(
					inTemplatesStore(folder) ? TEMPLATE_TOP_MOVE : MATTER_TOP_MOVE.formatted(matterLabel()));
		Folder target = workspaceFolder(targetId);
		if (target == null)
			throw new HealdanRuleException(NO_TARGET.formatted(targetId));
		if (topOf(targetId) != topOf(folderId))
			throw new HealdanRuleException(OTHER_WORKSPACE.formatted(matterLabel()));
		if (search(target))
			throw new HealdanRuleException(NO_FOLDERS_IN_SEARCH);
		FolderDAO folders = FolderDAO.get();
		if (targetId == folderId || folders.isInPath(folderId, targetId))
			throw new HealdanRuleException(INTO_ITSELF);
		if (folder.getParentId() == targetId)
			return false;

		try {
			HealdanTreeChecks.movedTrees(actor, List.of(folderId), targetId);
		} catch (PermissionException e) {
			throw new HealdanRuleException(MOVE_ITEMS);
		}
		if (!TrashRules.get().checkedOutIn(rows.tree(folderId)).isEmpty())
			throw new HealdanRuleException(CHECKED_OUT);
		refuseTaken(target, folderId, folder.getName());

		String from = label(folder.getParentId());
		Folder current = folders.findById(folderId, true);
		String name = current.getName();
		evictor.evictFoldersOnRollback(List.of(folderId));
		folders.move(current, folders.findById(targetId), history(actor, sessionId, FolderEvent.MOVED));
		if (!name.equals(current.getName()))
			throw new HealdanRuleException(TAKEN.formatted(target.getName(), name));

		events.record(new AdminEvent(AdminArea.MATTERS, "folder.moved", FOLDER, folderId, label(folderId),
				Map.of("in", new Change(from, label(targetId)))), actor);
		return true;
	}

	/**
	 * @param folderId a workspace's folder
	 * @return the values it sets for what's filed below it: each property of the Healdan profile that passes down
	 *         from folders, in the profile's order, but client and matter, which come from the matter
	 * @throws PersistenceException {@link #NOT_IN_WORKSPACE} for a folder that isn't a workspace's, or an error at
	 *         data layer
	 */
	public List<ProfileField> properties(long folderId) throws PersistenceException {
		existing(folderId);
		Set<String> fixed = ProfileSchema.get().fixed();
		return ProfileView.get().forFolder(FolderDAO.get().findById(folderId, true), topOf(folderId)).stream()
				.filter(field -> !fixed.contains(field.name())).toList();
	}

	/**
	 * Changes the values a folder sets, stored with LogicalDOC's folder change event, so Healdan's profile rules mark
	 * each value local or inheriting and pass the change down: a value that differs from what the folder would inherit
	 * becomes its own; one equal to it, or none, goes back to inheriting.
	 *
	 * @param folderId a workspace's folder, not a search folder
	 * @param values the values to change, by the property's name, each as LogicalDOC takes it (text, a number, a date,
	 *        true or false, a user, folder or document), or null to inherit; the properties left out stay as they are.
	 *        The caller has checked them, as the API's {@code PropertyInput} does
	 * @param actor an administrator
	 * @param sessionId the caller's session, for the history; null for none
	 * @return false if the values the folder sets of its own didn't change
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence; or an error at data
	 *         layer
	 * @throws IllegalArgumentException for a property {@link #properties} doesn't list, such as client or matter
	 */
	@Transactional(rollbackFor = Exception.class)
	public boolean setProperties(long folderId, Map<String, Object> values, User actor, String sessionId)
			throws PersistenceException {
		requireAdmin(actor);
		Folder folder = existing(folderId);
		if (search(folder))
			throw new HealdanRuleException(NO_VALUES_IN_SEARCH);
		List<ProfileField> fields = properties(folderId);
		Set<String> listed = fields.stream().map(ProfileField::name).collect(Collectors.toSet());
		for (String name : values.keySet())
			if (!listed.contains(name))
				throw new IllegalArgumentException("A folder doesn't set " + name);
		Map<String, String> before = own(fields);
		Map<String, ProfileField> byName = new HashMap<>();
		fields.forEach(field -> byName.put(field.name(), field));
		// What wouldn't change the folder's own values isn't stored
		Map<String, Object> changing = new LinkedHashMap<>();
		values.forEach((name, value) -> {
			ProfileField field = byName.get(name);
			boolean inherits = field.source() != Source.LOCAL;
			if (value == null ? !inherits : !same(value, field.value()))
				changing.put(name, value);
		});
		if (changing.isEmpty())
			return false;

		FolderDAO folders = FolderDAO.get();
		Folder current = folders.findById(folderId, true);
		Template template = ProfileSchema.get().template();
		for (Map.Entry<String, Object> value : changing.entrySet()) {
			Attribute own = current.getAttribute(value.getKey());
			if (own == null) {
				own = new Attribute(template.getAttribute(value.getKey()));
				own.setInitialization(null);
				own.setValidation(null);
				current.getAttributes().put(value.getKey(), own);
			}
			// LogicalDOC's setValue(null) would make the attribute a boolean
			Inheritance.clear(own);
			if (value.getValue() != null)
				own.setValue(value.getValue());
		}
		evictor.evictFoldersOnRollback(List.of(folderId));
		folders.store(current, history(actor, sessionId, FolderEvent.CHANGED));

		Map<String, String> after = own(properties(folderId));
		Map<String, Change> changes = new HashMap<>();
		after.forEach((name, value) -> {
			if (!Objects.equals(before.get(name), value))
				changes.put(name, new Change(before.get(name), value));
		});
		if (changes.isEmpty())
			return false;
		events.record(new AdminEvent(AdminArea.MATTERS, "folder.properties-changed", FOLDER, folderId,
				label(folderId), changes), actor);
		return true;
	}

	/**
	 * @return each property's value the folder sets of its own, as text, by name; null for one it inherits
	 */
	private static Map<String, String> own(List<ProfileField> fields) {
		Map<String, String> own = new LinkedHashMap<>();
		for (ProfileField field : fields)
			own.put(field.name(), field.source() == Source.LOCAL ? text(field.value()) : null);
		return own;
	}

	/**
	 * @param value a value as {@link #setProperties} takes it
	 * @param current a value as {@link ProfileView} gives it: a user, folder or document by its id
	 * @return true if they're the same value
	 */
	private static boolean same(Object value, Object current) {
		if (current == null)
			return false;
		return switch (value) {
		case User user -> current instanceof Number id && id.longValue() == user.getId();
		case Folder folder -> current instanceof Number id && id.longValue() == folder.getId();
		case com.logicaldoc.core.document.Document document ->
			current instanceof Number id && id.longValue() == document.getId();
		case Date date -> current instanceof Date other && other.getTime() == date.getTime();
		case Double number -> current instanceof Number other && Double.compare(other.doubleValue(), number) == 0;
		case Number number -> current instanceof Number other && other.longValue() == number.longValue()
				&& !(current instanceof Double);
		default -> value.equals(current);
		};
	}

	private static String text(Object value) {
		if (value == null)
			return null;
		return value instanceof Date date ? date.toInstant().toString() : value.toString();
	}

	/**
	 * Creates a search folder through {@link SearchFolderService#create}, after checking its name as a folder's:
	 * another folder or search folder in the folder may not have it, ignoring case. It follows its parent's
	 * permissions.
	 *
	 * @param parentId a workspace's folder, its top folder included
	 * @param name its name, which the caller has checked as a folder name; it is trimmed
	 * @param rule its rule; the folder id in it is ignored
	 * @param actor an administrator
	 * @return the new search folder's id
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence (the service's own
	 *         too, such as a window shorter than a day); or an error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public long createSearchFolder(long parentId, String name, SearchFolderRule rule, User actor)
			throws PersistenceException {
		requireAdmin(actor);
		Folder parent = existing(parentId);
		if (search(parent))
			throw new HealdanRuleException(NO_FOLDERS_IN_SEARCH);
		String clean = StringUtils.strip(name);
		refuseTaken(parent, 0L, clean);

		Folder created = SearchFolderService.get().create(parentId, clean, rule, actor);
		if (!clean.equals(created.getName()))
			throw new HealdanRuleException(TAKEN.formatted(parent.getName(), clean));

		Map<String, Change> changes = ruleChanges(null, rules.find(created.getId()).orElseThrow());
		changes.put("name", new Change(null, clean));
		changes.put("in", new Change(null, label(parentId)));
		events.record(new AdminEvent(AdminArea.MATTERS, "search-folder.created", SEARCH_FOLDER, created.getId(),
				label(created.getId()), changes), actor);
		return created.getId();
	}

	/**
	 * @param folderId a workspace's search folder
	 * @return its rule; null for a folder that isn't a workspace's search folder, or a search folder with no rule
	 * @throws PersistenceException error at data layer
	 */
	public SearchFolderRule rule(long folderId) throws PersistenceException {
		Folder folder = workspaceFolder(folderId);
		if (folder == null || !search(folder))
			return null;
		return rules.find(folderId).orElse(null);
	}

	/**
	 * Changes a search folder's rule through {@link SearchFolderService#update}, with the name it has: a search folder
	 * is renamed as a folder is ({@link #rename}).
	 *
	 * @param folderId a workspace's search folder
	 * @param rule its new rule, the whole of it; the folder id in it is ignored
	 * @param actor an administrator
	 * @return false if the rule didn't change
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence (the service's own
	 *         too); or an error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public boolean changeRule(long folderId, SearchFolderRule rule, User actor) throws PersistenceException {
		requireAdmin(actor);
		Folder folder = existing(folderId);
		if (!search(folder))
			throw new HealdanRuleException(NO_SEARCH_FOLDER);
		SearchFolderRule before = rules.find(folderId).orElse(null);
		SearchFolderService.get().update(folderId, folder.getName(), rule, actor);
		Map<String, Change> changes = ruleChanges(before, rules.find(folderId).orElseThrow());
		if (changes.isEmpty())
			return false;
		events.record(new AdminEvent(AdminArea.MATTERS, "search-folder.rule-changed", SEARCH_FOLDER, folderId,
				label(folderId), changes), actor);
		return true;
	}

	/**
	 * @return the rule's members that differ, by the contract's names, as text: {@code kind} as {@code all},
	 *         {@code documents} or {@code emails}
	 */
	private static Map<String, Change> ruleChanges(SearchFolderRule before, SearchFolderRule after) {
		Map<String, Change> changes = new HashMap<>();
		change(changes, "days", before != null ? Integer.toString(before.days()) : null,
				Integer.toString(after.days()));
		change(changes, "onlyMine", before != null ? Boolean.toString(before.onlyMine()) : null,
				Boolean.toString(after.onlyMine()));
		change(changes, "kind", before != null ? kind(before) : null, kind(after));
		change(changes, "docClass", before != null ? before.docClass() : null, after.docClass());
		change(changes, "docSubclass", before != null ? before.docSubclass() : null, after.docSubclass());
		return changes;
	}

	private static void change(Map<String, Change> changes, String name, String before, String after) {
		if (!Objects.equals(before, after))
			changes.put(name, new Change(before, after));
	}

	private static String kind(SearchFolderRule rule) {
		return rule.kind().name().toLowerCase(Locale.ROOT);
	}

	/**
	 * @return the workspace's folder
	 * @throws HealdanRuleException {@link #NOT_IN_WORKSPACE} for any other
	 */
	private Folder existing(long folderId) throws PersistenceException {
		Folder folder = workspaceFolder(folderId);
		if (folder == null)
			throw new HealdanRuleException(NOT_IN_WORKSPACE);
		return folder;
	}

	/**
	 * Refuses a name another live folder in the folder has, ignoring case: any, a hidden one, an alias or a search
	 * folder too, as LogicalDOC would give a folder named as another a name of its own making
	 *
	 * @param parent where the name would be
	 * @param exceptId the folder that would have the name, which doesn't count; 0 for a new one
	 * @param name the name
	 */
	private void refuseTaken(Folder parent, long exceptId, String name) throws HealdanRuleException {
		for (FolderRow row : rows.children(parent.getId()))
			if (row.id() != exceptId && name.equalsIgnoreCase(row.name()))
				throw new HealdanRuleException(TAKEN.formatted(parent.getName(), name));
	}

	private void requireAdmin(User actor) throws PersistenceException {
		if (actor == null || !filingRules.isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
	}

	private boolean inTemplatesStore(Folder folder) {
		return Long.valueOf(folder.getParentId()).equals(settings.templatesStoreId());
	}

	private static boolean search(Folder folder) {
		return folder.getType() == HealdanConstants.FOLDER_TYPE_SEARCH;
	}

	private String matterLabel() {
		return settings.matterLabel().toLowerCase(Locale.ROOT);
	}

	private static FolderHistory history(User actor, String sessionId, FolderEvent event) {
		FolderHistory history = new FolderHistory();
		history.setUser(actor);
		if (sessionId != null)
			history.setSessionId(sessionId);
		history.setEvent(event);
		return history;
	}
}
