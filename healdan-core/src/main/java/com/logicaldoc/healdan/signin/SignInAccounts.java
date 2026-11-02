package com.logicaldoc.healdan.signin;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.apikey.ApiKeyDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.signin.GroupSync.Change;
import com.logicaldoc.healdan.util.BasicPlane;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.html.HTMLSanitizer;
import com.logicaldoc.util.spring.Context;

/**
 * Which account a provider sign-in is (M4 spec section 7.2), and what each
 * sign-in changes in it: the names and email (section 7.4) and the groups
 * (section 8). The provider has already checked the person, and
 * {@link ProviderClient} the ID token, so these are the claims of a real
 * person; the questions here are only which account is theirs and whether the
 * group map lets them in.
 * <p>
 * Each decision is one transaction, run under a lock for the provider and the
 * identity that's held until it commits, so two first sign-ins of one person
 * can't make two accounts. Two different identities that match one email, or
 * one username, aren't under one lock: the unique indexes are the backstop
 * ({@code hd_linkuser}, {@code hd_linkkey} and CE's on {@code ld_username}). An
 * attempt that fails one rolls back whole, so it makes and links nothing, and
 * the decision is made once more from what's committed, which then refuses it
 * ({@code email-taken} or {@code username-taken}). A second failure is thrown.
 * <p>
 * A refusal changes nothing, but for the gate's own rule: a linked account it
 * refuses is taken out of every mapped group, so nothing it held through the
 * map stays (spec section 7.2, step 2). And a link whose account was deleted is
 * closed first, as it records only that (controller's ruling R8(b)).
 * <p>
 * Making an account a provider account, by linking it or by finding a linked
 * one with another source or a password, deletes its LogicalDOC API keys, and
 * once the transaction commits closes the CE sessions they opened (owner,
 * 2026-11-01): a key signs in with no password, so it would skip the provider.
 * CE's {@code HealdanThrottle} refuses any key made for it afterwards.
 * <p>
 * {@link SignInAdmin}'s unlinking runs under the same locks
 * ({@link #lockedOnce}), so it can't land in the middle of a decision for the
 * person it unlinks.
 * <p>
 * Every link, new account and membership change is logged at info, after the
 * transaction commits, with the provider, the account's username and the
 * group's name; never a token or another claim's value (spec section 8).
 */
@Component
public class SignInAccounts {

	/** No mapped Healdan group: the gate refuses */
	public static final String NO_GROUP = "no-group";

	/** The email matches more than one account, or one that can't be linked */
	public static final String EMAIL_TAKEN = "email-taken";

	/** A new account's username is taken or can't be stored */
	public static final String USERNAME_TAKEN = "username-taken";

	/**
	 * The account the email matches is disabled or past its expiry date, so
	 * CE's sign-in would refuse it (ruling R16); also the code of CE's own
	 * refusals, which the sign-in pages give
	 */
	public static final String ACCOUNT = "account";

	/** What a new account's language is when {@code _system} has none: what CE's user editor starts on */
	static final String DEFAULT_LANGUAGE = "en";

	/** The width of {@code ld_user}'s username, names and email */
	static final int MAX_TEXT = 255;

	static final Runnable NOTHING = () -> {
	};

	/**
	 * Runs once every check has passed and just before an account is linked or
	 * made: a test's way to put another sign-in's change there. Production
	 * never sets it.
	 */
	static volatile Runnable beforeLinking = NOTHING;

	/** How many locks the identities share; two that share one just wait for each other */
	private static final int STRIPES = 64;

	private static final String ADMIN = "admin";

	private static final Logger log = LoggerFactory.getLogger(SignInAccounts.class);

	private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

	private final SignInProviders providers;

	private final SignInIdentityDAO identities;

	private final TransactionTemplate transactions;

	/**
	 * What a sign-in is: an account, or a refusal
	 */
	public sealed interface Decision permits Allowed, Refused {
	}

