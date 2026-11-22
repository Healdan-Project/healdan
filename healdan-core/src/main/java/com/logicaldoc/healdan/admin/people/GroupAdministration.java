// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.admin.Page;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.healdan.admin.people.PeopleReads.Account;
import com.logicaldoc.healdan.admin.people.PeopleReads.Counter;
import com.logicaldoc.healdan.admin.people.PeopleReads.GroupRecord;
import com.logicaldoc.healdan.admin.people.PeopleReads.Throttle;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.healdan.util.StorableText;
import com.logicaldoc.util.spring.Context;

/**
 * The Admin app's groups (Admin app spec section 8.7): the Groups list and a group's page, New Group, renaming and
 * describing a group, deleting one, and its members, through LogicalDOC's DAOs.
 * <p>
 * <b>Which group is a group:</b> a live group of the reader's tenant, not a person's own ({@code _user_...}). The
 * {@code admin}, {@code guest} and {@code publisher} groups and Everyone are fixed: never renamed or deleted, though
 * their descriptions change. A group a sign-in provider's map names isn't deleted, and a person's membership of it is
 * set again at their next sign-in through that provider.
 * <p>
 * <b>Members</b> change through {@link UserAdministration}'s guarded membership change, with a person's groups' rules:
 * a read-only account belongs only to its own group and {@code guest}, and every other person to Everyone, neither of
 * which they're taken out of; and the {@code admin} group's guards hold, under {@link UserAdministration#ADMIN_CHANGES}.
 * <p>
 * Every change refuses anyone but an administrator first, then what isn't a group, then a form's fields
 * ({@link FieldRefusal}), then Healdan's rules, each a {@link HealdanRuleException} with its sentence. A change that
 * changes nothing is answered as it is, before any rule, and isn't recorded. Each change is in the administration
 * record in its transaction.
 */
@Service
public class GroupAdministration {

	public static final String NOT_A_GROUP = "That group doesn't exist.";

	public static final String FIXED_RENAME = "The %s group is one Healdan needs, so it can't be renamed.";

	public static final String FIXED_DELETE = "The %s group is one Healdan needs, so it's never deleted.";

	public static final String MAPPED = "A sign-in provider's group map names this group. Take it out of the sign-in "
			+ "provider's group map first.";

	public static final String EVERYONE_STAYS = "Every account but a read-only one is in Everyone, so nobody is taken "
			+ "out of it.";

	public static final String GUEST_STAYS = "A read-only account always stays in guest.";

	public static final String NAME_TOO_LONG_TO_DELETE = "This group's name is too long for a deleted group to keep. "
			+ "Rename it to %d characters or fewer first.";

	public static final String TAKEN = "Another group already has that name.";

	static final String NO_NAME = "Give the group a name.";

	static final String LONG_NAME = "A group's name can't be longer than %d characters.";

	static final String BAD_NAME = "A group's name can't start with _user_ or contain control characters or "
			+ StorableText.CANT_KEEP + ".";

	static final String LONG_DESCRIPTION = "A description can't be longer than %d characters.";

	static final String BAD_DESCRIPTION = "A description can't contain control characters or "
			+ StorableText.CANT_KEEP + ".";

	/** The longest group name the Admin app gives: a deleted group keeps {@code <name>.<id>} within 255 */
	static final int MAX_NAME = 200;

	/** The longest name or description the group's columns hold */
	static final int MAX_TEXT = 255;

	/** What a person's own group is named, with the user's id after it */
	private static final String OWN_GROUP = "_user_";

	/** The groups that are never renamed or deleted */
	static final Set<String> FIXED = Set.of(Group.GROUP_ADMIN, UserAdministration.GUEST_GROUP, "publisher",
			HealdanConstants.EVERYONE_GROUP);

	/** What the administration record calls a group */
	private static final String GROUP = "group";

	/** New Group and a rename hold it until they've committed, so two groups can't take one name in two cases */
	static final Object GROUP_NAMES = new Object();

	private final PeopleReads reads;

	private final UserAdministration people;

	private final AdminEvents events;

	private final CacheEvictor evictor;

	@Autowired
	public GroupAdministration(DataSource dataSource, UserAdministration people, AdminEvents events,
			CacheEvictor evictor) {
		this.reads = new PeopleReads(dataSource);
		this.people = people;
		this.events = events;
		this.evictor = evictor;
	}

	/**
	 * @return the bean in the application context
	 */
	public static GroupAdministration get() {
		return Context.get(GroupAdministration.class);
	}

