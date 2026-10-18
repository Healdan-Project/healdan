package com.logicaldoc.healdan.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.util.spring.Context;

/**
 * Arithmetic on folder ACLs. An ACL here is the permissions each group is
 * granted, by group id; a group granted nothing is left out, since it grants
 * nothing. The ACLs are read from {@code ld_folder_acl} with JDBC on CE's data
 * source, so the reads join the caller's transaction and see what it has
 * flushed.
 */
public final class AclMath {

	/** The flags, in the order messages name them */
	static final List<Permission> FLAGS = List.of(Permission.values());

	/** A chain of security references longer than this is a cycle */
	private static final int MAX_CHAIN = 50;

	private AclMath() {
	}

	/**
	 * @param folderId a folder
	 * @return the ACL the folder grants: its own, or that of the folder its
	 *         security reference leads to, followed to the folder that holds
	 *         its own ACL
	 */
	public static Map<Long, Set<Permission>> effective(long folderId) {
		return granted(rows(source(folderId)));
	}

	/**
	 * @param folderId a folder
	 * @return the folder whose ACL it uses: itself, or the folder its security
	 *         reference leads to, followed while that one has a reference too.
	 *         A missing folder, or a cycle, ends the walk where it is
	 */
	public static long source(long folderId) {
		JdbcTemplate jdbc = jdbc();
		long current = folderId;
		Set<Long> seen = new HashSet<>(List.of(current));
		for (int i = 0; i < MAX_CHAIN; i++) {
			List<Long> refs = jdbc.queryForList("select ld_securityref from ld_folder where ld_id = ?", Long.class,
					current);
			Long ref = refs.isEmpty() ? null : refs.get(0);
			if (ref == null || ref == current || !seen.add(ref))
				return current;
			current = ref;
		}
		return current;
	}

	/**
	 * @param folderId a folder
	 * @return the folder's own rows, by group, each with the permissions it
	 *         grants, groups granted nothing included
	 */
	public static Map<Long, Set<Permission>> rows(long folderId) {
		Map<Long, Set<Permission>> rows = new TreeMap<>();
		jdbc().query("select * from ld_folder_acl where ld_folderid = ?", rs -> {
			Set<Permission> granted = EnumSet.noneOf(Permission.class);
			for (Permission flag : FLAGS)
				if (rs.getInt(column(flag)) == 1)
					granted.add(flag);
			rows.put(rs.getLong("ld_groupid"), granted);
		}, folderId);
		return rows;
	}

	/**
	 * @param entries a folder's entries, as the folder object holds them
	 * @return the ACL they grant
	 */
	public static Map<Long, Set<Permission>> ofEntries(Set<FolderAccessControlEntry> entries) {
		return granted(entriesWithEmpty(entries));
	}

	/**
	 * @param entries a folder's entries, as the folder object holds them
	 * @return their rows, groups granted nothing included
	 */
	static Map<Long, Set<Permission>> entriesWithEmpty(Collection<FolderAccessControlEntry> entries) {
		Map<Long, Set<Permission>> rows = new TreeMap<>();
		if (entries != null)
			for (FolderAccessControlEntry entry : entries) {
				Set<Permission> granted = EnumSet.noneOf(Permission.class);
				granted.addAll(entry.grantedPermissions());
				rows.merge(entry.getGroupId(), granted, (a, b) -> {
					a.addAll(b);
					return a;
				});
			}
		return rows;
	}

	/**
	 * @param child an ACL
	 * @param parent another
	 * @return true if the child grants no group anything the parent doesn't
	 *         grant it, flag by flag
	 */
	public static boolean within(Map<Long, Set<Permission>> child, Map<Long, Set<Permission>> parent) {
		return firstExcess(child, parent, false) == null;
	}

	/**
	 * @param child an ACL
	 * @param parent another
	 * @return what both grant: each group in both, with the flags both grant
	 *         it
	 */
	public static Map<Long, Set<Permission>> intersect(Map<Long, Set<Permission>> child,
			Map<Long, Set<Permission>> parent) {
		Map<Long, Set<Permission>> both = new TreeMap<>();
		for (Map.Entry<Long, Set<Permission>> entry : child.entrySet()) {
			Set<Permission> flags = parent.get(entry.getKey());
			if (flags == null)
				continue;
			Set<Permission> common = EnumSet.noneOf(Permission.class);
			common.addAll(entry.getValue());
			common.retainAll(flags);
			if (!common.isEmpty())
				both.put(entry.getKey(), common);
		}
		return both;
	}

	/**
	 * @param child an ACL
	 * @param parent another
	 * @return the first thing the child grants beyond the parent, lowest group
	 *         id first, then in the order of {@link Permission}, e.g.
	 *         {@code group IT: WRITE} or {@code user jdoe: READ}; null if
	 *         there is none
	 */
	public static String firstExcess(Map<Long, Set<Permission>> child, Map<Long, Set<Permission>> parent) {
		return firstExcess(child, parent, true);
	}

	private static String firstExcess(Map<Long, Set<Permission>> child, Map<Long, Set<Permission>> parent,
			boolean describe) {
		for (Map.Entry<Long, Set<Permission>> entry : new TreeMap<>(child).entrySet()) {
			Set<Permission> allowed = parent.getOrDefault(entry.getKey(), Set.of());
			for (Permission flag : FLAGS)
				if (entry.getValue().contains(flag) && !allowed.contains(flag))
					return describe ? "%s: %s".formatted(describe(entry.getKey()), flag.name()) : flag.name();
		}
		return null;
	}

	/**
	 * @param groupId a group
	 * @return how messages name it: "group IT", or "user jdoe" for a user's
	 *         own group
	 */
	public static String describe(long groupId) {
		JdbcTemplate jdbc = jdbc();
		List<Map<String, Object>> found = jdbc.queryForList("select ld_name, ld_type from ld_group where ld_id = ?",
				groupId);
		if (found.isEmpty())
			return "group " + groupId;
		String name = (String) found.get(0).get("LD_NAME");
		int type = ((Number) found.get(0).get("LD_TYPE")).intValue();
		if (type == Group.Type.USER.ordinal() && name != null && name.startsWith("_user_")) {
			List<String> usernames = jdbc.queryForList("select ld_username from ld_user where ld_id = ?",
					String.class, Long.parseLong(name.substring("_user_".length())));
			if (!usernames.isEmpty() && usernames.get(0) != null)
				return "user " + usernames.get(0);
		}
		return "group " + name;
	}

	/**
	 * @param flag a permission
	 * @return its column in {@code ld_folder_acl}
	 */
	static String column(Permission flag) {
		return "ld_" + flag.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * @return the rows without the groups granted nothing
	 */
	static Map<Long, Set<Permission>> granted(Map<Long, Set<Permission>> rows) {
		Map<Long, Set<Permission>> granted = new TreeMap<>();
		rows.forEach((group, flags) -> {
			if (!flags.isEmpty())
				granted.put(group, EnumSet.copyOf(flags));
		});
		return granted;
	}

	static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}
}
