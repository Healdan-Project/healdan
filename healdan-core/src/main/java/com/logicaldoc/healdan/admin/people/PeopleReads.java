// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.LoginThrottle;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.HibernateUserDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.healdan.listing.ListingSql;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokenDAO;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * What the people's screens read, a few queries for any number of people: the accounts, their groups, their links to
 * sign-in providers, the sign-in throttle's counts and their device tokens. The Users list and a group's members are
 * then filtered, sorted and paged in Java (ruling P4-R5).
 */
final class PeopleReads {

	/** The groups a person's row lists: live groups of the tenant, but people's own */
	static final String LISTED_GROUP = """
			g.ld_deleted = 0 and g.ld_tenantid = :tenant and g.ld_type = %d and not g.ld_name like '!_user!_%%' %s"""
			.formatted(Group.Type.DEFAULT.ordinal(), ListingSql.LIKE_ESCAPE);

	/** The people: accounts of the tenant, not deleted, not system accounts */
	static final String PERSON = "u.ld_deleted = 0 and u.ld_tenantid = :tenant and not u.ld_type = %d"
			.formatted(User.Type.SYSTEM.ordinal());

	/**
	 * An account as the people's screens read it
	 *
	 * @param id the user id
	 * @param username the username
	 * @param firstName the first name, null for a blank one
	 * @param lastName the last name, null for a blank one
	 * @param email the email address, null for a blank one
	 * @param enabled whether it's enabled
	 * @param readOnly whether it's a read-only account
	 * @param source how LogicalDOC made it, {@link UserSource}'s ordinal
	 * @param lastLogin when LogicalDOC last stamped a sign-in; filled when the account is made
	 * @param signedIn whether it has signed in: its history has a sign-in, or, as the history may not record them
	 *        ({@code history.events}), a sign-in's stamp is later than when it was made
	 * @param language its language, null for a blank one
	 * @param timeZone its time zone, null for a blank one
	 * @param created when it was made
	 * @param passwordChanged when its password last changed, null for none
	 * @param passwordExpired whether an administrator's password has to change
	 */
	record Account(long id, String username, String firstName, String lastName, String email, boolean enabled,
			boolean readOnly, int source, Instant lastLogin, boolean signedIn, String language, String timeZone,
			Instant created, Instant passwordChanged, boolean passwordExpired) {

		String name() {
			return PersonNames.of(firstName, lastName, username);
		}

		/** Whether text a person typed is in the name, username or email, any case; the text is lower case */
		boolean matches(String text) {
			return contains(username, text) || contains(email, text) || contains(firstName, text)
					|| contains(lastName, text)
					|| contains(StringUtils.defaultString(firstName) + " " + StringUtils.defaultString(lastName), text);
		}

		private static boolean contains(String value, String text) {
			return value != null && value.toLowerCase(Locale.ROOT).contains(text);
		}
	}

	/**
	 * A count of the sign-in throttle's
	 *
	 * @param value the failures counted
	 * @param lastModified when the last was
	 */
	record Counter(long value, Instant lastModified) {
	}

	/**
	 * The sign-in throttle's settings for usernames, as LogicalDOC's check reads them
	 *
	 * @param on whether the throttle is on
	 * @param max the failures that block a username; 0 for none
	 * @param waitMinutes how long a block lasts after the last failure; 0 for none
	 */
	record Throttle(boolean on, int max, int waitMinutes) {

		static Throttle now() {
			ContextProperties config = Context.get().getConfig();
			return new Throttle(config.getBoolean("throttle.enabled", false), config.getInt("throttle.username.max", 0),
					config.getInt("throttle.username.wait", 0));
		}

		/**
		 * @return when the block the count makes ends; null when it makes none now
		 */
		Instant blockedUntil(Counter counter, Instant now) {
			if (!on || max <= 0 || waitMinutes <= 0 || counter == null || counter.value() < max)
				return null;
			Instant until = counter.lastModified().plus(Duration.ofMinutes(waitMinutes));
			return until.isAfter(now) ? until : null;
		}
	}