	// Reads

	/**
	 * The Groups list, the forms' group pickers among its readers: the reader's tenant's live groups, people's own
	 * left out, by name ignoring case.
	 *
	 * @param q part of a group's name, any case, the whitespace at either end left out, {@code %} and {@code _} as they
	 *        are; null or blank for every group
	 * @param offset how many groups to skip
	 * @param limit the most to return
	 * @param reader who reads it
	 * @return the page, with how many groups {@code q} keeps
	 */
	public Page<GroupRow> list(String q, int offset, int limit, User reader) {
		long tenantId = reader.getTenantId();
		String text = q != null ? q.strip().toLowerCase(Locale.ROOT) : "";
		List<GroupRecord> records = reads.groupRecords(tenantId, null).stream()
				.filter(group -> text.isEmpty() || group.name().toLowerCase(Locale.ROOT).contains(text))
				.sorted(Comparator.comparing((GroupRecord group) -> group.name().toLowerCase(Locale.ROOT))
						.thenComparingLong(GroupRecord::id))
				.toList();
		Map<Long, Long> members = reads.memberCounts(tenantId, null);
		Map<Long, List<String>> mapped = mappedBy();
		int from = Math.min(Math.max(offset, 0), records.size());
		int to = Math.min(from + Math.max(limit, 0), records.size());
		List<GroupRow> rows = records.subList(from, to).stream()
				.map(group -> row(group, members, mapped)).toList();
		return new Page<>(rows, offset, limit, records.size());
	}

	/**
	 * @param id a group
	 * @param reader who reads it
	 * @return whether it's a listed group of the reader's tenant: live, not a person's own
	 */
	public boolean isGroup(long id, User reader) {
		return !reads.groupRecords(reader.getTenantId(), id).isEmpty();
	}

	/**
	 * @param id a group
	 * @param reader who reads it
	 * @return its page; null for what isn't a group of the reader's tenant ({@link #isGroup})
	 */
	public GroupDetail group(long id, User reader) {
		List<GroupRecord> found = reads.groupRecords(reader.getTenantId(), id);
		if (found.isEmpty())
			return null;
		return new GroupDetail(row(found.get(0), reads.memberCounts(reader.getTenantId(), id), mappedBy()),
				reads.grantingFolders(id));
	}

	/**
	 * A group's members: the people in it, disabled and read-only ones too, by name ignoring case, then username.
	 *
	 * @param groupId a group
	 * @param q text a member's name, username or email holds, any case, as the Users list's {@code q}; null or blank
	 *        for every member
	 * @param offset how many members to skip
	 * @param limit the most to return
	 * @param reader who reads it
	 * @return the page; empty for what isn't a group of the reader's tenant
	 */
	public Page<MemberRow> members(long groupId, String q, int offset, int limit, User reader) {
		long tenantId = reader.getTenantId();
		if (!isGroup(groupId, reader))
			return new Page<>(List.of(), offset, limit, 0);
		String text = q != null ? q.strip().toLowerCase(Locale.ROOT) : "";
		List<Account> members = reads.accounts(tenantId, null, groupId).stream()
				.filter(account -> text.isEmpty() || account.matches(text)).sorted(PeopleReads.BY_NAME).toList();
		Map<String, Counter> counters = reads.counters(null);
		Throttle throttle = Throttle.now();
		Instant now = Instant.now();
		int from = Math.min(Math.max(offset, 0), members.size());
		int to = Math.min(from + Math.max(limit, 0), members.size());
		List<MemberRow> rows = members.subList(from, to).stream().map(account -> {
			PersonStatus status = !account.enabled() ? PersonStatus.DISABLED
					: throttle.blockedUntil(counters.get(PeopleReads.throttleAccount(account.username())), now) != null
							? PersonStatus.BLOCKED
							: PersonStatus.ACTIVE;
			return new MemberRow(account.id(), account.username(), account.name(), status, account.readOnly());
		}).toList();
		return new Page<>(rows, offset, limit, members.size());
	}

	// Changes

