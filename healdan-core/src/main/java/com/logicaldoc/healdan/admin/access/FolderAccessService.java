// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.access;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
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
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.GroupNames;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.healdan.admin.workspace.WorkspaceAdmin;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.security.PermissionRules;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Who can use a workspace's folder, the Workspace Editor's Who Can Use This Folder (Admin app spec section 8.4): its
 * rows as levels, setting its own permissions as exact flags, Follow Parent and Apply to Subfolders. A workspace is a
 * matter's own folder and everything in it, or a template's: a folder directly in the Templates Store and everything
 * in it ({@link #workspaceFolder}).
 * <p>
 * The rules that bind are checked before anything is written, each with its sentence, so a refusal changes nothing:
 * <ul>
 * <li>everywhere, a group or person that no longer exists, and {@code guest} or a read-only person's own group given
 * anything beyond Read Only's five permissions that the folder didn't grant it already, so a row kept as it is, such
 * as a read-only owner's taken over from the folder it followed, never stops a save;</li>
 * <li>on a matter's own folder, the owner's row, which keeps Full Access, whoever the owner is (a read-only owner's row
 * isn't capped, Admin app plan, P2-R20, and holds what a read-only person's row can), so the folder can't follow its
 * Store either, and P2 ({@link PermissionRules#checkP2});</li>
 * <li>below it, P1 ({@link PermissionRules#checkP1}).</li>
 * </ul>
 * In a template neither P1 nor P2 is checked as it's edited (Admin app plan, P2-R7): its problems list what New
 * Matter would refuse. The stores go through {@link FolderDAO} with the event {@code PERMISSION}, so Healdan's
 * listeners still check P1 and P2 and narrow the folders below. Only administrators make the changes.
 * <p>
 * Each change is recorded in the administration record, a save and Follow Parent inside their transaction and Apply
 * to Subfolders, which is LogicalDOC's own transaction, straight after it, each with the rows as they're stored. A
 * change that changes nothing isn't recorded. The second-level cache isn't transactional, so a save and Follow Parent
 * evict the folder if their transaction rolls back.
 */
@Service
public class FolderAccessService {

	static final String NOT_IN_WORKSPACE = "That folder isn't in a workspace.";

	static final String NO_GROUP = "That group no longer exists.";

	static final String NO_PERSON = "That person no longer exists.";

	static final String READ_ONLY_CAP = "%s can't be given more than Read Only.";

	static final String OWNER_ROW = "The %s's owner keeps Full Access to its folder.";

	static final String OWNER_FOLLOWS = "A %s's folder can't follow its Store: its owner would lose Full Access.";

	static final String NOTHING_BELOW = "This folder has no folders below it.";

	static final String ONLY_ADMINS = "Only an administrator can change who can use a workspace's folder.";

	/** What the administration record calls a folder */
	private static final String FOLDER = "folder";

	/** The start of the name of a person's own group, {@code _user_<user id>} */
	private static final String PERSONAL = "_user_";

	/** Every folder below one, at every level, the Trash's included, with its security reference */
	private static final String BELOW = """
			with recursive tree (id) as (
			  select ld_id from ld_folder where ld_parentid = ? and not ld_id = ld_parentid
			  union
			  select f.ld_id from ld_folder f join tree t on f.ld_parentid = t.id where not f.ld_id = f.ld_parentid
			)
			select f.ld_id, f.ld_securityref from ld_folder f join tree t on f.ld_id = t.id where not f.ld_id = ?""";

	private final MatterLocator locator;

	private final PermissionRules rules;

	private final HealdanSettings settings;

	private final AdminEvents events;

	private final CacheEvictor evictor;

	private final WorkspaceAdmin workspaces;

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	@Autowired
	public FolderAccessService(MatterLocator locator, PermissionRules rules, HealdanSettings settings,
			AdminEvents events, CacheEvictor evictor, WorkspaceAdmin workspaces, DataSource dataSource) {
		this.locator = locator;
		this.rules = rules;
		this.settings = settings;
		this.events = events;
		this.evictor = evictor;
		this.workspaces = workspaces;
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
	}

	/**
	 * @return the bean in the application context
	 */
	public static FolderAccessService get() {
		return Context.get(FolderAccessService.class);
	}

	/**
	 * @param folderId a folder
	 * @return the folder, if it's a workspace's, as {@link WorkspaceAdmin#workspaceFolder} takes it, which decides for
	 *         every admin route on a workspace's folders; null for anything else
	 * @throws PersistenceException error at data layer
	 */
	public Folder workspaceFolder(long folderId) throws PersistenceException {
		return workspaces.workspaceFolder(folderId);
	}

	/**
	 * @param folderId a workspace's folder
	 * @param actor who asks, for whether Apply to Subfolders is offered
	 * @return who can use the folder: its own rows, or those of the folder it follows
	 * @throws PersistenceException {@link #NOT_IN_WORKSPACE} for a folder {@link #workspaceFolder} doesn't take, or
	 *         an error at data layer
	 */
	public FolderAccess read(long folderId, User actor) throws PersistenceException {
		Folder folder = existing(folderId);
		long source = AclMath.source(folderId);
		boolean own = source == folderId;
		return new FolderAccess(folderId, own, own ? null : source,
				rows(AclMath.effective(folderId), ownersGroup(locator.locate(folder))),
				canApplyToSubfolders(folderId, actor));
	}

	/**
	 * Gives the folder permissions of its own, exactly these: each group or person gets the flags its grant gives
	 * (LogicalDOC's {@code grantPermissions} sets every flag), and every row it had before goes. A grant that gives
	 * nothing makes no row. Inside a matter, the folders below that would grant more then narrow with it.
	 *
	 * @param folderId a workspace's folder
	 * @param grants its rows, each group or person once
	 * @param actor who saves it
	 * @return who can use it now
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence: the actor isn't an
	 *         administrator, or a rule refuses the rows; or an error at data layer
	 * @throws IllegalArgumentException for a grant that names neither a group nor a person, or both, or a group or
	 *         person an earlier grant named
	 */
	@Transactional(rollbackFor = Exception.class)
	public FolderAccess save(long folderId, List<AccessGrant> grants, User actor) throws PersistenceException {
		requireAdmin(actor);
		Folder folder = existing(folderId);
		FolderPosition position = locator.locate(folder);
		long sourceBefore = AclMath.source(folderId);
		Map<Long, Set<Permission>> before = AclMath.effective(folderId);
		Map<Long, Set<Permission>> after = resolve(grants, before);

		// The owner's row on a matter's own folder keeps Full Access, whoever owns it (P2-R20)
		Long owner = ownersGroup(position);
		boolean ownFolder = position.kind() == Kind.MATTER_WORKSPACE && owner != null;
		for (Map.Entry<Long, Set<Permission>> row : after.entrySet())
			if (!ownFolder || !owner.equals(row.getKey()))
				checkReadOnlyCap(row.getKey(), added(row.getValue(), before.get(row.getKey())));
		if (ownFolder && !after.getOrDefault(owner, Set.of()).containsAll(Permission.all()))
			throw new HealdanRuleException(OWNER_ROW.formatted(matterLabel()));

		boolean ownBefore = sourceBefore == folderId;
		if (ownBefore && after.equals(before))
			return read(folderId, actor);

		Folder stored = FolderDAO.get().findById(folderId, true);
		stored.setSecurityRef(null);
		stored.getAccessControlList().clear();
		after.forEach((group, flags) -> stored.addAccessControlEntry(entry(group, flags)));

		// The rules Healdan's listener checks too, before the store: LogicalDOC's store logs a refusal as an error
		if (position.kind() == Kind.IN_MATTER)
			rules.checkP1(stored);
		else if (position.kind() == Kind.MATTER_WORKSPACE)
			rules.checkP2(after, stored.getParentId(), position.matter().getOwnerId(), folderId);

		// The cache isn't transactional: a rollback, such as a record that fails, mustn't leave the new rows cached
		evictor.evictFoldersOnRollback(List.of(folderId));
		FolderDAO.get().store(stored, history(actor, FolderEvent.PERMISSION));
		// As stored: a read-only owner's row holds what a read-only person's row can
		record(folder, ownBefore ? null : sourceBefore, null, before, AclMath.effective(folderId), actor);
		return read(folderId, actor);
	}

	/**
	 * Follow Parent: the folder gives up permissions of its own and takes its parent's, through LogicalDOC's
	 * {@code updateSecurityRef}, which points the folders that followed it at what it follows now. A matter's own
	 * folder can't: its Store's permissions would lose its owner's row.
	 *
	 * @param folderId a workspace's folder
	 * @param actor who changes it
	 * @return who can use it now
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence: the actor isn't an
	 *         administrator, or it's a matter's own folder; or an error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public FolderAccess followParent(long folderId, User actor) throws PersistenceException {
		requireAdmin(actor);
		Folder folder = existing(folderId);
		if (locator.locate(folder).kind() == Kind.MATTER_WORKSPACE)
			throw new HealdanRuleException(OWNER_FOLLOWS.formatted(matterLabel()));

		long sourceBefore = AclMath.source(folderId);
		long parentSource = AclMath.source(folder.getParentId());
		if (sourceBefore == parentSource && sourceBefore != folderId)
			return read(folderId, actor);

		Map<Long, Set<Permission>> before = AclMath.effective(folderId);
		// LogicalDOC's updateSecurityRef stores the folder, then writes the folders that followed it with a query
		evictor.evictFoldersOnRollback(List.of(folderId));
		FolderDAO.get().updateSecurityRef(folderId, folder.getParentId(), history(actor, FolderEvent.PERMISSION));
		Map<Long, Set<Permission>> after = AclMath.effective(folderId);
		record(folder, sourceBefore == folderId ? null : sourceBefore, AclMath.source(folderId), before, after, actor);
		return read(folderId, actor);
	}

	/**
	 * Apply to Subfolders, LogicalDOC's {@code applySecurityToTree}, as the Classic Interface's: every folder and
	 * search folder below this one, at every level and in the Trash too, gives up permissions of its own and follows
	 * what this folder follows (this folder, when it has its own). A restricted folder below stays, opened up to this
	 * folder's permissions; one in the Trash comes back so if it's restored. It's offered where
	 * {@link FolderAccess#canApplyToSubfolders()} is true: a live folder below, and {@code checkApplyToTree} allows
	 * it, as it always does an administrator.
	 *
	 * @param folderId a workspace's folder
	 * @param actor who applies it
	 * @param sessionId the actor's session, which LogicalDOC asks for
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence: the actor isn't an
	 *         administrator, there's no folder below, or Healdan's rules don't allow it; or an error at data layer
	 */
	public void applyToSubfolders(long folderId, User actor, String sessionId) throws PersistenceException {
		requireAdmin(actor);
		existing(folderId);
		if (!hasFolderBelow(folderId))
			throw new HealdanRuleException(NOTHING_BELOW);
		rules.checkApplyToTree(folderId, actor);

		// What changes below, for the record
		long source = AclMath.source(folderId);
		List<Long[]> below = jdbc.query(BELOW, (rs, i) -> {
			long id = rs.getLong(1);
			long ref = rs.getLong(2);
			return new Long[] { id, rs.wasNull() ? null : ref };
		}, folderId, folderId);
		long own = below.stream().filter(row -> row[1] == null || row[1].equals(row[0])).count();
		long following = below.stream().filter(row -> row[1] != null && row[1] == source).count();

		FolderHistory history = history(actor, FolderEvent.PERMISSION);
		history.setSessionId(sessionId);
		FolderDAO.get().applySecurityToTree(folderId, history);

		Map<String, Change> changes = new HashMap<>();
		if (own != 0)
			changes.put("foldersWithOwnPermissions", new Change(String.valueOf(own), "0"));
		if (following != below.size())
			changes.put("foldersFollowing", new Change(String.valueOf(following), String.valueOf(below.size())));
		if (!changes.isEmpty())
			events.recordAfter(new AdminEvent(AdminArea.MATTERS, "folder.access-applied", FOLDER, folderId,
					label(folderId), changes), actor);
	}

	/**
	 * @param acl what a folder or Store grants each group, by group id, groups granted nothing left out
	 * @param ownerGroupId the matter's owner's own group, whose row is {@code owner}; null for none
	 * @return the rows, groups first, then people, each by name ignoring case, then by id; each with its level by
	 *         {@link AccessLevel#of}
	 */
	public List<AccessRow> rows(Map<Long, Set<Permission>> acl, Long ownerGroupId) {
		if (acl.isEmpty())
			return List.of();

		record Named(String name, int type) {
		}
		Map<Long, Named> groups = new HashMap<>();
		named.query("select ld_id, ld_name, ld_type from ld_group where ld_id in (:ids)",
				new MapSqlParameterSource("ids", acl.keySet()),
				rs -> {
					groups.put(rs.getLong(1), new Named(rs.getString(2), rs.getInt(3)));
				});
		Map<Long, Long> people = new HashMap<>();
		groups.forEach((id, group) -> {
			Long userId = GroupNames.personOf(group.name(), group.type());
			if (userId != null)
				people.put(id, userId);
		});
		Map<Long, String> names = new HashMap<>();
		if (!people.isEmpty())
			named.query("select ld_id, ld_firstname, ld_name, ld_username from ld_user where ld_id in (:ids)",
					new MapSqlParameterSource("ids", new TreeSet<>(people.values())), rs -> {
						names.put(rs.getLong(1), PersonNames.of(rs.getString(2), rs.getString(3), rs.getString(4)));
					});

		List<AccessRow> rows = new ArrayList<>();
		acl.forEach((groupId, flags) -> {
			Named group = groups.get(groupId);
			Long userId = people.get(groupId);
			String name;
			if (userId != null && names.get(userId) != null)
				name = names.get(userId);
			else
				// A group with no row in ld_group, as GroupNames names it
				name = group != null ? group.name() : GroupNames.of(groupId);
			Set<Permission> granted = EnumSet.noneOf(Permission.class);
			granted.addAll(flags);
			rows.add(new AccessRow(groupId, name, userId != null, userId, AccessLevel.of(granted),
					Collections.unmodifiableSet(granted), ownerGroupId != null && ownerGroupId.longValue() == groupId));
		});
		rows.sort(Comparator.comparing(AccessRow::person)
				.thenComparing(row -> row.name().toLowerCase(Locale.ROOT)).thenComparingLong(AccessRow::groupId));
		return List.copyOf(rows);
	}

	/**
	 * @param userId a person
	 * @return the person's own group, if neither it nor they are deleted; null otherwise
	 */
	public Long personalGroupId(long userId) {
		List<Long> ids = jdbc.queryForList("""
				select G.ld_id from ld_group G, ld_user U
				 where G.ld_name = ? and G.ld_tenantid = ? and G.ld_deleted = 0 and U.ld_id = ? and U.ld_deleted = 0""",
				Long.class, PERSONAL + userId, Tenant.DEFAULT_ID, userId);
		return ids.isEmpty() ? null : ids.get(0);
	}

	/*
	 * FilingRules reloads the user's groups, which are transient
	 */
	private static void requireAdmin(User actor) throws PersistenceException {
		Objects.requireNonNull(actor, "actor");
		if (!FilingRules.get().isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
	}

	/**
	 * @return the folder
	 * @throws HealdanRuleException {@link #NOT_IN_WORKSPACE} if it isn't a workspace's
	 */
	private Folder existing(long folderId) throws PersistenceException {
		Folder folder = workspaceFolder(folderId);
		if (folder == null)
			throw new HealdanRuleException(NOT_IN_WORKSPACE);
		return folder;
	}

	/**
	 * @return whether Apply to Subfolders is offered: a live folder below, and Healdan's rules allow the actor
	 */
	private boolean canApplyToSubfolders(long folderId, User actor) throws PersistenceException {
		if (!hasFolderBelow(folderId))
			return false;
		try {
			rules.checkApplyToTree(folderId, actor);
			return true;
		} catch (HealdanRuleException e) {
			return false;
		}
	}

	private boolean hasFolderBelow(long folderId) {
		Long below = jdbc.queryForObject(
				"select count(*) from ld_folder where ld_parentid = ? and not ld_id = ld_parentid and ld_deleted = 0",
				Long.class, folderId);
		return below != null && below > 0;
	}

	/**
	 * @return the matter's owner's own group, for a folder of a matter whose owner is there; null otherwise
	 */
	private Long ownersGroup(FolderPosition position) {
		if (!position.isInsideMatter() || position.matter() == null)
			return null;
		return personalGroupId(position.matter().getOwnerId());
	}

	/**
	 * @param grants a folder's new rows
	 * @param current what the folder grants now
	 * @return the groups the grants name, each with its flags, those granting nothing left out
	 * @throws HealdanRuleException for a group or person that no longer exists; a deleted group the folder already
	 *         grants something may stay, so a save needn't take away what's there
	 */
	private Map<Long, Set<Permission>> resolve(List<AccessGrant> grants, Map<Long, Set<Permission>> current)
			throws PersistenceException {
		Map<Long, Set<Permission>> rows = new TreeMap<>();
		Set<Long> named = new TreeSet<>();
		for (AccessGrant grant : grants) {
			if ((grant.groupId() == null) == (grant.userId() == null))
				throw new IllegalArgumentException("A grant names a group or a person, one of the two");
			long group;
			if (grant.userId() != null) {
				Long own = UserDAO.get().findById(grant.userId()) != null ? personalGroupId(grant.userId()) : null;
				if (own == null)
					throw new HealdanRuleException(NO_PERSON);
				group = own;
			} else {
				group = grant.groupId();
				if (GroupDAO.get().findById(group) == null && !current.containsKey(group))
					throw new HealdanRuleException(NO_GROUP);
			}
			if (!named.add(group))
				throw new IllegalArgumentException("Group " + group + " is named twice");
			if (!grant.permissions().isEmpty())
				rows.put(group, EnumSet.copyOf(grant.permissions()));
		}
		return rows;
	}

	/**
	 * @param flags what a row is to grant
	 * @param granted what the folder grants that group now, null for nothing
	 * @return the flags it doesn't grant now
	 */
	private static Set<Permission> added(Set<Permission> flags, Set<Permission> granted) {
		Set<Permission> added = EnumSet.noneOf(Permission.class);
		added.addAll(flags);
		if (granted != null)
			added.removeAll(granted);
		return added;
	}

	/**
	 * {@code guest}, or a read-only person's own group, can't be given anything beyond Read Only's five permissions here
	 *
	 * @param flags what the row is to grant that the folder doesn't grant it now
	 */
	private static void checkReadOnlyCap(long groupId, Set<Permission> flags) throws PersistenceException {
		if (AccessLevel.READ_ONLY.permissions().containsAll(flags))
			return;
		Group group = GroupDAO.get().findById(groupId);
		if (group != null && group.isGuest())
			throw new HealdanRuleException(READ_ONLY_CAP.formatted(capitalize(AclMath.describe(groupId))));
	}

	/**
	 * Records a save or Follow Parent: where the folder takes its permissions from, and each row that changed, by
	 * whom it grants ({@link AclMath#describe}), its flags before and after
	 *
	 * @param followedBefore the folder it followed, null if it had its own
	 * @param followedAfter the folder it follows now, null if it has its own
	 */
	private void record(Folder folder, Long followedBefore, Long followedAfter, Map<Long, Set<Permission>> before,
			Map<Long, Set<Permission>> after, User actor) throws PersistenceException {
		Map<String, Change> changes = new HashMap<>();
		if (!Objects.equals(followedBefore, followedAfter))
			changes.put("follows", new Change(followedBefore != null ? label(followedBefore) : null,
					followedAfter != null ? label(followedAfter) : null));
		Set<Long> groups = new TreeSet<>(before.keySet());
		groups.addAll(after.keySet());
		for (long group : groups)
			if (!Objects.equals(before.get(group), after.get(group)))
				changes.put(rowName(group), new Change(flags(before.get(group)), flags(after.get(group))));
		if (!changes.isEmpty())
			events.record(new AdminEvent(AdminArea.MATTERS, "folder.access-changed", FOLDER, folder.getId(),
					label(folder.getId()), changes), actor);
	}

	/**
	 * @param groupId a row's group, or a person's own group
	 * @return how the record names a row: {@code group IT} or {@code user jdoe}, with the group's id after a deleted
	 *         one's, which has no name of its own
	 */
	public static String rowName(long groupId) {
		String described = AclMath.describe(groupId);
		return described.startsWith("a deleted ") ? described + " " + groupId : described;
	}

	/**
	 * @param flags what a row grants, null for no row
	 * @return the flags in {@link Permission}'s order, as the contract names them, such as {@code read, write}; null
	 *         for none, as the record gives a row's
	 */
	public static String flags(Set<Permission> flags) {
		if (flags == null)
			return null;
		return List.of(Permission.values()).stream().filter(flags::contains)
				.map(flag -> flag.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "));
	}

	/**
	 * @return how the record names a folder, as {@link WorkspaceAdmin#label} does: its path from the workspace's top
	 *         folder, as {@code Standard/Correspondence}; a folder above a workspace, such as a Store, by its name
	 */
	private String label(long folderId) throws PersistenceException {
		return workspaces.label(folderId);
	}

	/**
	 * @param groupId a group
	 * @param flags exactly what its row grants
	 * @return the row, for a folder's or a Store's access control list
	 */
	public static FolderAccessControlEntry entry(long groupId, Set<Permission> flags) {
		FolderAccessControlEntry entry = new FolderAccessControlEntry(groupId);
		entry.grantPermissions(flags);
		return entry;
	}

	/**
	 * @param actor who makes the change
	 * @param event what it is
	 * @return the history of a folder's or a Store's change
	 */
	public static FolderHistory history(User actor, FolderEvent event) {
		FolderHistory history = new FolderHistory();
		history.setUser(actor);
		history.setEvent(event);
		return history;
	}

	private String matterLabel() {
		return settings.matterLabel().toLowerCase(Locale.ROOT);
	}

	private static String capitalize(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}
}
