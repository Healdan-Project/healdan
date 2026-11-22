// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.HealdanThrottle;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.core.security.user.UserSource;
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
import com.logicaldoc.healdan.admin.people.PeopleReads.Throttle;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.signin.OutgoingEmail;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.TemporaryPasswords;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * The Admin app's people (Admin app spec section 8.7): the Users list and a person's page, and every change to an
 * account made there, through LogicalDOC's DAOs, so its listeners and Healdan's run as on every route.
 * <p>
 * <b>Who is a person:</b> an account of the reader's tenant, not deleted, and not a system account. A system account
 * is never changed here. A read-only account belongs only to its own group and {@code guest}.
 * <p>
 * <b>The guards:</b> the {@code admin} account is never disabled or taken out of the {@code admin} group, nobody
 * disables or takes themselves out of it, and no change made here leaves the tenant without an enabled administrator.
 * Every change that could lower their count holds {@link #ADMIN_CHANGES}, runs in a transaction of its own, refuses a
 * caller whose own account is no longer enabled, counts them after its write and commits before it lets go, so two
 * administrators taking each other out at once leave one. Edit, Enable, Reset Password and a group's delete hold it
 * too, so this app's stores of a person take turns and none puts back groups another has just changed.
 * <p>
 * <b>Signing out:</b> Disable and Reset Password revoke the person's device tokens in their store, through
 * {@code TokenUserListener}, and once they commit close every open session on this server that the person opened, as
 * themselves or as another account ({@link PersonSessions}); Sign Out Everywhere does both. A session is closed by its
 * id, never through the request that asks.
 * <p>
 * <b>Temporary passwords:</b> New User and Reset Password give the account a temporary password the person changes at
 * their next sign-in, made and emailed by {@link TemporaryPasswords} once the change has committed. It's in their
 * answer once, when it isn't emailed, and in no record, log line, exception or other answer.
 * <p>
 * Every change refuses anyone but an administrator first, then what isn't a person, then a form's fields
 * ({@link FieldRefusal}), then Healdan's rules, each a {@link HealdanRuleException} with its sentence. A refusal
 * changes nothing. Each change is in the administration record, in its transaction, or straight after it when it
 * isn't one; a change that changes nothing isn't made or recorded.
 */
@Service
public class UserAdministration {

	/**
	 * Every change that could lower the number of enabled administrators, and every other store of a person made here,
	 * takes it outside its transaction and holds it until it has committed
	 */
	static final Object ADMIN_CHANGES = new Object();

	/** New User holds it until it has committed, so two accounts can't take one username in two cases */
	static final Object NEW_USERNAMES = new Object();

	/** Nothing, the default of {@link #whileLocked} */
	static final Runnable NOTHING = () -> {
	};

	/**
	 * Run inside each lock's transaction, once it has written and, for a change of the enabled administrators, counted
	 * them; a test sets it to hold a change there while another tries to start
	 */
	static volatile Runnable whileLocked = NOTHING;

	public static final String ONLY_ADMINS = "Only an administrator can change people's accounts and groups.";

	public static final String YOUR_ACCOUNT_DISABLED = "Your own account has been disabled, so you can't change "
			+ "people's accounts and groups.";

	public static final String NO_PERSON = "That person doesn't exist.";

	public static final String SYSTEM_ACCOUNT = "A system account can't be changed.";

	public static final String NO_GROUP = "That group doesn't exist.";

	public static final String DISABLE_ADMIN = "The admin account can't be disabled.";

	public static final String DISABLE_YOURSELF = "You can't disable your own account.";

	public static final String ADMIN_STAYS = "The admin account always stays in the admin group.";

	public static final String LEAVE_ADMIN_YOURSELF = "Another administrator has to take you out of the admin group.";

	public static final String LAST_ADMIN = "That would leave no enabled administrator. Make someone else an "
			+ "administrator first.";

	public static final String RESET_PROVIDER = "This person signs in through a sign-in provider, so they have no "
			+ "password here to reset.";

	public static final String RESET_YOURSELF = "Change your own password from your person menu.";

	public static final String READ_ONLY_GROUPS = "A read-only account belongs only to its own group and guest.";

	public static final String TAKEN = "Another account already has that username.";

	/** LogicalDOC's administrator's username, the account that always stays in the admin group */
	static final String ADMIN_ACCOUNT = "admin";

	/** The admin group's name */
	static final String ADMIN_GROUP = Group.GROUP_ADMIN;

	/** The group read-only accounts belong to */
	static final String GUEST_GROUP = "guest";

	/** What the administration record calls a person */
	static final String USER = "user";

	private final PeopleReads reads;

	private final AdminEvents events;

	private final CacheEvictor evictor;

	private final TransactionTemplate transactions;

	private final SessionFactory sessionFactory;

	@Autowired
	public UserAdministration(DataSource dataSource, AdminEvents events, CacheEvictor evictor,
			PlatformTransactionManager transactionManager, @Qualifier("sessionFactory") SessionFactory sessionFactory) {
		this.reads = new PeopleReads(dataSource);
		this.events = events;
		this.evictor = evictor;
		this.transactions = new TransactionTemplate(transactionManager);
		this.sessionFactory = sessionFactory;
	}

	/**
	 * @return the bean in the application context
	 */
	public static UserAdministration get() {
		return Context.get(UserAdministration.class);
	}

	// Reads

	/**
	 * The Users list: the reader's tenant's people, filtered, sorted and paged.
	 *
	 * @param filter which people to keep
	 * @param sort the column to sort by; ties go by username, ignoring case, whichever the order
	 * @param descending true for the highest first
	 * @param offset how many people to skip
	 * @param limit the most to return
	 * @param reader who reads it, whose tenant's people it lists
	 * @return the page
	 */
	public Page<PersonRow> list(PeopleFilter filter, PeopleSort sort, boolean descending, int offset, int limit,
			User reader) {
		Objects.requireNonNull(filter, "filter");
		Objects.requireNonNull(sort, "sort");
		long tenantId = reader.getTenantId();
		List<Account> accounts = reads.accounts(tenantId, null);
		String text = filter.q() != null ? filter.q().strip().toLowerCase(Locale.ROOT) : "";
		Set<Long> matching = accounts.stream().filter(account -> text.isEmpty() || account.matches(text))
				.map(Account::id).collect(Collectors.toSet());
		List<PersonRow> kept = rows(tenantId, null, accounts).stream().filter(row -> matching.contains(row.id()))
				.filter(row -> filter.groupId() == null
						|| row.groups().stream().anyMatch(group -> group.id() == filter.groupId()))
				.filter(row -> filter.status() == null || row.status() == filter.status())
				.sorted(order(sort, descending)).toList();
		int from = Math.min(Math.max(offset, 0), kept.size());
		int to = Math.min(from + Math.max(limit, 0), kept.size());
		return new Page<>(kept.subList(from, to), offset, limit, kept.size());
	}

	/**
	 * @param id an account
	 * @param reader who reads it
	 * @return whether it's a person of the reader's tenant: not deleted and not a system account
	 */
	public boolean isPerson(long id, User reader) {
		return reads.isPerson(reader.getTenantId(), id);
	}

	/**
	 * A person's page, with which of its changes the reader would be refused now.
	 *
	 * @param id an account
	 * @param reader who reads it
	 * @return the person; null for what isn't a person of the reader's tenant ({@link #isPerson})
	 */
	public Person person(long id, User reader) {
		long tenantId = reader.getTenantId();
		List<Account> found = reads.accounts(tenantId, id);
		if (found.isEmpty())
			return null;
		Account account = found.get(0);
		PersonRow row = rows(tenantId, id, found).get(0);
		boolean provider = providerAccount(UserSource.values()[account.source()]);
		boolean mustChange = !provider
				&& (account.passwordExpired() || UserDAO.get().isPasswordExpired(account.username()));
		return new Person(row, account.language() != null ? account.language() : UserForm.defaultLanguage(tenantId),
				account.timeZone(), account.created(), provider ? null : account.passwordChanged(), mustChange,
				PersonCheckouts.count(id), reads.mattersOwned(id), refusals(row, provider, reader));
	}

	/**
	 * @param reader who asks
	 * @return what New User and Edit offer and take
	 */
	public UserOptions options(User reader) {
		long tenantId = reader.getTenantId();
		List<SignsInWith.Provider> providers = SignInProviders.get().enabled().stream()
				.map(p -> new SignsInWith.Provider(p.id(),
						StringUtils.isNotBlank(p.label()) ? p.label() : p.id()))
				.toList();
		return new UserOptions(UserForm.languages(tenantId), UserForm.defaultLanguage(tenantId), UserForm.timeZones(),
				providers, OutgoingEmail.isSetUp());
	}

	/**
	 * @param id a person
	 * @return their signed-in devices, as {@link ApiTokens#list} gives their tokens, newest first; never a token or
	 *         its hash
	 */
	public List<DeviceRow> devices(long id) {
		Map<String, SignInProvider> providers = PeopleReads.providers();
		return ApiTokens.get().list(id).stream()
				.map(token -> new DeviceRow(token.getId(), token.getName(), token.getDevice(),
						token.getProvider() != null ? PeopleReads.provider(token.getProvider(), providers) : null,
						instant(token.getCreation()), instant(token.getLastUsed()), instant(token.getExpires())))
				.toList();
	}

	/**
	 * The documents a person has checked out, as the User app's Checked Out to Me reads them for its reader, but
	 * every one of them, as an administrator reads every document: documents with a doc # in a Store, locks left out.
	 *
	 * @param id a person
	 * @return their check-outs, the latest first, then by doc #, highest first
	 */
	public List<CheckedOut> checkouts(long id) {
		return PersonCheckouts.of(id);
	}

	// Changes

	/**
	 * New User: makes a password account, in Everyone and the groups given, with a temporary password that has to
	 * change at the first sign-in. Once the account has committed the password is emailed, if asked; it's returned
	 * when it isn't emailed. Call it outside a transaction.
	 *
	 * @param draft the person's details
	 * @param emailPassword whether to email the temporary password
	 * @param actor who makes it
	 * @return the person, and the password unless it was emailed
	 * @throws PersistenceException a refusal: {@link #ONLY_ADMINS}; a {@link FieldRefusal} for the draft's fields,
	 *         a username that another account has in any case among them; {@link #NO_GROUP}. Or an error at data
	 *         layer
	 * @throws IllegalArgumentException for a group listed twice, or a null one
	 */
	public Created create(UserDraft draft, boolean emailPassword, User actor) throws PersistenceException {
		requireAdmin(actor);
		Objects.requireNonNull(draft, "draft");
		long tenantId = actor.getTenantId();
		Set<Long> groupIds = distinct(draft.groupIds());
		UserForm form = UserForm.check(draft, tenantId, null, this::taken);
		checkGroups(groupIds, tenantId);
		String password = TemporaryPasswords.make(tenantId);

		long id;
		try {
			synchronized (NEW_USERNAMES) {
				id = inTransaction(() -> {
					if (taken(form.username()))
						throw new FieldRefusal(List.of(new FieldRefusal.Field("username", FieldRefusal.TAKEN, TAKEN)));
					// Again where they're added: a group deleted meanwhile is still read, and would be joined
					checkGroups(groupIds, tenantId);
					User user = new User();
					user.setUsername(form.username());
					user.setFirstName(form.firstName());
					user.setName(form.lastName());
					user.setEmail(StringUtils.defaultString(form.email()));
					user.setLanguage(form.language());
					user.setTimeZone(form.timeZone());
					user.setTenantId(tenantId);
					user.setType(User.Type.DEFAULT);
					user.setSource(UserSource.DEFAULT);
					user.setEnabled(true);
					setPassword(user, password);
					for (long groupId : groupIds)
						user.addGroup(GroupDAO.get().findById(groupId));
					UserDAO.get().store(user, history(actor, UserEvent.CREATED));
					evictor.evictUsersOnRollback(List.of(user.getId()));

					Map<String, Change> changes = new HashMap<>();
					changes.put("username", new Change(null, form.username()));
					changes.put("firstName", new Change(null, form.firstName()));
					changes.put("lastName", new Change(null, form.lastName()));
					if (form.email() != null)
						changes.put("email", new Change(null, form.email()));
					changes.put("language", new Change(null, form.language()));
					if (form.timeZone() != null)
						changes.put("timeZone", new Change(null, form.timeZone()));
					changes.put("groups", new Change(null, groupNames(listedGroups(user.getId(), tenantId))));
					changes.put("password", Change.secret());
					events.record(new AdminEvent(AdminArea.PEOPLE, "user.created", USER, user.getId(),
							PersonNames.recorded(form.firstName(), form.lastName(), form.username()), changes), actor);
					whileLocked.run();
					return user.getId();
				});
			}
		} catch (RuntimeException | PersistenceException e) {
			// Another route made the username meanwhile, and LogicalDOC's unique index refused this one: its
			// transaction has rolled back, and the other has committed, so the check sees it
			if (uniqueViolation(e) && taken(form.username()))
				throw new FieldRefusal(List.of(new FieldRefusal.Field("username", FieldRefusal.TAKEN, TAKEN)));
			throw e;
		}

		NotEmailed notEmailed = emailPassword ? email(UserDAO.get().findById(id), password) : NotEmailed.NOT_ASKED;
		Person person = person(id, actor);
		return notEmailed == null ? new Created(person, null, true, null)
				: new Created(person, password, false, notEmailed);
	}

	/**
	 * Edit: changes a person's names, email, language and time zone. A provider account's names and email may be
	 * set again at its next sign-in through the provider.
	 *
	 * @param id a person
	 * @param draft their details, all of them, made with {@link UserDraft#edit}
	 * @param actor who changes them
	 * @return false if they had these details already, which changes nothing
	 * @throws PersistenceException a refusal: {@link #ONLY_ADMINS}, {@link #NO_PERSON}, {@link #SYSTEM_ACCOUNT}, or a
	 *         {@link FieldRefusal} for the draft's fields, a username among them, which never changes. A name, an
	 *         email, a language or a time zone the person has already is taken as it is, a blank name among them. Or an
	 *         error at data layer
	 * @throws IllegalArgumentException for a draft with groups, which an edit doesn't change
	 */
	public boolean update(long id, UserDraft draft, User actor) throws PersistenceException {
		requireAdmin(actor);
		Objects.requireNonNull(draft, "draft");
		if (draft.groupIds() != null)
			throw new IllegalArgumentException("An edit changes no groups");
		synchronized (ADMIN_CHANGES) {
			return inTransaction(() -> {
				User user = changeable(id, actor.getTenantId());
				UserForm form = UserForm.check(draft, user.getTenantId(), user, this::taken);
				String language = StringUtils.isBlank(user.getLanguage())
						? UserForm.defaultLanguage(user.getTenantId())
						: user.getLanguage();
				Map<String, Change> changes = new HashMap<>();
				// A stored blank name, which a provider's claims can leave, stays blank until one is typed
				changed(changes, "firstName", blankless(user.getFirstName()), blankless(form.firstName()));
				changed(changes, "lastName", blankless(user.getName()), blankless(form.lastName()));
				changed(changes, "email", blankless(user.getEmail()), form.email());
				changed(changes, "language", language, form.language());
				changed(changes, "timeZone", blankless(user.getTimeZone()), form.timeZone());
				if (changes.isEmpty())
					return false;

				user.setFirstName(StringUtils.defaultString(form.firstName()));
				user.setName(StringUtils.defaultString(form.lastName()));
				user.setEmail(StringUtils.defaultString(form.email()));
				user.setLanguage(form.language());
				user.setTimeZone(form.timeZone());
				evictor.evictUsersOnRollback(List.of(id));
				UserDAO.get().store(user, history(actor, UserEvent.UPDATED));
				events.record(new AdminEvent(AdminArea.PEOPLE, "user.changed", USER, id, PersonNames.recorded(user),
						changes), actor);
				return true;
			});
		}
	}

	/**
	 * Disable: the person can't sign in on any route. Their device tokens are revoked in the store and, once it
	 * commits, every open session they opened, as themselves or as another account, closes. Call it outside a
	 * transaction.
	 *
	 * @param id a person
	 * @param actor who disables them
	 * @return false if they were disabled already, which changes nothing, whoever they are; their sessions close all
	 *         the same
	 * @throws PersistenceException a refusal: {@link #ONLY_ADMINS}, {@link #YOUR_ACCOUNT_DISABLED}, {@link #NO_PERSON},
	 *         {@link #SYSTEM_ACCOUNT}, {@link #DISABLE_ADMIN}, {@link #DISABLE_YOURSELF} or {@link #LAST_ADMIN}. Or an
	 *         error at data layer
	 */
	public boolean disable(long id, User actor) throws PersistenceException {
		Objects.requireNonNull(actor, "actor");
		Disabling outcome;
		synchronized (ADMIN_CHANGES) {
			outcome = inTransaction(() -> {
				requireEnabledAdmin(actor);
				User user = changeable(id, actor.getTenantId());
				// Nothing to change comes before the guards: disabling a disabled account, admin's too, does nothing
				if (!user.isEnabled())
					return new Disabling(user.getUsername(), false);
				if (ADMIN_ACCOUNT.equals(user.getUsername()))
					throw new HealdanRuleException(DISABLE_ADMIN);
				if (user.getId() == actor.getId())
					throw new HealdanRuleException(DISABLE_YOURSELF);
				boolean lowersAdmins = user.isAdmin();
				if (lowersAdmins && reads.enabledAdmins(user.getTenantId(), user.getId()) == 0)
					throw new HealdanRuleException(LAST_ADMIN);

				user.setEnabled(false);
				evictor.evictUsersOnRollback(List.of(id));
				UserDAO.get().store(user, history(actor, UserEvent.DISABLED));
				checkAdminsLeft(lowersAdmins, user.getTenantId());
				events.record(new AdminEvent(AdminArea.PEOPLE, "user.disabled", USER, id, PersonNames.recorded(user),
						null), actor);
				return new Disabling(user.getUsername(), true);
			});
		}
		PersonSessions.closeAll(id, outcome.username());
		return outcome.disabled();
	}

	/**
	 * What a Disable found
	 *
	 * @param username the person's username, as stored, whose sessions close
	 * @param disabled whether it disabled them, false if they were disabled already
	 */
	private record Disabling(String username, boolean disabled) {
	}

	/**
	 * Enable: the person can sign in again. No device token comes back.
	 *
	 * @param id a person
	 * @param actor who enables them
	 * @return false if they were enabled already, which changes nothing
	 * @throws PersistenceException a refusal: {@link #ONLY_ADMINS}, {@link #NO_PERSON} or {@link #SYSTEM_ACCOUNT}. Or
	 *         an error at data layer
	 */
	public boolean enable(long id, User actor) throws PersistenceException {
		requireAdmin(actor);
		synchronized (ADMIN_CHANGES) {
			return inTransaction(() -> {
				User user = changeable(id, actor.getTenantId());
				if (user.isEnabled())
					return false;
				user.setEnabled(true);
				evictor.evictUsersOnRollback(List.of(id));
				UserDAO.get().store(user, history(actor, UserEvent.ENABLED));
				events.record(new AdminEvent(AdminArea.PEOPLE, "user.enabled", USER, id, PersonNames.recorded(user),
						null), actor);
				return true;
			});
		}
	}

	/**
	 * Reset Password: gives a password account a temporary password that has to change at the next sign-in. The store
	 * revokes the person's device tokens and lifts their username's sign-in block; once it commits, every open session
	 * they opened, as themselves or as another account, closes and the password is emailed, if asked. Call it outside
	 * a transaction.
	 *
	 * @param id a person
	 * @param email whether to email the temporary password
	 * @param actor who resets it, who may reset the {@code admin} account's but not their own
	 * @return the password unless it was emailed
	 * @throws PersistenceException a refusal: {@link #ONLY_ADMINS}, {@link #NO_PERSON}, {@link #SYSTEM_ACCOUNT},
	 *         {@link #RESET_PROVIDER} or {@link #RESET_YOURSELF}. Or an error at data layer
	 */
	public ResetResult resetPassword(long id, boolean email, User actor) throws PersistenceException {
		requireAdmin(actor);
		User target = changeable(id, actor.getTenantId());
		checkResettable(target, actor);
		String password = TemporaryPasswords.make(target.getTenantId());

		User user;
		synchronized (ADMIN_CHANGES) {
			user = inTransaction(() -> {
				User current = changeable(id, actor.getTenantId());
				checkResettable(current, actor);
				setPassword(current, password);
				evictor.evictUsersOnRollback(List.of(id));
				UserDAO.get().store(current, history(actor, UserEvent.PASSWORDCHANGED));
				return current;
			});
		}
		PersonSessions.closeAll(id, user.getUsername());

		NotEmailed notEmailed = email ? email(user, password) : NotEmailed.NOT_ASKED;
		events.recordAfter(new AdminEvent(AdminArea.PEOPLE, "user.password-reset", USER, id,
				PersonNames.recorded(user), Map.of("password", Change.secret(), "emailed",
						new Change(null, String.valueOf(notEmailed == null)))),
				actor);
		return notEmailed == null ? new ResetResult(null, true, null) : new ResetResult(password, false, notEmailed);
	}

	/**
	 * Sign Out Everywhere: closes every open session on this server that the person opened, as themselves or as another
	 * account, and revokes their device tokens. One's own is allowed, and closes one's own session too.
	 *
	 * @param id a person
	 * @param actor who signs them out
	 * @return how many sessions it closed, and how many of the devices their page showed it revoked
	 * @throws PersistenceException a refusal: {@link #ONLY_ADMINS}, {@link #NO_PERSON} or {@link #SYSTEM_ACCOUNT}. Or
	 *         an error at data layer
	 */
	public SignedOut signOut(long id, User actor) throws PersistenceException {
		requireAdmin(actor);
		User user = changeable(id, actor.getTenantId());
		// The devices their page shows; every token of theirs is revoked, expired and idle ones too
		int devices = ApiTokens.get().list(id).size();
		int sessions = PersonSessions.closeAll(id, user.getUsername());
		ApiTokens.get().revokeAll(id);
		if (sessions > 0 || devices > 0)
			events.recordAfter(new AdminEvent(AdminArea.PEOPLE, "user.signed-out", USER, id,
					PersonNames.recorded(user), Map.of("sessions", new Change(String.valueOf(sessions), "0"),
							"devices", new Change(String.valueOf(devices), "0"))),
					actor);
		return new SignedOut(sessions, devices);
	}

	/**
	 * Clear Sign-In Block: clears the sign-in throttle's count of the person's username, never the count of an
	 * address, which other accounts may share.
	 *
	 * @param id a person
	 * @param actor who clears it
	 * @return false if the username had no count, which changes nothing
	 * @throws PersistenceException a refusal: {@link #ONLY_ADMINS}, {@link #NO_PERSON} or {@link #SYSTEM_ACCOUNT}. Or
	 *         an error at data layer
	 */
	public boolean clearBlock(long id, User actor) throws PersistenceException {
		requireAdmin(actor);
		return inTransaction(() -> {
			User user = changeable(id, actor.getTenantId());
			Counter counter = reads.counters(PeopleReads.throttleAccount(user.getUsername()))
					.get(PeopleReads.throttleAccount(user.getUsername()));
			if (counter == null)
				return false;
			HealdanThrottle.passwordSet(user.getUsername());
			if (reads.counters(PeopleReads.throttleAccount(user.getUsername())).isEmpty()) {
				events.record(new AdminEvent(AdminArea.PEOPLE, "user.block-cleared", USER, id,
						PersonNames.recorded(user),
						Map.of("failures", new Change(String.valueOf(counter.value()), null))), actor);
				return true;
			}
			// The throttle is off, so its counts stay as they are
			return false;
		});
	}

	/**
	 * A person's groups: exactly these, besides their own group and, but for a read-only account, Everyone, which
	 * every person is in whether or not it's named. Call it outside a transaction.
	 *
	 * @param id a person
	 * @param groupIds the groups, each once
	 * @param actor who changes them
	 * @return false if they were in these groups already, which changes nothing
	 * @throws PersistenceException a refusal: {@link #ONLY_ADMINS}, {@link #YOUR_ACCOUNT_DISABLED}, {@link #NO_PERSON},
	 *         {@link #SYSTEM_ACCOUNT}, {@link #NO_GROUP}, {@link #READ_ONLY_GROUPS}, {@link #ADMIN_STAYS},
	 *         {@link #LEAVE_ADMIN_YOURSELF} or {@link #LAST_ADMIN}. Or an error at data layer
	 * @throws IllegalArgumentException for a group listed twice, or a null one
	 */
	public boolean setGroups(long id, List<Long> groupIds, User actor) throws PersistenceException {
		requireAdmin(actor);
		Set<Long> wanted = distinct(Objects.requireNonNull(groupIds, "groupIds"));
		return memberships(id, actor, (user, current) -> {
			Set<Long> target = new TreeSet<>(wanted);
			if (user.isReadonly())
				target.add(groupId(GUEST_GROUP, user.getTenantId()));
			else
				target.add(groupId(HealdanConstants.EVERYONE_GROUP, user.getTenantId()));
			return target;
		}, (user, before, after) -> new AdminEvent(AdminArea.PEOPLE, "user.groups-changed", USER, user.getId(),
				PersonNames.recorded(user), Map.of("groups", new Change(groupNames(before), groupNames(after)))));
	}

	/**
	 * Revoke on a person's page: revokes one of their device tokens and closes its sessions.
	 *
	 * @param id a person
	 * @param tokenId one of their tokens
	 * @param actor who revokes it
	 * @return false if it isn't one of their usable tokens ({@link ApiTokens#list}): another person's, revoked or
	 *         expired
	 * @throws PersistenceException a refusal: {@link #ONLY_ADMINS}, {@link #NO_PERSON} or {@link #SYSTEM_ACCOUNT}. Or
	 *         an error at data layer
	 */
	public boolean revokeDevice(long id, long tokenId, User actor) throws PersistenceException {
		requireAdmin(actor);
		User user = changeable(id, actor.getTenantId());
		ApiToken token = ApiTokens.get().list(id).stream().filter(t -> t.getId() == tokenId).findFirst()
				.orElse(null);
		if (token == null)
			return false;
		ApiTokens.get().revoke(token);
		events.recordAfter(new AdminEvent(AdminArea.PEOPLE, "user.device-revoked", USER, id,
				PersonNames.recorded(user), Map.of("device", new Change(token.getName(), null))), actor);
		return true;
	}

	// The guarded membership change, which GroupAdministration's members share

	/** What a person's groups become, from what they are */
	@FunctionalInterface
	interface Wanted {
		Set<Long> of(User user, Set<Long> current) throws PersistenceException;
	}

	/** The record of a change of a person's groups */
	@FunctionalInterface
	interface Recorded {
		AdminEvent of(User user, Set<Long> before, Set<Long> after) throws PersistenceException;
	}

	/**
	 * Changes a person's memberships of the listed groups (live groups of the tenant but people's own), under
	 * {@link #ADMIN_CHANGES}, in a transaction of its own: the caller's own account must be enabled still, the groups
	 * must be listed ones, a read-only account gets none but {@code guest}, and the admin group's guards hold. The
	 * groups a row doesn't list (their own, others' own groups, deleted ones) stay as they are.
	 *
	 * @return false if their groups were these already, which changes nothing
	 */
	boolean memberships(long id, User actor, Wanted wanted, Recorded recorded) throws PersistenceException {
		synchronized (ADMIN_CHANGES) {
			return inTransaction(() -> {
				requireEnabledAdmin(actor);
				User user = changeable(id, actor.getTenantId());
				long tenantId = user.getTenantId();
				Set<Long> current = listedGroups(id, tenantId);
				Set<Long> target = wanted.of(user, current);
				checkGroups(target, tenantId);
				// Nothing to change comes before the guards
				if (target.equals(current))
					return false;
				if (user.isReadonly()) {
					long guest = groupId(GUEST_GROUP, tenantId);
					if (target.stream().anyMatch(group -> group != guest))
						throw new HealdanRuleException(READ_ONLY_GROUPS);
				}
				long admins = groupId(ADMIN_GROUP, tenantId);
				boolean leavesAdmin = current.contains(admins) && !target.contains(admins);
				if (leavesAdmin) {
					if (ADMIN_ACCOUNT.equals(user.getUsername()))
						throw new HealdanRuleException(ADMIN_STAYS);
					if (user.getId() == actor.getId())
						throw new HealdanRuleException(LEAVE_ADMIN_YOURSELF);
				}
				boolean lowersAdmins = leavesAdmin && user.isEnabled();
				if (lowersAdmins && reads.enabledAdmins(tenantId, id) == 0)
					throw new HealdanRuleException(LAST_ADMIN);

				for (long group : current)
					if (!target.contains(group))
						user.removeGroup(group);
				for (long group : target)
					if (!current.contains(group))
						user.addGroup(GroupDAO.get().findById(group));
				evictor.evictUsersOnRollback(List.of(id));
				UserDAO.get().store(user, history(actor, UserEvent.UPDATED));
				checkAdminsLeft(lowersAdmins, tenantId);
				Set<Long> after = listedGroups(id, tenantId);
				events.record(recorded.of(user, current, after), actor);
				return true;
			});
		}
	}

	// Helpers

	/**
	 * @return the person, with their groups, read in the current transaction
	 * @throws HealdanRuleException {@link #NO_PERSON} for an account that isn't one of the tenant's, or is deleted;
	 *         {@link #SYSTEM_ACCOUNT} for a system account
	 */
	User changeable(long id, long tenantId) throws PersistenceException {
		User user = UserDAO.get().findById(id, true);
		if (user == null || user.getDeleted() != 0 || user.getTenantId() != tenantId)
			throw new HealdanRuleException(NO_PERSON);
		if (user.getType() == User.Type.SYSTEM)
			throw new HealdanRuleException(SYSTEM_ACCOUNT);
		return user;
	}

	/**
	 * @return the person's listed groups: live groups of the tenant but people's own
	 */
	Set<Long> listedGroups(long userId, long tenantId) {
		Set<Long> groups = new TreeSet<>();
		reads.groups(tenantId, userId).getOrDefault(userId, List.of()).forEach(group -> groups.add(group.id()));
		return groups;
	}

	/**
	 * @return the group's id, by its name, in the tenant
	 * @throws IllegalStateException if the tenant has no such group
	 */
	static long groupId(String name, long tenantId) throws PersistenceException {
		Group group = GroupDAO.get().findByName(name, tenantId);
		if (group == null)
			throw new IllegalStateException("The tenant has no " + name + " group");
		return group.getId();
	}

	/**
	 * @throws HealdanRuleException {@link #ONLY_ADMINS} for anyone but an administrator; a seed counts as one, as
	 *         for the other admin services
	 */
	static void requireAdmin(User actor) throws PersistenceException {
		Objects.requireNonNull(actor, "actor");
		// FilingRules reloads the user's groups, which are transient
		if (!FilingRules.get().isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
	}

	/**
	 * Inside {@link #ADMIN_CHANGES}: {@link #requireAdmin}, and the caller's own account, as stored now, must still be
	 * enabled, so an administrator disabled while their change waited its turn changes nothing
	 *
	 * @throws HealdanRuleException {@link #ONLY_ADMINS}, or {@link #YOUR_ACCOUNT_DISABLED} for a caller whose stored
	 *         account is disabled or deleted; a seed's caller too
	 */
	void requireEnabledAdmin(User actor) throws PersistenceException {
		requireAdmin(actor);
		if (!reads.enabled(actor.getId()))
			throw new HealdanRuleException(YOUR_ACCOUNT_DISABLED);
	}

	/**
	 * Runs the work in a transaction of its own, committed when it returns
	 *
	 * @throws IllegalStateException inside a caller's transaction, which would commit it later
	 */
	<T> T inTransaction(Work<T> work) throws PersistenceException {
		if (TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("A people change runs its own transaction: call it outside one");
		try {
			return transactions.execute(status -> {
				try {
					return work.run();
				} catch (PersistenceException e) {
					// A checked exception: wrapped, so that the template rolls back
					throw new Checked(e);
				}
			});
		} catch (Checked e) {
			throw e.wrapped;
		}
	}

	@FunctionalInterface
	interface Work<T> {
		T run() throws PersistenceException;
	}

	private static final class Checked extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final transient PersistenceException wrapped;

		Checked(PersistenceException wrapped) {
			super(wrapped);
			this.wrapped = wrapped;
		}
	}

	/**
	 * Inside a guarded change's transaction, after its write: counts the enabled administrators as the change leaves
	 * them, then runs {@link #whileLocked}
	 *
	 * @param lowersAdmins whether the change took an enabled administrator out
	 * @throws HealdanRuleException {@link #LAST_ADMIN} if it leaves none, which rolls it back
	 */
	void checkAdminsLeft(boolean lowersAdmins, long tenantId) throws HealdanRuleException {
		// What LogicalDOC's store wrote through Hibernate, for the count to read
		sessionFactory.getCurrentSession().flush();
		if (lowersAdmins && reads.enabledAdmins(tenantId, null) == 0)
			throw new HealdanRuleException(LAST_ADMIN);
		whileLocked.run();
	}

	/**
	 * @param userId the one person whose row to make, or null for every person's
	 * @param accounts the accounts read, that person's or every person's
	 * @return the rows of the Users list, unsorted
	 */
	private List<PersonRow> rows(long tenantId, Long userId, List<Account> accounts) {
		Map<Long, List<PersonRow.GroupRef>> groups = reads.groups(tenantId, userId);
		Map<Long, String> links = reads.links(userId);
		Map<String, Counter> counters = reads.counters(
				userId != null && accounts.size() == 1 ? PeopleReads.throttleAccount(accounts.get(0).username())
						: null);
		Map<Long, Integer> devices = reads.devices(userId);
		Map<String, SignInProvider> providers = links.isEmpty() ? Map.of() : PeopleReads.providers();
		Throttle throttle = Throttle.now();
		Instant now = Instant.now();
		long admins = adminGroupId(tenantId);

		List<PersonRow> rows = new ArrayList<>();
		for (Account account : accounts) {
			List<PersonRow.GroupRef> listed = groups.getOrDefault(account.id(), List.of());
			String provider = links.get(account.id());
			SignsInWith signsInWith = provider != null
					? new SignsInWith(SignsInWith.Kind.PROVIDER, PeopleReads.provider(provider, providers))
					: SignsInWith.PASSWORD;
			Instant blockedUntil = account.enabled()
					? throttle.blockedUntil(counters.get(PeopleReads.throttleAccount(account.username())), now)
					: null;
			PersonStatus status = !account.enabled() ? PersonStatus.DISABLED
					: blockedUntil != null ? PersonStatus.BLOCKED : PersonStatus.ACTIVE;
			rows.add(new PersonRow(account.id(), account.username(), account.name(), account.firstName(),
					account.lastName(), account.email(), listed, signsInWith, status, blockedUntil,
					account.signedIn() ? account.lastLogin() : null, devices.getOrDefault(account.id(), 0),
					account.readOnly(), listed.stream().anyMatch(group -> group.id() == admins)));
		}
		return rows;
	}

	/** The admin group's id in the tenant; -1 for none */
	private static long adminGroupId(long tenantId) {
		try {
			Group group = GroupDAO.get().findByName(ADMIN_GROUP, tenantId);
			return group != null ? group.getId() : -1L;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the admin group", e);
		}
	}

	private static Comparator<PersonRow> order(PeopleSort sort, boolean descending) {
		Comparator<PersonRow> byUsername = Comparator
				.comparing((PersonRow row) -> row.username().toLowerCase(Locale.ROOT)).thenComparingLong(PersonRow::id);
		Comparator<PersonRow> column = switch (sort) {
		case NAME -> directed(Comparator.comparing((PersonRow row) -> row.name().toLowerCase(Locale.ROOT)),
				descending);
		case USERNAME -> directed(Comparator.comparing((PersonRow row) -> row.username().toLowerCase(Locale.ROOT)),
				descending);
		case LAST_SIGN_IN -> Comparator.comparing(PersonRow::lastSignIn,
				Comparator.nullsLast(directed(Comparator.<Instant>naturalOrder(), descending)));
		};
		return column.thenComparing(byUsername);
	}

	private static <T> Comparator<T> directed(Comparator<T> comparator, boolean descending) {
		return descending ? comparator.reversed() : comparator;
	}

	/**
	 * @param provider whether it's a provider account ({@link #providerAccount})
	 * @return the refusals a person's page shows the reader
	 */
	private Person.Refusals refusals(PersonRow row, boolean provider, User reader) {
		boolean admin = ADMIN_ACCOUNT.equals(row.username());
		boolean self = row.id() == reader.getId();
		boolean lastAdmin = row.admin() && row.status() != PersonStatus.DISABLED
				&& reads.enabledAdmins(reader.getTenantId(), row.id()) == 0;
		// An action that would change nothing is refused nothing: a disabled account's Disable
		String disable = row.status() == PersonStatus.DISABLED ? null
				: admin ? DISABLE_ADMIN : self ? DISABLE_YOURSELF : lastAdmin ? LAST_ADMIN : null;
		String reset = provider ? RESET_PROVIDER : self ? RESET_YOURSELF : null;
		String leaveAdmin = !row.admin() ? null
				: admin ? ADMIN_STAYS : self ? LEAVE_ADMIN_YOURSELF : lastAdmin ? LAST_ADMIN : null;
		return new Person.Refusals(disable, null, reset, leaveAdmin);
	}

	/**
	 * Whether the account signs in through a sign-in provider, and so has no password here: Reset Password refuses it,
	 * and its page shows no password change. Unlinking makes it a password account again.
	 *
	 * @param source how the account was made
	 */
	static boolean providerAccount(UserSource source) {
		return source != UserSource.DEFAULT;
	}

	/** @throws HealdanRuleException for a provider account, or one's own */
	private static void checkResettable(User user, User actor) throws HealdanRuleException {
		if (providerAccount(user.getSource()))
			throw new HealdanRuleException(RESET_PROVIDER);
		if (user.getId() == actor.getId())
			throw new HealdanRuleException(RESET_YOURSELF);
	}

	/** Sets a temporary password, which has to change at the next sign-in, as LogicalDOC's admin password does */
	private static void setPassword(User user, String password) throws PersistenceException {
		try {
			user.setDecodedPassword(password);
		} catch (NoSuchAlgorithmException e) {
			throw new PersistenceException("Couldn't set a temporary password", e);
		}
		user.setPasswordChanged(new Date());
		user.setRepass("");
		user.setPasswordExpired(true);
	}

	/**
	 * Emails the temporary password, once its change has committed
	 *
	 * @return null if the mail server took it, else why it wasn't emailed
	 */
	private static NotEmailed email(User user, String password) {
		return switch (TemporaryPasswords.email(new TemporaryPasswords.Pending(user, password))) {
		case EMAILED -> null;
		case NOT_SET_UP -> NotEmailed.NOT_SET_UP;
		case NO_EMAIL -> NotEmailed.NO_EMAIL;
		case NOT_TAKEN -> NotEmailed.NOT_TAKEN;
		};
	}

	/** A change's history, naming the administrator who made it */
	private static UserHistory history(User actor, UserEvent event) {
		UserHistory history = new UserHistory();
		history.setEvent(event);
		history.setAuthor(actor.getUsername());
		history.setComment("By " + actor.getUsername() + " in the Admin app");
		return history;
	}

	/**
	 * @return the groups, in the order listed
	 * @throws IllegalArgumentException for a group listed twice, or a null one
	 */
	private static Set<Long> distinct(List<Long> ids) {
		Set<Long> groups = new LinkedHashSet<>();
		if (ids == null)
			return groups;
		for (Long id : ids) {
			if (id == null)
				throw new IllegalArgumentException("A group is null");
			if (!groups.add(id))
				throw new IllegalArgumentException("Group " + id + " is listed twice");
		}
		return groups;
	}

	/**
	 * @throws HealdanRuleException {@link #NO_GROUP} for a group that isn't a live group of the tenant, or is a
	 *         person's own
	 */
	private void checkGroups(Set<Long> groupIds, long tenantId) throws PersistenceException {
		if (groupIds.isEmpty())
			return;
		Set<Long> listed = reads.listedGroupIds(tenantId, groupIds);
		if (!listed.containsAll(groupIds))
			throw new HealdanRuleException(NO_GROUP);
	}

	/**
	 * Whether another account has the username, in any case, deleted and system accounts included, as LogicalDOC
	 * compares a new account's
	 */
	private boolean taken(String username) {
		return reads.usernameTaken(username);
	}

	/** The groups' names, by name ignoring case, for the record */
	private String groupNames(Set<Long> groupIds) {
		if (groupIds.isEmpty())
			return null;
		return reads.groupNames(groupIds).stream().sorted(String.CASE_INSENSITIVE_ORDER)
				.collect(Collectors.joining(", "));
	}

	/** Whether a commit failed on a unique index, SQLState 23505 */
	static boolean uniqueViolation(Throwable e) {
		for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause())
			if (t instanceof SQLException sql && "23505".equals(sql.getSQLState()))
				return true;
		return false;
	}

	private static void changed(Map<String, Change> changes, String field, String before, String after) {
		if (!Objects.equals(before, after))
			changes.put(field, new Change(before, after));
	}

	private static String blankless(String text) {
		return StringUtils.isBlank(text) ? null : text;
	}

	private static Instant instant(Date date) {
		return date != null ? date.toInstant() : null;
	}
}