	/**
	 * The sign-in is this account
	 *
	 * @param user the account, as stored, with its groups
	 * @param linkedNow true if this sign-in linked it, by email or by making it
	 * @param madeNow true if this sign-in made it
	 */
	public record Allowed(User user, boolean linkedNow, boolean madeNow) implements Decision {
	}

	/**
	 * The sign-in is refused, and made and linked nothing
	 *
	 * @param code {@code no-group}, {@code email-taken},
	 *        {@code username-taken} or {@code account} (spec section 5.3;
	 *        ruling R16)
	 */
	public record Refused(String code) implements Decision {
	}

	@Autowired
	public SignInAccounts(SignInProviders providers, SignInIdentityDAO identities,
			PlatformTransactionManager transactionManager) {
		this.providers = providers;
		this.identities = identities;
		this.transactions = new TransactionTemplate(transactionManager);
		for (int i = 0; i < STRIPES; i++)
			locks[i] = new ReentrantLock();
	}

	/**
	 * @return the bean in the application context
	 */
	public static SignInAccounts get() {
		return Context.get(SignInAccounts.class);
	}

	/**
	 * Decides which account a provider sign-in is, in spec section 7.2's order:
	 * an identity already linked, the gate, link by email, and otherwise a new
	 * account. It runs its own transaction, so call it outside one: the lock
	 * that keeps one person to one account is held until that transaction
	 * commits.
	 *
	 * @param p the provider the person signed in through
	 * @param claims what its ID token says, every check passed
	 * @return the account, or the refusal
	 * @throws PersistenceException error at data layer
	 * @throws IllegalArgumentException if the claims have no identity
	 * @throws IllegalStateException if it's called inside a transaction
	 */
	public Decision decide(SignInProvider p, IdClaims claims) throws PersistenceException {
		checkIdentity(p, claims);
		return locked(p.id(), claims.identity(), journal -> decideNow(p, claims, journal));
	}

	/**
	 * Applies a re-check's claims to the account the identity is linked to (spec
	 * section 9): the names and email (never the username) and the groups, the
	 * gate included. Like {@link #decide}, it runs its own transaction.
	 *
	 * @param user the account the device token signs in as
	 * @param p the provider
	 * @param claims what the provider's new ID token says
	 * @return false when the gate refuses, after taking the account out of every
	 *         mapped group; false too, changing nothing, when the identity isn't
	 *         linked to this account, or the account no longer exists. True
	 *         otherwise.
	 * @throws PersistenceException error at data layer
	 * @throws IllegalArgumentException if the claims have no identity
	 * @throws IllegalStateException if it's called inside a transaction
	 */
	public boolean refresh(User user, SignInProvider p, IdClaims claims) throws PersistenceException {
		checkIdentity(p, claims);
		Objects.requireNonNull(user, "user");
		long userId = user.getId();
		return locked(p.id(), claims.identity(), journal -> refreshNow(userId, p, claims, journal));
	}

