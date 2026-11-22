// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.signin;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.signin.SignInAccounts.Journal;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.util.spring.Context;

/**
 * The accounts linked to a sign-in provider, and unlinking them (M4 spec
 * section 7.7), for the Sign-In tab.
 * <p>
 * A link is a record beside the CE account, so unlinking loses nothing: the
 * user id stays, and with it the documents, check-outs, history, My Matters,
 * favourites, grants and groups. The account goes back to a password account,
 * as CE's admin Change Password leaves one
 * ({@code SecurityServiceImpl.changePassword}): source {@code DEFAULT}, a
 * random temporary password marked expired, so the person changes it at their
 * next sign-in. Its device
 * tokens are revoked (plan correction 2), its LogicalDOC API keys deleted and
 * the sessions they opened closed (ruling R17), and the link is kept, marked
 * unlinked, with when and by whom. Signing in through the provider again links
 * it again by the usual rule (section 7.2).
 * <p>
 * Each account is unlinked in one transaction, under the lock its sign-ins
 * take. Once it commits, the temporary password is emailed to the person when
 * outgoing email is set up, in CE's credentials email (the {@code psw.rec1}
 * template, built as {@code SecurityServiceImpl.notifyAccount} builds it;
 * {@link TemporaryPasswords} makes and emails it). When it can't be, the
 * password is returned, for the admin to pass on. The password is never logged.
 */
@Component
public class SignInAdmin {

	private static final Logger log = LoggerFactory.getLogger(SignInAdmin.class);

	private final SignInAccounts accounts;

	private final SignInIdentityDAO identities;

	/**
	 * An account linked to a provider, as the Sign-In tab lists it
	 *
	 * @param userId the account's id
	 * @param username its username
	 * @param name its full name
	 * @param how how it was linked: {@code email} or {@code new}
	 * @param linked when it was linked
	 * @param lastSignIn when it last signed in through the provider
	 */
	public record Linked(long userId, String username, String name, String how, Date linked, Date lastSignIn) {
	}

	/**
	 * What Unlink Everyone did
	 *
	 * @param count how many accounts were unlinked
	 * @param passwords the temporary passwords that weren't emailed, by
	 *        username, for the admin to pass on
	 */
	public record Unlinked(int count, Map<String, String> passwords) {

		@Override
		public String toString() {
			// Never the passwords
			return "Unlinked[count=%d, notEmailed=%d]".formatted(count, passwords.size());
		}
	}

	/** An account just unlinked, and its temporary password, until it's emailed */
	private record Temporary(User user, String provider, String password) {

		@Override
		public String toString() {
			return "Temporary[" + user.getUsername() + "]";
		}

		TemporaryPasswords.Pending pending() {
			return new TemporaryPasswords.Pending(user, password);
		}
	}

	@Autowired
	public SignInAdmin(SignInAccounts accounts, SignInIdentityDAO identities) {
		this.accounts = accounts;
		this.identities = identities;
	}

	/**
	 * @return the bean in the application context
	 */
	public static SignInAdmin get() {
		return Context.get(SignInAdmin.class);
	}

	/**
	 * @param provider a provider's id
	 * @return the accounts linked to it, by username, leaving out deleted ones
	 * @throws PersistenceException error at data layer
	 */
	public List<Linked> linked(String provider) throws PersistenceException {
		List<Linked> linked = new ArrayList<>();
		if (provider == null)
			return linked;
		for (SignInIdentity link : identities.findLinked(provider)) {
			User user = UserDAO.get().findById(link.getUserId());
			if (user != null)
				linked.add(new Linked(user.getId(), user.getUsername(), user.getFullName(), link.getHow(),
						link.getLinked(), link.getLastSignIn()));
		}
		return linked;
	}

	/**
	 * Unlinks one account (spec section 7.7). Call it outside a transaction:
	 * the unlinking commits before the email goes.
	 *
	 * @param userId the account's id
	 * @param by the admin who unlinks it
	 * @return the temporary password when it wasn't emailed: outgoing email
	 *         isn't set up or LogicalDOC doesn't send
	 *         ({@link OutgoingEmail#isSetUp}), the account has no email, the
	 *         email couldn't be started, or the mail server didn't take it
	 *         within {@link TemporaryPasswords#EMAIL_WAIT_MILLIS}; else null
	 * @throws PersistenceException error at data layer
	 * @throws HealdanRuleException if the account isn't linked, or doesn't exist
	 */
	public String unlink(long userId, User by) throws PersistenceException {
		Temporary temporary = unlinkOne(userId, by);
		return deliver(List.of(temporary)).get(temporary.user().getUsername());
	}