	/** The order of the people's screens: by name ignoring case, then username ignoring case */
	static final Comparator<Account> BY_NAME = Comparator
			.comparing((Account account) -> account.name().toLowerCase(Locale.ROOT))
			.thenComparing(account -> account.username().toLowerCase(Locale.ROOT)).thenComparingLong(Account::id);

	private final NamedParameterJdbcTemplate jdbc;

	PeopleReads(DataSource dataSource) {
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @param tenantId the tenant
	 * @param userId the one person to read, or null for every person
	 * @return the people of the tenant, in no particular order
	 */
	List<Account> accounts(long tenantId, Long userId) {
		return accounts(tenantId, userId, null);
	}

	/**
	 * @param tenantId the tenant
	 * @param userId the one person to read, or null for every person
	 * @param groupId only the members of this group, or null for everyone
	 * @return the people of the tenant, in no particular order
	 */
	List<Account> accounts(long tenantId, Long userId, Long groupId) {
		MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenantId).addValue("login",
				UserEvent.LOGIN.toString());
		String one = "";
		if (userId != null) {
			one = " and u.ld_id = :id";
			params.addValue("id", userId);
		}
		if (groupId != null) {
			one += " and u.ld_id in (select ug.ld_userid from ld_usergroup ug where ug.ld_groupid = :group)";
			params.addValue("group", groupId);
		}
		return jdbc.query("""
				select u.ld_id, u.ld_username, u.ld_firstname, u.ld_name, u.ld_email, u.ld_enabled, u.ld_type,
				       u.ld_source, u.ld_lastlogin, u.ld_language, u.ld_timezone, u.ld_creation, u.ld_passwordchanged,
				       u.ld_passwordexpired,
				       case when u.ld_lastlogin > u.ld_creation
				                 or exists (select 1 from ld_user_history h
				                             where h.ld_userid = u.ld_id and h.ld_event = :login) then 1 else 0 end
				  from ld_user u
				 where""" + " " + PERSON + one, params, (rs, n) -> account(rs));
	}

	/**
	 * @param tenantId the tenant
	 * @param userId whether the account is a person of the tenant
	 */
	boolean isPerson(long tenantId, long userId) {
		Integer found = jdbc.queryForObject("select count(*) from ld_user u where " + PERSON + " and u.ld_id = :id",
				new MapSqlParameterSource("tenant", tenantId).addValue("id", userId), Integer.class);
		return found != null && found > 0;
	}

	private static Account account(ResultSet rs) throws SQLException {
		return new Account(rs.getLong(1), rs.getString(2), blankless(rs.getString(3)), blankless(rs.getString(4)),
				blankless(rs.getString(5)), rs.getInt(6) == 1, rs.getInt(7) == User.Type.READONLY.ordinal(),
				rs.getInt(8), instant(rs.getTimestamp(9)), rs.getInt(15) == 1, blankless(rs.getString(10)),
				blankless(rs.getString(11)), instant(rs.getTimestamp(12)), instant(rs.getTimestamp(13)),
				rs.getInt(14) == 1);
	}