	/**
	 * New Group.
	 *
	 * @param name its name; the whitespace at either end is taken off
	 * @param description its description; null or blank for none
	 * @param actor who makes it
	 * @return the group's page
	 * @throws PersistenceException a refusal: {@link UserAdministration#ONLY_ADMINS}; a {@link FieldRefusal} for the
	 *         name or the description, a name another group has among them. Or an error at data layer
	 */
	public GroupDetail create(String name, String description, User actor) throws PersistenceException {
		UserAdministration.requireAdmin(actor);
		long tenantId = actor.getTenantId();
		Form form = form(name, description, tenantId, null);
		long id;
		try {
			synchronized (GROUP_NAMES) {
				id = people.inTransaction(() -> {
					if (reads.groupNameTaken(tenantId, form.name(), 0))
						throw taken();
					Group group = new Group();
					group.setName(form.name());
					group.setDescription(StringUtils.defaultString(form.description()));
					group.setTenantId(tenantId);
					group.setType(Group.Type.DEFAULT);
					GroupDAO.get().store(group);
					evictor.evictGroupsOnRollback(List.of(group.getId()));
					Map<String, Change> changes = new HashMap<>();
					changes.put("name", new Change(null, form.name()));
					if (form.description() != null)
						changes.put("description", new Change(null, form.description()));
					events.record(new AdminEvent(AdminArea.PEOPLE, "group.created", GROUP, group.getId(), form.name(),
							changes), actor);
					UserAdministration.whileLocked.run();
					return group.getId();
				});
			}
		} catch (RuntimeException | PersistenceException e) {
			// Another route made the name meanwhile, and LogicalDOC's unique index refused this one
			if (UserAdministration.uniqueViolation(e) && reads.groupNameTaken(tenantId, form.name(), 0))
				throw taken();
			throw e;
		}
		return group(id, actor);
	}

	/**
	 * Renames a group and sets its description. A fixed group keeps its name, exactly, and its description changes.
	 *
	 * @param id a group
	 * @param name its name, which it may keep; the whitespace at either end is taken off
	 * @param description its description; null or blank for none
	 * @param actor who changes it
	 * @return false if it had this name and description already, which changes nothing
	 * @throws PersistenceException a refusal: {@link UserAdministration#ONLY_ADMINS}, {@link #NOT_A_GROUP}; a
	 *         {@link FieldRefusal} for the name or the description, a name another group has among them;
	 *         {@link #FIXED_RENAME}. Or an error at data layer
	 */
	public boolean update(long id, String name, String description, User actor) throws PersistenceException {
		UserAdministration.requireAdmin(actor);
		long tenantId = actor.getTenantId();
		GroupRecord current = existing(id, tenantId);
		Form form = form(name, description, tenantId, current);
		if (form.name().equals(current.name()) && Objects.equals(form.description(), current.description()))
			return false;
		if (FIXED.contains(current.name()) && !form.name().equals(current.name()))
			throw new HealdanRuleException(FIXED_RENAME.formatted(current.name()));

		try {
			synchronized (GROUP_NAMES) {
				return people.inTransaction(() -> {
					GroupRecord now = existing(id, tenantId);
					if (!form.name().equals(now.name()) && reads.groupNameTaken(tenantId, form.name(), id))
						throw taken();
					Group group = GroupDAO.get().findById(id);
					evictor.evictGroupsOnRollback(List.of(id));
					group.setName(form.name());
					group.setDescription(StringUtils.defaultString(form.description()));
					GroupDAO.get().store(group);
					Map<String, Change> changes = new HashMap<>();
					if (!form.name().equals(now.name()))
						changes.put("name", new Change(now.name(), form.name()));
					if (!Objects.equals(form.description(), now.description()))
						changes.put("description", new Change(now.description(), form.description()));
					if (changes.isEmpty())
						return false;
					events.record(new AdminEvent(AdminArea.PEOPLE, "group.changed", GROUP, id, form.name(), changes),
							actor);
					return true;
				});
			}
		} catch (RuntimeException | PersistenceException e) {
			if (UserAdministration.uniqueViolation(e) && reads.groupNameTaken(tenantId, form.name(), id))
				throw taken();
			throw e;
		}
	}

