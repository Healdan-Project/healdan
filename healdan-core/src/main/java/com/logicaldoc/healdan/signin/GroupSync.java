package com.logicaldoc.healdan.signin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;

/**
 * A provider's group map applied to an account (M4 spec section 8). For each
 * mapped Healdan group, the person is in it when one of their provider groups
 * maps to it, and out of it otherwise. Groups outside the map are left alone,
 * and so are the person's own group and Everyone, which CE and
 * {@code EveryoneUserListener} keep.
 * <p>
 * A row whose Healdan group was deleted is ignored, and so is one that names a
 * person's own group, which the Sign-In tab refuses to save: it would put one
 * person in another's group.
 */
public final class GroupSync {

	private GroupSync() {
	}

	/**
	 * The mapped Healdan group ids the claims give; empty means the gate refuses.
	 *
	 * @param map the provider's group map
	 * @param providerGroups the values of the person's groups claim, compared
	 *        exactly
	 * @return the ids of the groups that exist and the person's groups map to;
	 *         Everyone counts, so a map can let people in without a group of
	 *         their own
	 * @throws PersistenceException error at data layer
	 */
	public static Set<Long> mapped(List<GroupMapping> map, List<String> providerGroups) throws PersistenceException {
		Set<String> values = new HashSet<>(providerGroups);
		Set<Long> granted = new LinkedHashSet<>();
		Map<Long, Group> found = new HashMap<>();
		for (GroupMapping mapping : map)
			if (values.contains(mapping.value()) && usable(group(mapping.groupId(), found)))
				granted.add(mapping.groupId());
		return granted;
	}

	/**
	 * Adds the user to every mapped group in {@code granted}, removes it from
	 * every other mapped group, and leaves the rest. It changes the account in
	 * memory, and the caller stores it. A read-only account is left as it is:
	 * CE keeps it in its own group and {@code guest} only, whatever it's given.
	 *
	 * @param user the account, with its groups (initialized)
	 * @param map the provider's group map
	 * @param granted what {@link #mapped} gave for the person
	 * @return true if it changed
	 * @throws PersistenceException error at data layer
	 */
	public static boolean apply(User user, List<GroupMapping> map, Set<Long> granted) throws PersistenceException {
		return !changes(user, map, granted).isEmpty();
	}

	/**
	 * As {@link #apply}, saying what changed, for the caller to log once the
	 * change is stored
	 *
	 * @return the changes, in the map's order
	 */
	static List<Change> changes(User user, List<GroupMapping> map, Set<Long> granted) throws PersistenceException {
		List<Change> changes = new ArrayList<>();
		if (user.isReadonly())
			return changes;

		Map<Long, Group> found = new HashMap<>();
		Set<Long> seen = new HashSet<>();
		for (GroupMapping mapping : map) {
			if (!seen.add(mapping.groupId()))
				continue;
			Group group = group(mapping.groupId(), found);
			if (!usable(group) || HealdanConstants.EVERYONE_GROUP.equals(group.getName()))
				continue;
			boolean member = user.getGroups().stream().anyMatch(g -> g.getId() == group.getId())
					|| user.getUserGroups().stream().anyMatch(ug -> ug.getGroupId() == group.getId());
			if (granted.contains(group.getId()) && !member) {
				user.addGroup(group);
				changes.add(new Change(true, group.getName()));
			} else if (!granted.contains(group.getId()) && member) {
				user.removeGroup(group.getId());
				changes.add(new Change(false, group.getName()));
			}
		}
		return changes;
	}

	/**
	 * A membership {@link #changes} made
	 *
	 * @param added true if the account was put in the group, false if it was
	 *        taken out
	 * @param group the group's name
	 */
	record Change(boolean added, String group) {
	}

	/** A group a map row may name: one that exists and isn't a person's own */
	private static boolean usable(Group group) {
		return group != null && group.getType() != Group.Type.USER;
	}

	private static Group group(long id, Map<Long, Group> found) throws PersistenceException {
		if (!found.containsKey(id))
			// Null for a deleted group too
			found.put(id, GroupDAO.get().findById(id));
		return found.get(id);
	}
}