	/**
	 * @param tenantId the tenant
	 * @param userId the one person whose groups to read, or null for every person's
	 * @return each person's listed groups, by name ignoring case, by their user id
	 */
	Map<Long, List<PersonRow.GroupRef>> groups(long tenantId, Long userId) {
		MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenantId);
		String one = "";
		if (userId != null) {
			one = " and ug.ld_userid = :id";
			params.addValue("id", userId);
		}
		Map<Long, List<PersonRow.GroupRef>> groups = new HashMap<>();
		jdbc.query("select ug.ld_userid, g.ld_id, g.ld_name from ld_usergroup ug join ld_group g on g.ld_id = "
				+ "ug.ld_groupid where " + LISTED_GROUP + one, params, rs -> {
					groups.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
							.add(new PersonRow.GroupRef(rs.getLong(2), rs.getString(3)));
				});
		Comparator<PersonRow.GroupRef> order = Comparator
				.comparing((PersonRow.GroupRef group) -> group.name().toLowerCase(Locale.ROOT))
				.thenComparingLong(PersonRow.GroupRef::id);
		groups.values().forEach(list -> list.sort(order));
		return groups;
	}

	/**
	 * @param userId the one person whose link to read, or null for everyone's
	 * @return the sign-in provider each linked account is linked to, by its user id
	 */
	Map<Long, String> links(Long userId) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		String one = "";
		if (userId != null) {
			one = " and hd_linkuser = :id";
			params.addValue("id", userId);
		}
		Map<Long, String> links = new HashMap<>();
		jdbc.query("select hd_linkuser, hd_provider from hd_signin_identity where hd_linkuser is not null "
				+ "and ld_deleted = 0" + one, params, rs -> {
					links.put(rs.getLong(1), rs.getString(2));
				});
		return links;
	}

	/**
	 * @param account the one account whose count to read, as {@link #throttleAccount} names it, or null for every
	 *        account's
	 * @return the sign-in throttle's counts of usernames, by the account they count
	 */
	Map<String, Counter> counters(String account) {
		MapSqlParameterSource params = new MapSqlParameterSource("system", Tenant.SYSTEM_ID);
		String which = " and ld_name like :prefix " + ListingSql.LIKE_ESCAPE;
		params.addValue("prefix", ListingSql.likeLiteral(LoginThrottle.LOGINFAIL_USERNAME) + "%");
		if (account != null) {
			which = " and ld_name = :name";
			params.addValue("name", LoginThrottle.LOGINFAIL_USERNAME + account);
		}
		Map<String, Counter> counters = new HashMap<>();
		jdbc.query("select ld_name, ld_value, ld_lastmodified from ld_sequence where ld_tenantid = :system "
				+ "and ld_objectid = 0 and ld_deleted = 0" + which, params, rs -> {
					counters.put(rs.getString(1).substring(LoginThrottle.LOGINFAIL_USERNAME.length()),
							new Counter(rs.getLong(2), instant(rs.getTimestamp(3))));
				});
		return counters;
	}

	/**
	 * @param userId the one person whose devices to count, or null for everyone's
	 * @return how many signed-in devices each person has, as {@link ApiTokens#list} would answer, by their user id
	 */
	Map<Long, Integer> devices(Long userId) {
		Map<Long, Integer> devices = new HashMap<>();
		try {
			List<ApiToken> tokens = userId != null ? ApiTokens.get().list(userId)
					: ApiTokenDAO.get().findByWhere("_entity.deleted = 0", null, null, null);
			Date now = new Date();
			for (ApiToken token : tokens)
				if (ApiTokens.get().usable(token, now))
					devices.merge(token.getUserId(), 1, Integer::sum);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't count the device tokens", e);
		}
		return devices;
	}

	/**
	 * @param tenantId the tenant
	 * @param ids groups
	 * @return those of them that are listed groups: live groups of the tenant but people's own
	 */
	Set<Long> listedGroupIds(long tenantId, Collection<Long> ids) {
		if (ids.isEmpty())
			return Set.of();
		return new HashSet<>(jdbc.queryForList("select g.ld_id from ld_group g where " + LISTED_GROUP
				+ " and g.ld_id in (:ids)", new MapSqlParameterSource("tenant", tenantId).addValue("ids", ids),
				Long.class));
	}

	/**
	 * @param ids groups
	 * @return their names, in no particular order
	 */
	List<String> groupNames(Collection<Long> ids) {
		if (ids.isEmpty())
			return List.of();
		return jdbc.queryForList("select ld_name from ld_group where ld_id in (:ids)",
				new MapSqlParameterSource("ids", ids), String.class);
	}

	/**
	 * Whether a username is taken: another account has it, in any case, deleted and system accounts included, as
	 * LogicalDOC compares a new account's ({@code SignInAccounts} too)
	 *
	 * @param username a username, without the whitespace at either end
	 */
	boolean usernameTaken(String username) {
		Integer count = jdbc.queryForObject("select count(*) from ld_user where lower(ld_username) = :name",
				new MapSqlParameterSource("name", username.toLowerCase(Locale.ROOT)), Integer.class);
		return count != null && count > 0;
	}

	/**
	 * Whether a group's name is taken in the tenant: a live group has it in any case, or any group has it exactly,
	 * a deleted group's kept name ({@code <name>.<id>}) among them, which LogicalDOC's unique index holds
	 *
	 * @param tenantId the tenant
	 * @param name the name, without the whitespace at either end
	 * @param exceptId the group being renamed, or 0 for none
	 */
	boolean groupNameTaken(long tenantId, String name, long exceptId) {
		// Compared in Java, as a folder's names are, not with the database's lower()
		List<Object[]> rows = jdbc.query(
				"select ld_id, ld_name, ld_deleted from ld_group where ld_tenantid = :tenant and (ld_deleted = 0 "
						+ "or ld_name = :name)",
				new MapSqlParameterSource("tenant", tenantId).addValue("name", name),
				(rs, n) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getInt(3) });
		for (Object[] row : rows) {
			if ((long) row[0] == exceptId)
				continue;
			String other = (String) row[1];
			if (name.equals(other) || (int) row[2] == 0 && name.equalsIgnoreCase(other))
				return true;
		}
		return false;
	}

	/**
	 * A listed group's row as the database holds it
	 *
	 * @param id the group's id
	 * @param name its name
	 * @param description its description, null for a blank one
	 */
	record GroupRecord(long id, String name, String description) {
	}

	/**
	 * @param tenantId the tenant
	 * @param groupId the one group to read, or null for every listed group
	 * @return the tenant's listed groups, in no particular order
	 */
	List<GroupRecord> groupRecords(long tenantId, Long groupId) {
		MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenantId);
		String one = "";
		if (groupId != null) {
			one = " and g.ld_id = :id";
			params.addValue("id", groupId);
		}
		return jdbc.query("select g.ld_id, g.ld_name, g.ld_description from ld_group g where " + LISTED_GROUP + one,
				params, (rs, n) -> new GroupRecord(rs.getLong(1), rs.getString(2), blankless(rs.getString(3))));
	}

	/**
	 * @param tenantId the tenant
	 * @param groupId the one group whose members to count, or null for every group's
	 * @return how many people each group has, by its id: accounts of the tenant, not deleted, not system accounts
	 */
	Map<Long, Long> memberCounts(long tenantId, Long groupId) {
		MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenantId);
		String one = "";
		if (groupId != null) {
			one = " and ug.ld_groupid = :id";
			params.addValue("id", groupId);
		}
		Map<Long, Long> counts = new HashMap<>();
		jdbc.query("select ug.ld_groupid, count(*) from ld_usergroup ug join ld_user u on u.ld_id = ug.ld_userid "
				+ "where " + PERSON + one + " group by ug.ld_groupid", params, rs -> {
					counts.put(rs.getLong(1), rs.getLong(2));
				});
		return counts;
	}

	/**
	 * @param groupId a group
	 * @return how many folders and search folders not in the Trash have rows of their own that give it at least one
	 *         permission
	 */
	long grantingFolders(long groupId) {
		String grants = Arrays.stream(Permission.values())
				.map(flag -> "a.ld_" + flag.name().toLowerCase(Locale.ROOT) + " = 1").collect(Collectors.joining(" or "));
		Long count = jdbc.queryForObject("""
				select count(distinct f.ld_id) from ld_folder_acl a join ld_folder f on f.ld_id = a.ld_folderid
				 where a.ld_groupid = :group and f.ld_deleted = 0
				   and (f.ld_securityref is null or f.ld_securityref = f.ld_id) and (""" + grants + ")",
				new MapSqlParameterSource("group", groupId), Long.class);
		return count != null ? count : 0;
	}

	/**
	 * @param groupId a group
	 * @return every account in it that isn't a person of the tenant, such as a system account: their memberships
	 *         go without storing them, so the accounts themselves stay as they are
	 */
	List<Long> otherMembers(long tenantId, long groupId) {
		return jdbc.queryForList("select ug.ld_userid from ld_usergroup ug where ug.ld_groupid = :group and "
				+ "not ug.ld_userid in (select u.ld_id from ld_user u where " + PERSON + ")",
				new MapSqlParameterSource("group", groupId).addValue("tenant", tenantId), Long.class);
	}

	/**
	 * Takes accounts that aren't people out of a group with JDBC
	 */
	void removeMemberships(long groupId, Collection<Long> userIds) {
		if (userIds.isEmpty())
			return;
		jdbc.update("delete from ld_usergroup where ld_groupid = :group and ld_userid in (:ids)",
				new MapSqlParameterSource("group", groupId).addValue("ids", userIds));
	}

	/**
	 * @param tenantId the tenant
	 * @param exceptUserId a person to leave out, or null for none
	 * @return how many enabled administrators the tenant has: people, enabled, in the {@code admin} group
	 */
	int enabledAdmins(long tenantId, Long exceptUserId) {
		MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenantId)
				.addValue("except", exceptUserId != null ? exceptUserId : 0L);
		Integer count = jdbc.queryForObject("""
				select count(*) from ld_user u
				  join ld_usergroup ug on ug.ld_userid = u.ld_id
				  join ld_group g on g.ld_id = ug.ld_groupid
				 where g.ld_name = 'admin' and g.ld_deleted = 0 and g.ld_tenantid = :tenant and g.ld_type = 0
				   and u.ld_enabled = 1 and not u.ld_id = :except and""" + " " + PERSON, params, Integer.class);
		return count != null ? count : 0;
	}

	/**
	 * @param userId an account
	 * @return whether its stored account is enabled and not deleted, as committed now
	 */
	boolean enabled(long userId) {
		Integer count = jdbc.queryForObject(
				"select count(*) from ld_user where ld_id = :id and ld_enabled = 1 and ld_deleted = 0",
				new MapSqlParameterSource("id", userId), Integer.class);
		return count != null && count > 0;
	}

	/**
	 * @param userId a person
	 * @return how many live matters they own: not deleted, their folders not in the Trash
	 */
	long mattersOwned(long userId) {
		Long count = jdbc.queryForObject("""
				select count(*) from hd_matter m
				  join hd_client c on c.ld_id = m.hd_clientid and c.ld_deleted = 0
				  join ld_folder f on f.ld_id = m.hd_folderid and f.ld_deleted = 0
				 where m.ld_deleted = 0 and m.hd_ownerid = :id""", new MapSqlParameterSource("id", userId), Long.class);
		return count != null ? count : 0;
	}

	/**
	 * @param providers every sign-in provider
	 * @return how a person's row names the provider: its button's label; its id once it's gone or has none
	 */
	static SignsInWith.Provider provider(String id, Map<String, SignInProvider> providers) {
		SignInProvider p = providers.get(id);
		String label = p != null && StringUtils.isNotBlank(p.label()) ? p.label() : id;
		return new SignsInWith.Provider(id, label);
	}

	/**
	 * @return every sign-in provider, by its id
	 */
	static Map<String, SignInProvider> providers() {
		Map<String, SignInProvider> providers = new HashMap<>();
		for (SignInProvider p : SignInProviders.get().all())
			providers.put(p.id(), p);
		return providers;
	}

	/**
	 * The account the sign-in throttle counts a username's failures under, as LogicalDOC's sign-in names it: the
	 * username trimmed, and in lower case when sign-in ignores case.
	 *
	 * @param username a username, as stored
	 * @return the account
	 */
	static String throttleAccount(String username) {
		String account = StringUtils.substringBefore(StringUtils.defaultString(username), ">").trim();
		return HibernateUserDAO.ignoreCaseLogin() ? account.toLowerCase(Locale.ROOT) : account;
	}

	private static String blankless(String text) {
		return StringUtils.isBlank(text) ? null : text;
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp != null ? timestamp.toInstant() : null;
	}
}