	private Decision decideNow(SignInProvider p, IdClaims claims, Journal journal) throws PersistenceException {
		Optional<SignInIdentity> link = identities.findLinked(p.id(), claims.identity());
		User linked = null;
		if (link.isPresent()) {
			linked = UserDAO.get().findById(link.get().getUserId(), true);
			if (linked == null) {
				// CE's delete tells no listener and renames the account, so its link stays in use (R8(b))
				close(link.get(), journal);
				link = Optional.empty();
			}
		}

		List<GroupMapping> map = providers.groups(p.id());
		Set<Long> granted = claims.groupsOverage() ? Set.of() : GroupSync.mapped(map, claims.groups());

		// 1. An identity already linked is its account
		if (link.isPresent())
			return signInLinked(linked, link.get(), p, claims, map, granted, journal);

		// 2. The gate
		if (granted.isEmpty()) {
			refusedAtGate(p, claims, printable(claims.username()), journal);
			return new Refused(NO_GROUP);
		}

		// 3. Link by email
		String email = email(claims);
		if (p.linkByEmail() && email != null) {
			List<User> matches = UserDAO.get().findByWhere("lower(_entity.email) = :email",
					Map.of("email", email.toLowerCase(Locale.ROOT)), null, null);
			if (matches.size() > 1) {
				journal.info("Refused a first sign-in through the provider {} for {}: its email matches {} accounts",
						p.id(), printable(claims.username()), matches.size());
				return new Refused(EMAIL_TAKEN);
			}
			if (matches.size() == 1) {
				User match = matches.get(0);
				String why = unlinkable(match);
				if (why != null) {
					journal.info("Refused a first sign-in through the provider {} for {}: its email matches the "
							+ "account {}, which {}", p.id(), printable(claims.username()),
							printable(match.getUsername()), why);
					return new Refused(EMAIL_TAKEN);
				}
				// CE's chain would refuse it once linked, so it isn't linked: nothing changes (ruling R16). What can
				// pass, such as working hours or an address's block, is left to the chain after the link.
				String state = refusedState(match);
				if (state != null) {
					journal.info("Refused a first sign-in through the provider {} for {}: its email matches the "
							+ "account {}, which {}", p.id(), printable(claims.username()),
							printable(match.getUsername()), state);
					return new Refused(ACCOUNT);
				}
				return linkByEmail(match.getId(), p, claims, map, granted, journal);
			}
		}

		// 4. Otherwise, a new account
		return makeAccount(p, claims, map, granted, journal);
	}

	private Decision signInLinked(User user, SignInIdentity link, SignInProvider p, IdClaims claims,
			List<GroupMapping> map, Set<Long> granted, Journal journal) throws PersistenceException {
		if (granted.isEmpty()) {
			// Nothing it held through the map stays
			List<Change> changes = GroupSync.changes(user, map, Set.of());
			// A provider account stored with impersonators is refused (R14), so any that got in go with the groups
			boolean dropped = user.getSource() == UserSource.OIDC && dropImpersonators(user);
			if (!changes.isEmpty() || dropped)
				UserDAO.get().store(user, history("Taken out of the groups of the sign-in provider " + p.id()));
			journal.changes(p, user, changes);
			refusedAtGate(p, claims, printable(user.getUsername()), journal);
			return new Refused(NO_GROUP);
		}

		// Reads first, then the account's changes, then its store: no flush may write them before CE's listeners see them
		List<Change> changes = GroupSync.changes(user, map, granted);
		boolean converting = !isProviderAccount(user);
		boolean wasProvider = !converting && user.getImpersonators().isEmpty();
		boolean changed = followProvider(user, claims);
		if (!wasProvider)
			journal.warn("The account {}, linked to the sign-in provider {}, had a password, impersonators or another "
					+ "source; it's a provider account again", printable(user.getUsername()), p.id());
		if (changed || !changes.isEmpty())
			UserDAO.get().store(user, history("Updated from the sign-in provider " + p.id()));
		// It wasn't a provider account, so its keys may have signed it in
		if (converting)
			deleteApiKeys(user, signsInThrough(p), journal);
		journal.changes(p, user, changes);

		link.setLastSignIn(new Date());
		identities.store(link);
		return new Allowed(UserDAO.get().findById(user.getId(), true), false, false);
	}

	private Decision linkByEmail(long userId, SignInProvider p, IdClaims claims, List<GroupMapping> map,
			Set<Long> granted, Journal journal) throws PersistenceException {
		User user = UserDAO.get().findById(userId, true);
		List<Change> changes = GroupSync.changes(user, map, granted);

		beforeLinking.run();
		// The link first: a second link for the account breaks hd_linkuser here, before the account changes
		identities.store(newLink(p, claims, userId, SignInIdentity.HOW_EMAIL));

		// A provider account from now on: the provider's names and email, no password (spec section 7.3) and no
		// impersonators (R14). Removing the password is a change TokenUserListener sees, so the account's device
		// tokens are revoked.
		boolean hadImpersonators = !user.getImpersonators().isEmpty();
		followProvider(user, claims);
		UserDAO.get().store(user, history("Linked to the sign-in provider " + p.id()));

		journal.info("Linked the account {} to the sign-in provider {}, by its email", printable(user.getUsername()),
				p.id());
		deleteApiKeys(user, signsInThrough(p), journal);
		if (hadImpersonators)
			journal.info("The account {} no longer lets other accounts impersonate it, as it's a provider account",
					printable(user.getUsername()));
		journal.changes(p, user, changes);
		return new Allowed(UserDAO.get().findById(userId, true), true, false);
	}