	/**
	 * Deletes a group, taking its members out first, in one transaction, as the Classic Interface does, under
	 * {@link UserAdministration#ADMIN_CHANGES}, so it takes turns with this app's other stores of a person. The
	 * people are stored, so LogicalDOC's listeners and Healdan's run; an account that isn't a person, such as a system
	 * account, has only its membership taken out, with no store of the account, as a system account is never changed
	 * here. Folders' rows for the group stay, granting nobody. Call it outside a transaction.
	 *
	 * @param id a group
	 * @param actor who deletes it
	 * @throws PersistenceException a refusal: {@link UserAdministration#ONLY_ADMINS}, {@link #NOT_A_GROUP},
	 *         {@link #FIXED_DELETE}, {@link #MAPPED} or {@link #NAME_TOO_LONG_TO_DELETE}, or what Healdan's rules
	 *         refuse. Or an error at data layer
	 */
	public void delete(long id, User actor) throws PersistenceException {
		UserAdministration.requireAdmin(actor);
		long tenantId = actor.getTenantId();
		synchronized (UserAdministration.ADMIN_CHANGES) {
			people.inTransaction(() -> {
				// Read where it's deleted: a provider's map could name it meanwhile, or another route rename it
				GroupRecord now = existing(id, tenantId);
				if (FIXED.contains(now.name()))
					throw new HealdanRuleException(FIXED_DELETE.formatted(now.name()));
				if (mappedBy().containsKey(id))
					throw new HealdanRuleException(MAPPED);
				// A deleted group is kept as <name>.<id>, which the column must hold
				if ((now.name() + "." + id).length() > MAX_TEXT)
					throw new HealdanRuleException(
							NAME_TOO_LONG_TO_DELETE.formatted(MAX_TEXT - 1 - String.valueOf(id).length()));

				evictor.evictGroupsOnRollback(List.of(id));
				List<Account> members = reads.accounts(tenantId, null, id);
				evictor.evictUsersOnRollback(members.stream().map(Account::id).toList());
				for (Account member : members) {
					User user = UserDAO.get().findById(member.id(), true);
					user.removeGroup(id);
					UserHistory history = new UserHistory();
					history.setEvent(UserEvent.UPDATED);
					history.setAuthor(actor.getUsername());
					history.setComment("Taken out of the group " + now.name() + ", deleted in the Admin app");
					UserDAO.get().store(user, history);
				}
				reads.removeMemberships(id, reads.otherMembers(tenantId, id));
				GroupDAO.get().delete(id, PersistentObject.DELETED_CODE_DEFAULT);
				events.record(new AdminEvent(AdminArea.PEOPLE, "group.deleted", GROUP, id, now.name(), null), actor);
				return null;
			});
		}
	}

	/**
	 * Adds a person to a group; nothing changes if they're in it already. Call it outside a transaction.
	 *
	 * @param groupId a group
	 * @param userId a person
	 * @param actor who adds them
	 * @return false if they were in it already
	 * @throws PersistenceException a refusal: {@link UserAdministration#ONLY_ADMINS}, {@link #NOT_A_GROUP},
	 *         {@link UserAdministration#YOUR_ACCOUNT_DISABLED}, {@link UserAdministration#NO_PERSON},
	 *         {@link UserAdministration#SYSTEM_ACCOUNT} or {@link UserAdministration#READ_ONLY_GROUPS}. Or an error at
	 *         data layer
	 */
	public boolean addMember(long groupId, long userId, User actor) throws PersistenceException {
		UserAdministration.requireAdmin(actor);
		GroupRecord group = existing(groupId, actor.getTenantId());
		return people.memberships(userId, actor, (user, current) -> {
			Set<Long> target = new TreeSet<>(current);
			target.add(groupId);
			return target;
		}, (user, before, after) -> member("group.member-added", group, null, PersonNames.recorded(user)));
	}

	/**
	 * Takes a person out of a group; nothing changes if they aren't in it. Call it outside a transaction.
	 *
	 * @param groupId a group
	 * @param userId a person
	 * @param actor who takes them out
	 * @return false if they weren't in it
	 * @throws PersistenceException a refusal: {@link UserAdministration#ONLY_ADMINS}, {@link #NOT_A_GROUP},
	 *         {@link UserAdministration#YOUR_ACCOUNT_DISABLED}, {@link UserAdministration#NO_PERSON},
	 *         {@link UserAdministration#SYSTEM_ACCOUNT}, {@link #EVERYONE_STAYS}, {@link #GUEST_STAYS}, or the admin
	 *         group's ({@link UserAdministration#ADMIN_STAYS}, {@link UserAdministration#LEAVE_ADMIN_YOURSELF},
	 *         {@link UserAdministration#LAST_ADMIN}). Or an error at data layer
	 */
	public boolean removeMember(long groupId, long userId, User actor) throws PersistenceException {
		UserAdministration.requireAdmin(actor);
		GroupRecord group = existing(groupId, actor.getTenantId());
		return people.memberships(userId, actor, (user, current) -> {
			if (!current.contains(groupId))
				return current;
			if (HealdanConstants.EVERYONE_GROUP.equals(group.name()) && !user.isReadonly())
				throw new HealdanRuleException(EVERYONE_STAYS);
			if (UserAdministration.GUEST_GROUP.equals(group.name()) && user.isReadonly())
				throw new HealdanRuleException(GUEST_STAYS);
			Set<Long> target = new TreeSet<>(current);
			target.remove(groupId);
			return target;
		}, (user, before, after) -> member("group.member-removed", group, PersonNames.recorded(user), null));
	}