	/**
	 * Unlinks every account linked to a provider, each as {@link #unlink} does,
	 * so the provider can then be removed. An account that can't be unlinked is
	 * logged and left linked, and the others are unlinked all the same, so that
	 * their passwords aren't lost.
	 *
	 * @param provider the provider's id
	 * @param by the admin who unlinks them
	 * @return how many were unlinked, and the temporary passwords that weren't
	 *         emailed, by username
	 * @throws PersistenceException error at data layer, listing the accounts
	 */
	public Unlinked unlinkEveryone(String provider, User by) throws PersistenceException {
		Objects.requireNonNull(by, "by");
		List<Temporary> unlinked = new ArrayList<>();
		if (provider != null)
			for (SignInIdentity link : identities.findLinked(provider)) {
				try {
					unlinked.add(unlinkOne(link.getUserId(), by));
				} catch (HealdanRuleException e) {
					// Unlinked meanwhile, or its account deleted
					log.info("Skipped account {} while unlinking everyone from the sign-in provider {}: {}",
							link.getUserId(), provider, e.getMessage());
				} catch (RuntimeException | PersistenceException e) {
					log.warn("Couldn't unlink account {} from the sign-in provider {}; it stays linked",
							link.getUserId(), provider, e);
				}
			}
		Map<String, String> passwords = deliver(unlinked);
		log.info("Unlinked {} {} from the sign-in provider {}, by {}", unlinked.size(),
				unlinked.size() == 1 ? "account" : "accounts", provider, SignInAccounts.printable(by.getUsername()));
		return new Unlinked(unlinked.size(), passwords);
	}

	private Temporary unlinkOne(long userId, User by) throws PersistenceException {
		Objects.requireNonNull(by, "by");
		SignInIdentity link = identities.findLinkedByUser(userId).orElseThrow(SignInAdmin::notLinked);
		long linkId = link.getId();
		User account = UserDAO.get().findById(userId);
		if (account == null)
			throw noAccount();
		String password = TemporaryPasswords.make(account.getTenantId());
		return accounts.lockedOnce(link.getProvider(), link.getIdentity(),
				journal -> unlinkNow(userId, linkId, by, password, journal));
	}

	/** In the transaction, under the identity's lock */
	private Temporary unlinkNow(long userId, long linkId, User by, String password, Journal journal)
			throws PersistenceException {
		// Read again under the lock: a sign-in or another admin may have got there first
		SignInIdentity link = identities.findLinkedByUser(userId).filter(row -> row.getId() == linkId)
				.orElseThrow(SignInAdmin::notLinked);
		User user = UserDAO.get().findById(userId, true);
		if (user == null)
			throw noAccount();
		String provider = link.getProvider();

		// As CE's admin Change Password leaves an account, in one store: CE refuses a DEFAULT account with no password
		user.setSource(UserSource.DEFAULT);
		try {
			user.setDecodedPassword(password);
		} catch (NoSuchAlgorithmException e) {
			throw new PersistenceException("Couldn't make a temporary password", e);
		}
		user.setPasswordChanged(new Date());
		user.setRepass("");
		user.setPasswordExpired(true);
		UserHistory history = new UserHistory();
		history.setEvent(UserEvent.PASSWORDCHANGED);
		history.setAuthor(by.getUsername());
		history.setComment("Unlinked from the sign-in provider %s by %s".formatted(provider, by.getUsername()));
		// Only this store, and only this account: the listener lets it move off OIDC while it runs
		storeUnlinking(user, history);

		link.setUnlinked(new Date());
		link.setUnlinkedBy(by.getId());
		identities.store(link);

		// Going from no password to one isn't a change TokenUserListener sees (plan correction 2)
		int revoked = ApiTokens.get().revokeAll(userId);
		SignInAccounts.deleteApiKeys(user, "as it was unlinked from the sign-in provider " + provider, journal);

		String username = SignInAccounts.printable(user.getUsername());
		journal.info("Unlinked the account {} from the sign-in provider {}, by {}", username, provider,
				SignInAccounts.printable(by.getUsername()));
		if (revoked > 0)
			journal.info("Revoked the {} device {} of the account {}, as it was unlinked", revoked,
					revoked == 1 ? "token" : "tokens", username);
		return new Temporary(user, provider, password);
	}

	private static void storeUnlinking(User user, UserHistory history) throws PersistenceException {
		try {
			ProviderPasswordListener.unlinking(user.getId(), () -> {
				UserDAO.get().store(user, history);
				return null;
			});
		} catch (PersistenceException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new PersistenceException("Couldn't store the account " + user.getId(), e);
		}
	}

	private static HealdanRuleException notLinked() {
		return new HealdanRuleException("That account isn't linked to a sign-in provider.");
	}

	private static HealdanRuleException noAccount() {
		return new HealdanRuleException("That account doesn't exist.");
	}

	/**
	 * Emails each temporary password ({@link TemporaryPasswords#email(List)}), when outgoing email is set up, and
	 * waits for the mail server to take them.
	 *
	 * @return the passwords that weren't emailed, by username
	 */
	private static Map<String, String> deliver(List<Temporary> temporaries) {
		List<TemporaryPasswords.Outcome> outcomes = TemporaryPasswords
				.email(temporaries.stream().map(Temporary::pending).toList());
		Map<String, String> notEmailed = new LinkedHashMap<>();
		for (int i = 0; i < temporaries.size(); i++)
			if (outcomes.get(i) != TemporaryPasswords.Outcome.EMAILED)
				notEmailed.put(temporaries.get(i).user().getUsername(), temporaries.get(i).password());
		for (String username : notEmailed.keySet())
			log.info("The temporary password of the account {} wasn't emailed; it's shown to the administrator who "
					+ "unlinked it, to pass on", SignInAccounts.printable(username));
		return notEmailed;
	}
}