	private Decision makeAccount(SignInProvider p, IdClaims claims, List<GroupMapping> map, Set<Long> granted,
			Journal journal) throws PersistenceException {
		String username = claims.username();
		if (!storable(username) || ADMIN.equalsIgnoreCase(username) || taken(username)) {
			journal.info("Refused a first sign-in through the provider {} for {}: that username is taken, or can't be "
					+ "an account's", p.id(), printable(username));
			return new Refused(USERNAME_TAKEN);
		}

		Names names = names(claims);
		String email = email(claims);
		User user = new User();
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setUsername(username);
		user.setFirstName(StringUtils.defaultString(names.first()));
		user.setName(StringUtils.defaultString(names.last()));
		user.setEmail(StringUtils.defaultString(email));
		user.setSource(UserSource.OIDC);
		user.setPassword(null);
		user.setType(User.Type.DEFAULT);
		user.setEnabled(true);
		user.setLanguage(defaultLanguage());
		// Everyone comes from EveryoneUserListener, and the own group from CE
		List<Change> changes = GroupSync.changes(user, map, granted);

		beforeLinking.run();
		UserDAO.get().store(user);
		identities.store(newLink(p, claims, user.getId(), SignInIdentity.HOW_NEW));

		journal.info("Made the account {} at its first sign-in through the provider {}", printable(username), p.id());
		journal.changes(p, user, changes);
		return new Allowed(UserDAO.get().findById(user.getId(), true), true, true);
	}

	private boolean refreshNow(long userId, SignInProvider p, IdClaims claims, Journal journal)
			throws PersistenceException {
		Optional<SignInIdentity> link = identities.findLinked(p.id(), claims.identity());
		if (link.isEmpty() || link.get().getUserId() != userId) {
			journal.info("A re-check through the sign-in provider {} named an identity that isn't the account {}'s",
					p.id(), userId);
			return false;
		}
		User user = UserDAO.get().findById(userId, true);
		if (user == null)
			return false;

		List<GroupMapping> map = providers.groups(p.id());
		Set<Long> granted = claims.groupsOverage() ? Set.of() : GroupSync.mapped(map, claims.groups());
		if (granted.isEmpty()) {
			List<Change> changes = GroupSync.changes(user, map, Set.of());
			// A provider account stored with impersonators is refused (R14), so any that got in go with the groups
			boolean dropped = user.getSource() == UserSource.OIDC && dropImpersonators(user);
			if (!changes.isEmpty() || dropped)
				UserDAO.get().store(user, history("Taken out of the groups of the sign-in provider " + p.id()));
			journal.changes(p, user, changes);
			refusedAtGate(p, claims, printable(user.getUsername()), journal);
			return false;
		}

		List<Change> changes = GroupSync.changes(user, map, granted);
		boolean converting = !isProviderAccount(user);
		boolean changed = followProvider(user, claims);
		if (changed || !changes.isEmpty())
			UserDAO.get().store(user, history("Updated from the sign-in provider " + p.id()));
		if (converting)
			deleteApiKeys(user, signsInThrough(p), journal);
		journal.changes(p, user, changes);
		return true;
	}

	private static String signsInThrough(SignInProvider p) {
		return "as it signs in through the sign-in provider " + p.id();
	}

