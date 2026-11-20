// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.stores;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.access.AccessLevel;
import com.logicaldoc.healdan.admin.access.AccessRow;
import com.logicaldoc.healdan.admin.access.FolderAccessService;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.security.PermissionRules;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.healdan.util.StorableText;
import com.logicaldoc.util.html.HTMLSanitizer;
import com.logicaldoc.util.spring.Context;

/**
 * New Store and who can see a Store (Admin app spec section 8.6). A Store is a workspace directly under the root that
 * holds matters; LogicalDOC's Default workspace and the Templates Store aren't Stores, nor is the root. A Store has one
 * level, Can See: Read, the only permission that matters on a Store.
 * <p>
 * A Store is made with its rows in one transaction, so a matter can be made in it at once, and keeps its files on
 * Original, the server's own folder, until its storage backend is changed (Admin app plan, Spec Correction 1). Who can
 * see it is set by group, a person's own group among them: each group listed gets Read, keeping any other permission
 * its row has, and every other group's row goes, but not while a group the change shuts out of the Store can read one
 * of its matters (P2, {@link PermissionRules#checkStoreP2}). Healdan's permission rules then narrow what in the
 * Store's matters follows its permissions, as on every route. Both go through {@link FolderDAO}, so Healdan's
 * listeners check them.
 * <p>
 * Each change is recorded in the administration record inside its transaction, a row's change by whom it names
 * ({@link FolderAccessService#rowName}) with its permissions before and after; a change that changes nothing isn't
 * written or recorded. Administrators only.
 */
@Service
public class StoreService {

	static final String ONLY_ADMINS = "Only an administrator can create a Store or change who can see it.";

	static final String NOT_A_STORE = "That folder isn't a Store.";

	static final String NO_GROUP = "That group no longer exists.";

	static final String NO_NAME = "Give the Store a name.";

	static final String LONG_NAME = "A Store's name can't be longer than %d characters.";

	static final String CANT_KEEP_NAME = "A Store's name can't contain " + StorableText.CANT_KEEP + ".";

	static final String UNSAFE_NAME = "A Store's name can't contain '/', '\\' or HTML tags.";

	static final String TAKEN_BY_STORE = "Another Store is already called %s.";

	static final String TAKEN_BY_TEMPLATES = "The Templates Store is already called %s.";

	static final String TAKEN_BY_DEFAULT = "LogicalDOC's Default workspace is already called %s.";

	static final String TAKEN_BY_FOLDER = "Another folder under the root is already called %s.";

	/** The longest folder name */
	static final int MAX_NAME_LENGTH = 255;

	/** What the administration record calls a Store */
	private static final String STORE = "store";

	private final MatterLocator locator;

	private final FolderAccessService folderAccess;

	private final PermissionRules rules;

	private final CacheEvictor evictor;

	private final AdminEvents events;

	private final JdbcTemplate jdbc;