	// Helpers

	private static AdminEvent member(String action, GroupRecord group, String before, String after) {
		return new AdminEvent(AdminArea.PEOPLE, action, GROUP, group.id(), group.name(),
				Map.of("member", new Change(before, after)));
	}

	/**
	 * @return the group
	 * @throws HealdanRuleException {@link #NOT_A_GROUP} if it isn't a listed group of the tenant
	 */
	private GroupRecord existing(long id, long tenantId) throws HealdanRuleException {
		List<GroupRecord> found = reads.groupRecords(tenantId, id);
		if (found.isEmpty())
			throw new HealdanRuleException(NOT_A_GROUP);
		return found.get(0);
	}

	private static GroupRow row(GroupRecord group, Map<Long, Long> members, Map<Long, List<String>> mapped) {
		return new GroupRow(group.id(), group.name(), group.description(), members.getOrDefault(group.id(), 0L),
				FIXED.contains(group.name()), Group.GROUP_ADMIN.equals(group.name()),
				mapped.getOrDefault(group.id(), List.of()));
	}

	/**
	 * @return the labels of the sign-in providers, on or off, whose group map names each group, by label ignoring
	 *         case, by the group's id
	 */
	private static Map<Long, List<String>> mappedBy() {
		Map<Long, Set<String>> labels = new HashMap<>();
		try {
			for (SignInProvider p : SignInProviders.get().all()) {
				String label = StringUtils.isNotBlank(p.label()) ? p.label() : p.id();
				for (GroupMapping mapping : SignInProviders.get().groups(p.id()))
					labels.computeIfAbsent(mapping.groupId(), id -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
							.add(label);
			}
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the sign-in providers' group maps", e);
		}
		Map<Long, List<String>> mapped = new HashMap<>();
		labels.forEach((group, names) -> mapped.put(group, new ArrayList<>(names)));
		return mapped;
	}

	private static FieldRefusal taken() {
		return new FieldRefusal(List.of(new FieldRefusal.Field("name", FieldRefusal.TAKEN, TAKEN)));
	}

	/**
	 * A group's name and description, checked
	 *
	 * @param name the name
	 * @param description the description, null for none
	 */
	private record Form(String name, String description) {
	}

	/**
	 * @param current the group being renamed, whose own name, in any case, isn't taken, and whose name as it is is
	 *        taken as it is, as the app sends back what it was given; null for a new group
	 * @throws FieldRefusal for the fields Healdan doesn't take
	 */
	private Form form(String name, String description, long tenantId, GroupRecord current) throws FieldRefusal {
		List<FieldRefusal.Field> fields = new ArrayList<>();
		String clean = StringUtils.strip(name);
		long exceptId = current != null ? current.id() : 0;
		if (current != null && current.name().equals(clean))
			clean = current.name();
		else if (StringUtils.isEmpty(clean))
			fields.add(new FieldRefusal.Field("name", FieldRefusal.REQUIRED, NO_NAME));
		else if (clean.length() > MAX_NAME)
			fields.add(new FieldRefusal.Field("name", FieldRefusal.TOO_LONG, LONG_NAME.formatted(MAX_NAME)));
		else if (clean.startsWith(OWN_GROUP) || !StorableText.holds(clean)
				|| clean.chars().anyMatch(Character::isISOControl))
			fields.add(new FieldRefusal.Field("name", FieldRefusal.INVALID, BAD_NAME));
		else if (reads.groupNameTaken(tenantId, clean, exceptId))
			fields.add(new FieldRefusal.Field("name", FieldRefusal.TAKEN, TAKEN));

		String about = StringUtils.strip(description);
		if (StringUtils.isEmpty(about))
			about = null;
		else if (about.length() > MAX_TEXT)
			fields.add(new FieldRefusal.Field("description", FieldRefusal.TOO_LONG,
					LONG_DESCRIPTION.formatted(MAX_TEXT)));
		else if (!StorableText.holds(about) || about.chars().anyMatch(Character::isISOControl))
			fields.add(new FieldRefusal.Field("description", FieldRefusal.INVALID, BAD_DESCRIPTION));

		if (!fields.isEmpty())
			throw new FieldRefusal(fields);
		return new Form(clean, about);
	}
}