	/**
	 * Deletes the account's LogicalDOC API keys, the way CE's own user delete
	 * does: when it's a provider account from now on (owner, 2026-11-01), and
	 * when it was unlinked, as a key made while it was linked never signed in
	 * and would start to (ruling R17). Every row goes, the ones deleted in the
	 * web page included, which CE keeps marked deleted; the line logged counts
	 * the others, the ones its API Keys screen listed. Never a key:
	 * {@code ld_apikey} holds only their SHA-256.
	 * <p>
	 * Once the transaction commits, the open CE sessions the keys opened are
	 * closed too, as CE's {@code X-API-KEY} lookup finds a session by its key's
	 * hash ({@code SessionManager.getSessionFromApiKey}) and would go on
	 * finding them after the keys went.
	 *
	 * @param user the account
	 * @param why why they go, for the line logged, such as {@code as it signs
	 *        in through the sign-in provider entra}
	 * @param journal the transaction's journal
	 */
	static void deleteApiKeys(User user, String why, Journal journal) throws PersistenceException {
		ApiKeyDAO keys = ApiKeyDAO.get();
		Map<String, Object> account = Map.of("userId", user.getId());
		List<String> hashes = keys.queryForList("select ld_key from ld_apikey where ld_userid = :userId", account,
				String.class, null);
		if (hashes.isEmpty())
			return;
		int listed = keys.queryForInt("select count(*) from ld_apikey where ld_userid = :userId and ld_deleted = 0",
				account);
		keys.jdbcUpdate("delete from ld_apikey where ld_userid = :userId", account);

		String username = printable(user.getUsername());
		if (listed > 0)
			journal.info("Deleted the {} LogicalDOC API {} of the account {}, {}", listed, listed == 1 ? "key" : "keys",
					username, why);
		Set<String> deleted = Set.copyOf(hashes);
		journal.afterCommit(() -> closeKeySessions(deleted, username));
	}

	/**
	 * Closes this node's open CE sessions that one of the keys opened. CE's
	 * pool keeps each session's key as it was given, so a session is matched
	 * by the part before an impersonation's {@code >}, as CE's chain reads it:
	 * {@code <key>>bob} opens a session as {@code bob} with the key's owner as
	 * the impersonator. Only the sessions in this node's pool can be found
	 * from a key. Each session is checked and closed on its own, so one that
	 * fails doesn't keep the others open. It never throws: a failure here is
	 * logged, and the decision, already committed, stands.
	 */
	private static void closeKeySessions(Set<String> hashes, String username) {
		int closed = 0;
		int failed = 0;
		try {
			SessionManager sessions = SessionManager.get();
			// newSession holds this lock while it signs in, so a key sign-in that read its key before the commit has its
			// session in the pool by now, and one that starts later finds the key gone. Healdan never decides a sign-in
			// inside newSession, so the identity's lock, held here, is never taken the other way round.
			synchronized (sessions) {
				for (Session session : sessions.getSessions()) {
					try {
						if (session.isOpen() && openedWith(session, hashes)) {
							sessions.kill(session.getSid());
							closed++;
						}
					} catch (RuntimeException e) {
						failed++;
						log.debug("A session couldn't be checked or closed", e);
					}
				}
			}
		} catch (RuntimeException e) {
			failed++;
			log.debug("The sessions couldn't be read", e);
		}
		if (closed > 0)
			log.info("Closed {} {} opened with the deleted API keys of the account {}", closed,
					closed == 1 ? "session" : "sessions", username);
		if (failed > 0)
			log.warn("Couldn't check or close {} of the sessions that may have been opened with the deleted API keys "
					+ "of the account {}; an administrator can kill them in the Sessions list", failed, username);
	}