	@Autowired
	public StoreService(MatterLocator locator, FolderAccessService folderAccess, PermissionRules rules,
			CacheEvictor evictor, AdminEvents events, DataSource dataSource) {
		this.locator = locator;
		this.folderAccess = folderAccess;
		this.rules = rules;
		this.evictor = evictor;
		this.events = events;
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean in the application context
	 */
	public static StoreService get() {
		return Context.get(StoreService.class);
	}

	/**
	 * @return the live Stores ({@link MatterService#stores}), by name ignoring case, then by id
	 * @throws PersistenceException error at data layer
	 */
	public List<Folder> stores() throws PersistenceException {
		return MatterService.get().stores().stream()
				.sorted(Comparator.comparing((Folder store) -> store.getName().toLowerCase(Locale.ROOT))
						.thenComparingLong(Folder::getId))
				.toList();
	}

	/**
	 * @param folderId a folder
	 * @return the folder, if it's a live Store; null for anything else, such as LogicalDOC's Default workspace, the
	 *         Templates Store, the root, a matter's folder, a Store in the Trash or one that doesn't exist
	 * @throws PersistenceException error at data layer
	 */
	public Folder store(long folderId) throws PersistenceException {
		Folder folder = FolderDAO.get().findById(folderId);
		if (folder == null || folder.getDeleted() != 0 || locator.locate(folder).kind() != Kind.STORE)
			return null;
		return folder;
	}

	/**
	 * @param storeId a Store
	 * @return who can see it: what it grants, a row of Read alone {@link AccessLevel#CAN_SEE} and any other
	 *         {@link AccessLevel#CUSTOM}; groups and people granted nothing left out
	 * @throws PersistenceException {@link #NOT_A_STORE} for a folder {@link #store} doesn't take, or an error at data
	 *         layer
	 */
	public StoreAccess access(long storeId) throws PersistenceException {
		existing(storeId);
		List<AccessRow> rows = folderAccess.rows(AclMath.effective(storeId), null).stream()
				.map(row -> new AccessRow(row.groupId(), row.name(), row.person(), row.userId(),
						level(row.permissions()), row.permissions(), false))
				.toList();
		return new StoreAccess(storeId, rows);
	}

	/**
	 * Creates a Store, a workspace directly under the root, and gives each group listed Read, all or nothing.
	 *
	 * @param name its name; the whitespace at either end is taken off
	 * @param canSeeGroupIds the groups, or people's own groups, that can see it, each once; none for administrators
	 *        alone
	 * @param actor who creates it
	 * @return the new Store
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence: the actor isn't an
	 *         administrator; the name is blank, too long, holds a character Healdan can't keep or one LogicalDOC
	 *         would store otherwise, or is another live folder's under the root, compared without regard to case; or
	 *         a group no longer exists. Or an error at data layer
	 * @throws IllegalArgumentException for a group listed twice, or a null one
	 */
	@Transactional(rollbackFor = Exception.class)
	public Folder create(String name, List<Long> canSeeGroupIds, User actor) throws PersistenceException {
		requireAdmin(actor);
		String clean = cleanName(name);
		Folder root = FolderDAO.get().findRoot(actor.getTenantId());
		checkNameFree(root, clean);
		Set<Long> groups = groups(canSeeGroupIds, Map.of());

		Folder vo = new Folder(clean);
		vo.setType(Folder.TYPE_WORKSPACE);
		vo.setTenantId(root.getTenantId());
		// Its own permissions: for an administrator, LogicalDOC adds no row
		Folder store = FolderDAO.get().create(root, vo, false, history(actor, FolderEvent.CREATED));
		// LogicalDOC's cache keeps what it loaded, even if the transaction rolls back
		evictor.evictFoldersOnRollback(List.of(store.getId()));
		// LogicalDOC makes the name unique with a number when another create has taken it meanwhile
		if (!clean.equals(store.getName()))
			throw new HealdanRuleException(TAKEN_BY_STORE.formatted(clean));

		if (!groups.isEmpty()) {
			Folder stored = FolderDAO.get().findById(store.getId(), true);
			stored.setSecurityRef(null);
			stored.getAccessControlList().clear();
			for (long group : groups)
				stored.addAccessControlEntry(entry(group, EnumSet.of(Permission.READ)));
			FolderDAO.get().store(stored, history(actor, FolderEvent.PERMISSION));
		}

		Map<String, Change> changes = new HashMap<>();
		changes.put("name", new Change(null, clean));
		AclMath.effective(store.getId()).forEach((group, flags) -> changes
				.put(FolderAccessService.rowName(group), new Change(null, FolderAccessService.flags(flags))));
		events.record(new AdminEvent(AdminArea.STORES, "store.created", STORE, store.getId(), clean, changes), actor);
		return FolderDAO.get().findById(store.getId());
	}

	/**
	 * Sets who can see a Store: each group listed gets Read, keeping any other permission its row has, and every other
	 * group's row goes, a row that lacks Read included. A Store that followed another folder's permissions gets its
	 * own.
	 *
	 * @param storeId a Store
	 * @param canSeeGroupIds the groups, or people's own groups, that can see it, each once; none for administrators
	 *        alone
	 * @param actor who changes it
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence: the actor isn't an
	 *         administrator, the folder isn't a Store, a group no longer exists (a deleted group the Store grants
	 *         something now may stay, as LogicalDOC still lets its members in), or a group the change would shut out
	 *         of the Store can read one of its live matters, the owner's own group passing while the owner could
	 *         read the Store (P2; a matter that already let in a group the Store didn't stops no other change). Or an
	 *         error at data layer
	 * @throws IllegalArgumentException for a group listed twice, or a null one
	 */
	@Transactional(rollbackFor = Exception.class)
	public void setAccess(long storeId, List<Long> canSeeGroupIds, User actor) throws PersistenceException {
		requireAdmin(actor);
		Folder store = existing(storeId);
		long source = AclMath.source(storeId);
		Map<Long, Set<Permission>> before = AclMath.effective(storeId);
		Set<Long> groups = groups(canSeeGroupIds, before);

		Map<Long, Set<Permission>> after = new TreeMap<>();
		for (long group : groups) {
			Set<Permission> flags = EnumSet.of(Permission.READ);
			flags.addAll(before.getOrDefault(group, Set.of()));
			after.put(group, flags);
		}
		boolean own = source == storeId;
		if (own && after.equals(before))
			return;
		// P2 for the Store's matters, before the write: LogicalDOC's store logs a listener's refusal as an error
		rules.checkStoreP2(storeId, after);

		Folder stored = FolderDAO.get().findById(storeId, true);
		stored.setSecurityRef(null);
		stored.getAccessControlList().clear();
		after.forEach((group, flags) -> stored.addAccessControlEntry(entry(group, flags)));
		FolderDAO.get().store(stored, history(actor, FolderEvent.PERMISSION));

		// What the Store grants now, as stored
		Map<Long, Set<Permission>> now = AclMath.effective(storeId);
		Map<String, Change> changes = new HashMap<>();
		if (!own)
			changes.put("follows", new Change(folderName(source), null));
		Set<Long> named = new TreeSet<>(before.keySet());
		named.addAll(now.keySet());
		for (long group : named)
			if (!Objects.equals(before.get(group), now.get(group)))
				changes.put(FolderAccessService.rowName(group), new Change(FolderAccessService.flags(before.get(group)),
						FolderAccessService.flags(now.get(group))));
		if (!changes.isEmpty())
			events.record(new AdminEvent(AdminArea.STORES, "store.access-changed", STORE, storeId, store.getName(),
					changes), actor);
	}

	/**
	 * @return the Store
	 * @throws HealdanRuleException {@link #NOT_A_STORE} if it isn't a live Store
	 */
	private Folder existing(long storeId) throws PersistenceException {
		Folder store = store(storeId);
		if (store == null)
			throw new HealdanRuleException(NOT_A_STORE);
		return store;
	}

	/**
	 * @return a Store's level for a row: Can See for Read alone, Custom for anything else
	 */
	private static AccessLevel level(Set<Permission> flags) {
		return flags.equals(AccessLevel.CAN_SEE.permissions()) ? AccessLevel.CAN_SEE : AccessLevel.CUSTOM;
	}

	/**
	 * @param ids the groups listed
	 * @param current what the Store grants now
	 * @return the groups, in the order listed
	 * @throws HealdanRuleException {@link #NO_GROUP} for a group that no longer exists and the Store grants nothing
	 *         now
	 */
	private static Set<Long> groups(List<Long> ids, Map<Long, Set<Permission>> current)
			throws PersistenceException {
		Objects.requireNonNull(ids, "canSeeGroupIds");
		Set<Long> groups = new LinkedHashSet<>();
		for (Long id : ids) {
			if (id == null)
				throw new IllegalArgumentException("A group is null");
			if (!groups.add(id))
				throw new IllegalArgumentException("Group " + id + " is listed twice");
		}
		for (long id : groups)
			if (!current.containsKey(id) && GroupDAO.get().findById(id) == null)
				throw new HealdanRuleException(NO_GROUP);
		return groups;
	}

	/**
	 * @return the name, the whitespace at either end taken off
	 * @throws HealdanRuleException if it's blank, too long, holds a character Healdan can't keep, or is one
	 *         LogicalDOC's folder store would change: it takes markup, {@code /} and {@code \} out
	 */
	private static String cleanName(String name) throws HealdanRuleException {
		String clean = name == null ? "" : name.strip();
		if (clean.isEmpty())
			throw new HealdanRuleException(NO_NAME);
		if (clean.length() > MAX_NAME_LENGTH)
			throw new HealdanRuleException(LONG_NAME.formatted(MAX_NAME_LENGTH));
		if (!StorableText.holds(clean))
			throw new HealdanRuleException(CANT_KEEP_NAME);
		if (!clean.equals(HTMLSanitizer.sanitizeSimpleText(clean).replace("/", "").replace("\\", "")))
			throw new HealdanRuleException(UNSAFE_NAME);
		return clean;
	}

	/**
	 * @throws HealdanRuleException if a live folder directly under the root has the name, compared without regard to
	 *         case, with a sentence for what it is
	 */
	private void checkNameFree(Folder root, String name) throws PersistenceException {
		record Child(long id, String name) {
		}
		// Compared in Java, as a rename's are (FolderRenames.taken), not with the database's lower()
		Child child = jdbc.query("""
				select ld_id, ld_name from ld_folder
				 where ld_parentid = ? and not ld_id = ld_parentid and ld_deleted = 0 order by ld_id""",
				(rs, n) -> new Child(rs.getLong(1), rs.getString(2)), root.getId()).stream()
				.filter(found -> name.equalsIgnoreCase(found.name())).findFirst().orElse(null);
		if (child == null)
			return;
		Folder clash = FolderDAO.get().findById(child.id());
		String taken = switch (locator.locate(clash).kind()) {
		case STORE -> TAKEN_BY_STORE;
		case TEMPLATES_STORE -> TAKEN_BY_TEMPLATES;
		case DEFAULT_WORKSPACE -> TAKEN_BY_DEFAULT;
		default -> TAKEN_BY_FOLDER;
		};
		throw new HealdanRuleException(taken.formatted(clash.getName()));
	}

	/**
	 * @return the folder's name, its id for one that's gone
	 */
	private String folderName(long folderId) {
		List<String> names = jdbc.queryForList("select ld_name from ld_folder where ld_id = ?", String.class,
				folderId);
		return names.isEmpty() ? String.valueOf(folderId) : names.get(0);
	}

	/*
	 * FilingRules reloads the user's groups, which are transient
	 */
	private static void requireAdmin(User actor) throws PersistenceException {
		Objects.requireNonNull(actor, "actor");
		if (!FilingRules.get().isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
	}

	private static FolderAccessControlEntry entry(long groupId, Set<Permission> flags) {
		FolderAccessControlEntry entry = new FolderAccessControlEntry(groupId);
		entry.grantPermissions(flags);
		return entry;
	}

	private static FolderHistory history(User actor, FolderEvent event) {
		FolderHistory history = new FolderHistory();
		history.setUser(actor);
		history.setEvent(event);
		return history;
	}
}