	/**
	 * Whether one of the keys opened the session. A session opened with a
	 * password has no key, neither as given nor hashed, so it's never one.
	 */
	private static boolean openedWith(Session session, Set<String> hashes) {
		String key = session.getDecodedKey();
		if (key == null) {
			// The set refuses to look a null up
			String hash = session.getKey();
			return hash != null && hashes.contains(hash);
		}
		if (key.contains(">"))
			key = StringUtils.substringBefore(key, ">").trim();
		try {
			return hashes.contains(Encrypter.encryptSHA256(key));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Marks a link whose account is gone unlinked now, by nobody */
	private void close(SignInIdentity link, Journal journal) throws PersistenceException {
		link.setUnlinked(new Date());
		link.setUnlinkedBy(null);
		identities.store(link);
		journal.info("Closed a link to the sign-in provider {}: its account, id {}, was deleted", link.getProvider(),
				link.getUserId());
	}

	private static void refusedAtGate(SignInProvider p, IdClaims claims, String who, Journal journal) {
		if (claims.groupsOverage())
			journal.info("Refused a sign-in through the provider {} for {}: its ID token left out the groups, as the "
					+ "person has too many. Set the app registration to send the groups assigned to the application.",
					p.id(), who);
		else
			journal.info("Refused a sign-in through the provider {} for {}: none of its groups is mapped to a Healdan "
					+ "group", p.id(), who);
	}

	/**
	 * @return why the account can't be linked by email, or null if it can
	 */
	private String unlinkable(User user) throws PersistenceException {
		if (user.getId() == User.USERID_ADMIN || ADMIN.equalsIgnoreCase(user.getUsername()))
			return "is admin";
		if (user.getType() == User.Type.SYSTEM || user.getId() < 0)
			return "is a system account";
		if (identities.findLinkedByUser(user.getId()).isPresent())
			return "is already linked";
		return null;
	}

	/**
	 * @return why CE's sign-in would refuse the account whatever the time or
	 *         the address, as {@code DefaultAuthenticator.validateUser} reads
	 *         it, or null if it wouldn't
	 */
	private static String refusedState(User user) {
		if (!user.isEnabled())
			return "is disabled";
		if (user.isExpired())
			return "has expired";
		return null;
	}

	/**
	 * Makes the account a provider account that follows the claims: its names
	 * and email where they carry them, source OIDC, no password and no
	 * impersonators. The username never changes.
	 *
	 * @return true if anything changed
	 */
	private static boolean followProvider(User user, IdClaims claims) {
		boolean changed = false;
		Names names = names(claims);
		if (names.first() != null && !names.first().equals(user.getFirstName())) {
			user.setFirstName(names.first());
			changed = true;
		}
		if (names.last() != null && !names.last().equals(user.getName())) {
			user.setName(names.last());
			changed = true;
		}
		String email = email(claims);
		if (email != null && !email.equals(user.getEmail())) {
			user.setEmail(email);
			changed = true;
		}
		if (!isProviderAccount(user)) {
			user.setSource(UserSource.OIDC);
			// Not setDecodedPassword: the stored password goes, and nothing can sign in with it
			user.setPassword(null);
			changed = true;
		}
		if (dropImpersonators(user))
			changed = true;
		return changed;
	}

	/**
	 * Empties the account's impersonators, which CE writes back in place of the
	 * stored ones. A provider account has none (ruling R14): CE's
	 * {@code bob>jane} would sign {@code bob} in as it with {@code bob}'s
	 * password.
	 * 
	 * @return true if it had any
	 */
	private static boolean dropImpersonators(User user) {
		if (user.getImpersonators().isEmpty())
			return false;
		user.getImpersonators().clear();
		return true;
	}

	/** OIDC with no password; an empty one is none, as no password's hash is empty */
	private static boolean isProviderAccount(User user) {
		return user.getSource() == UserSource.OIDC && StringUtils.isEmpty(user.getPassword());
	}

	private static SignInIdentity newLink(SignInProvider p, IdClaims claims, long userId, String how) {
		Date now = new Date();
		SignInIdentity row = new SignInIdentity();
		row.setTenantId(Tenant.DEFAULT_ID);
		row.setProvider(p.id());
		row.setIdentity(claims.identity());
		row.setUserId(userId);
		row.setLinked(now);
		row.setHow(how);
		row.setLastSignIn(now);
		return row;
	}

	private static UserHistory history(String comment) {
		UserHistory history = new UserHistory();
		history.setEvent(UserEvent.UPDATED);
		history.setComment(comment);
		return history;
	}

	/**
	 * @return the server's default language. CE keeps no setting for it: its
	 *         setup wizard writes the language it's given to every account
	 *         there is, {@code _system} included, which no screen or SOAP call
	 *         can edit. So it's {@code _system}'s, or else {@code en}, which
	 *         CE's user editor starts a new account on.
	 */
	private static String defaultLanguage() throws PersistenceException {
		User system = UserDAO.get().findById(User.USERID_SYSTEM);
		String language = system == null ? null : StringUtils.strip(system.getLanguage());
		return StringUtils.isEmpty(language) ? DEFAULT_LANGUAGE : language;
	}

	/**
	 * Whether an account has the username, ignoring case as CE's store does. A
	 * deleted account counts: CE renames it {@code <username>.<id>}, but keeps
	 * its row under the unique index on the username.
	 */
	private static boolean taken(String username) throws PersistenceException {
		return UserDAO.get().queryForInt("select count(*) from ld_user where lower(ld_username) = :username",
				Map.of("username", username.toLowerCase(Locale.ROOT))) > 0;
	}

	/**
	 * Whether CE can store the username as a new account's: not blank, with no
	 * space around it, no {@code >} (CE's impersonation separator, which its
	 * store refuses), no control character, nothing MariaDB's {@code utf8}
	 * tables can't hold, and no longer than the column
	 */
	static boolean storable(String username) {
		return StringUtils.isNotBlank(username) && username.equals(username.strip()) && username.length() <= MAX_TEXT
				&& !username.contains(">") && BasicPlane.holds(username)
				&& username.chars().noneMatch(Character::isISOControl);
	}

	/**
	 * The parts of the name the claims carry: {@code given_name} and
	 * {@code family_name}, and for whichever is missing, {@code name} split at
	 * its last space (the words before it are the first name, the word after
	 * it the last name; one word is the first name, with an empty last name).
	 * Null for a part they don't carry.
	 */
	static Names names(IdClaims claims) {
		String first = text(claims.givenName());
		String last = text(claims.familyName());
		String full = text(claims.name());
		if (full != null && (first == null || last == null)) {
			int space = full.lastIndexOf(' ');
			if (first == null)
				first = space < 0 ? full : full.substring(0, space).strip();
			if (last == null)
				last = space < 0 ? "" : full.substring(space + 1).strip();
		}
		return new Names(first, last);
	}

	/**
	 * @param first the first name, null if the claims don't carry it
	 * @param last the last name, null if the claims don't carry it
	 */
	record Names(String first, String last) {
	}

	/**
	 * @return a name claim as CE stores it (sanitised, its whitespace one
	 *         space, characters beyond the Basic Multilingual Plane replaced, no
	 *         longer than the column); null if it's missing or blank
	 */
	private static String text(String claim) {
		if (StringUtils.isBlank(claim))
			return null;
		String clean = HTMLSanitizer.sanitizeSimpleText(claim.strip().replaceAll("\\s+", " "));
		clean = StringUtils.strip(BasicPlane.replaceOthers(clean));
		if (StringUtils.isEmpty(clean))
			return null;
		return StringUtils.strip(StringUtils.left(clean, MAX_TEXT));
	}

	/**
	 * @return the email claim, stripped; null if it's missing, or isn't
	 *         something an account's email could be (no {@code @}, a space or
	 *         control character, a character the database can't hold, or longer
	 *         than the column), which then neither links nor is stored
	 */
	static String email(IdClaims claims) {
		String email = StringUtils.strip(claims.email());
		if (StringUtils.isEmpty(email) || email.length() > MAX_TEXT || !email.contains("@") || !BasicPlane.holds(email)
				|| email.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
			return null;
		return email;
	}

	/**
	 * A username for a log line: its control characters replaced, so it can't
	 * forge a line, and at most 100 characters
	 */
	static String printable(String text) {
		if (text == null)
			return "(no username)";
		StringBuilder clean = new StringBuilder(text.length());
		text.codePoints().forEach(c -> clean.appendCodePoint(Character.isISOControl(c) ? '?' : c));
		return StringUtils.abbreviate(clean.toString(), 100);
	}

	private static void checkIdentity(SignInProvider p, IdClaims claims) {
		if (p == null || claims == null || StringUtils.isBlank(claims.identity()))
			throw new IllegalArgumentException("A provider sign-in needs the provider and the person's identity at it");
	}

	/**
	 * Runs work in one transaction under the identity's lock, as decisions run,
	 * so that it can't interleave with a sign-in or a re-check of that person:
	 * unlinking (spec section 7.7). Unlike a decision it's tried once, as its
	 * refusals are the caller's to give.
	 *
	 * @param provider the provider's id
	 * @param identity the identity at the provider
	 * @param work the work, which logs through the journal
	 * @return what the work returns, once its transaction committed and its
	 *         journal was written
	 * @throws PersistenceException what the work throws, or an error at data
	 *         layer
	 * @throws IllegalStateException if it's called inside a transaction
	 */
	<T> T lockedOnce(String provider, String identity, Work<T> work) throws PersistenceException {
		if (TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("Unlinking runs its own transaction: call it outside one");
		ReentrantLock lock = locks[Math.floorMod(Objects.hash(provider, identity), STRIPES)];
		lock.lock();
		try {
			return once(work);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Runs the work in one transaction, under the identity's lock, held until it
	 * commits. A failed attempt rolled back whole, so it's made once more from
	 * what's committed (see the class comment); a second failure is thrown.
	 */
	private <T> T locked(String provider, String identity, Work<T> work) throws PersistenceException {
		// Inside a caller's transaction the lock would go before the commit, and a failed attempt would doom it
		if (TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("A sign-in decision runs its own transaction: call it outside one");
		ReentrantLock lock = locks[Math.floorMod(Objects.hash(provider, identity), STRIPES)];
		lock.lock();
		try {
			try {
				return once(work);
			} catch (RuntimeException | PersistenceException e) {
				// The class only: a database's message can quote the row, and with it the identity
				log.warn("A sign-in through the provider {} failed ({}) and is decided again", provider,
						e.getClass().getSimpleName());
				log.debug("The sign-in's failure", e);
				return once(work);
			}
		} finally {
			lock.unlock();
		}
	}

	private <T> T once(Work<T> work) throws PersistenceException {
		Journal journal = new Journal();
		T result;
		try {
			result = transactions.execute(status -> {
				try {
					return work.run(journal);
				} catch (PersistenceException e) {
					// A checked exception: wrapped, so that the template rolls back
					throw new Checked(e);
				}
			});
		} catch (Checked e) {
			throw e.wrapped;
		}
		// Only what committed is logged
		journal.write();
		return result;
	}

	@FunctionalInterface
	interface Work<T> {
		T run(Journal journal) throws PersistenceException;
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
	 * The log lines of one attempt, written once it commits, and what it does
	 * after the commit
	 */
	static final class Journal {

		private final List<Runnable> lines = new ArrayList<>();

		void info(String format, Object... arguments) {
			lines.add(() -> log.info(format, arguments));
		}

		void warn(String format, Object... arguments) {
			lines.add(() -> log.warn(format, arguments));
		}

		/** Something to do once the attempt commits, in its place among the lines, which must not throw */
		void afterCommit(Runnable action) {
			lines.add(action);
		}

		void changes(SignInProvider p, User user, List<Change> changes) {
			for (Change change : changes)
				info("Sign-in provider {}: {} {} the group {}", p.id(), printable(user.getUsername()),
						change.added() ? "added to" : "removed from", change.group());
		}

		void write() {
			lines.forEach(Runnable::run);
		}
	}
}
