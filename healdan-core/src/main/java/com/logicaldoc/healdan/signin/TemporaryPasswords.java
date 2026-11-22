// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.signin;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.communication.Recipient;
import com.logicaldoc.core.security.TenantDAO;
import com.logicaldoc.core.security.authentication.PasswordWeakException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.util.security.PasswordGenerator;
import com.logicaldoc.util.spring.Context;

/**
 * Temporary passwords: made under the tenant's password rules, and emailed to the person in LogicalDOC's credentials
 * email. Unlinking an account from its sign-in provider gives it one ({@link SignInAdmin}), and so do the Admin app's
 * New User and Reset Password. The person changes it at their first sign-in.
 * <p>
 * A password is never logged, and never in an exception's message: only the account's username is.
 */
public final class TemporaryPasswords {

	/** LogicalDOC's credentials email, which its admin Change Password sends */
	static final String CREDENTIALS_TEMPLATE = "psw.rec1";

	/** How long {@link #email(List)} waits for the mail server to take the emails, in all */
	public static final long EMAIL_WAIT_MILLIS = 15_000L;

	/** LogicalDOC's pool for emails ({@code EMailSender.sendAsync}) */
	private static final String EMAIL_POOL = "Email";

	private static final Logger log = LoggerFactory.getLogger(TemporaryPasswords.class);

	/** LogicalDOC's generator of a password for a tenant, by its name, under the tenant's password settings */
	static final Function<String, String> CE_GENERATOR = PasswordGenerator::generate;

	/** How temporary passwords are made; {@code SignInTestHooks} puts it back to {@link #CE_GENERATOR} */
	static volatile Function<String, String> generator = CE_GENERATOR;

	/** What became of a temporary password's email */
	public enum Outcome {

		/** The mail server took it */
		EMAILED,

		/**
		 * Outgoing email isn't set up, or LogicalDOC doesn't send now ({@link OutgoingEmail#isSetUp}), so it wasn't
		 * written
		 */
		NOT_SET_UP,

		/** The account has no email address */
		NO_EMAIL,

		/**
		 * It couldn't be written or started, or the mail server didn't take it within {@link #EMAIL_WAIT_MILLIS}; it
		 * may still go, with the same password
		 */
		NOT_TAKEN
	}

	/**
	 * An account and its new temporary password, until it's emailed
	 *
	 * @param user the account, with its email address and language
	 * @param password the temporary password
	 */
	public record Pending(User user, String password) {

		public Pending {
			Objects.requireNonNull(user, "user");
			Objects.requireNonNull(password, "password");
		}

		@Override
		public String toString() {
			// Never the password
			return "Pending[" + SignInAccounts.printable(user.getUsername()) + "]";
		}
	}

	private TemporaryPasswords() {
	}

	/**
	 * Makes a temporary password the tenant's password rules accept. LogicalDOC's generator reads
	 * {@code password.sequence} as 4 when it isn't set, where the rules read it as 3, so it can make a password with a
	 * sequence of 4, such as {@code abcd}, which the rules refuse: about one in 10,000. Storing it would roll the
	 * change back, so it gets a second try.
	 * <p>
	 * Call it before the change's transaction: LogicalDOC's check refuses with a runtime exception, which would mark
	 * that transaction for rollback. It checks a stand-in account of the tenant, as LogicalDOC checks a ticket's
	 * password ({@code DocumentManager.createTicket}).
	 *
	 * @param tenantId the account's tenant
	 * @return the password
	 * @throws PersistenceException if neither try passes the rules; it never says the password or what the rules
	 *         found in it
	 */
	public static String make(long tenantId) throws PersistenceException {
		String tenant = TenantDAO.get().getTenantName(tenantId);
		User standIn = new User();
		standIn.setId(-1);
		standIn.setUsername("dummy");
		standIn.setTenantId(tenantId);
		standIn.setLocale(Locale.ENGLISH);
		for (int tries = 1;; tries++) {
			String password = generator.apply(tenant);
			try {
				standIn.setDecodedPassword(password);
				UserDAO.get().checkPasswordCompliance(standIn);
				return password;
			} catch (NoSuchAlgorithmException e) {
				throw new PersistenceException("Couldn't make a temporary password", e);
			} catch (PasswordWeakException e) {
				// Its message quotes what the rules found, part of the password
				if (tries == 2)
					throw new PersistenceException(
							"Couldn't make a temporary password that the password rules accept, in two tries");
			}
		}
	}

	/**
	 * Emails one temporary password, as {@link #email(List)} does.
	 *
	 * @param pending the account and its password
	 * @return what became of the email
	 */
	public static Outcome email(Pending pending) {
		return email(List.of(pending)).get(0);
	}

	/**
	 * Emails each temporary password, when outgoing email is set up, and waits for the mail server to take them,
	 * {@link #EMAIL_WAIT_MILLIS} in all. Call it once the change that set the passwords has committed. Each goes in
	 * LogicalDOC's credentials email, the {@code psw.rec1} template, as its {@code SecurityServiceImpl.notifyAccount}
	 * builds it: the server's address, the account and the password, in the account's language.
	 *
	 * @param pending the accounts and their passwords
	 * @return what became of each email, in the order given
	 */
	public static List<Outcome> email(List<Pending> pending) {
		boolean setUp = OutgoingEmail.isSetUp();
		List<Outcome> outcomes = new ArrayList<>();
		List<Future<Object>> sending = new ArrayList<>();
		for (Pending one : pending) {
			Future<Object> future = null;
			Outcome outcome = Outcome.NOT_SET_UP;
			if (setUp && StringUtils.isBlank(one.user().getEmail())) {
				log.info("The account {} has no email, so its temporary password can't be emailed",
						SignInAccounts.printable(one.user().getUsername()));
				outcome = Outcome.NO_EMAIL;
			} else if (setUp) {
				future = send(one);
				outcome = future == null ? Outcome.NOT_TAKEN : null;
			}
			outcomes.add(outcome);
			sending.add(future);
		}

		long deadline = System.currentTimeMillis() + EMAIL_WAIT_MILLIS;
		for (int i = 0; i < pending.size(); i++) {
			Future<Object> future = sending.get(i);
			if (future == null)
				continue;
			String username = SignInAccounts.printable(pending.get(i).user().getUsername());
			if (taken(future, deadline, username)) {
				log.info("Emailed the temporary password of the account {}", username);
				outcomes.set(i, Outcome.EMAILED);
			} else {
				outcomes.set(i, Outcome.NOT_TAKEN);
			}
		}
		return outcomes;
	}

	/**
	 * Starts the credentials email to the account.
	 *
	 * @return the email's sending, or null if it can't be sent
	 */
	private static Future<Object> send(Pending pending) {
		User user = pending.user();
		String username = SignInAccounts.printable(user.getUsername());

		EMail email = new EMail();
		email.setTenantId(user.getTenantId());
		email.setHtml(true);
		Recipient recipient = new Recipient();
		recipient.setAddress(user.getEmail());
		recipient.setRead(1);
		email.addRecipient(recipient);
		email.setFolder("outbox");
		email.setUsername(user.getUsername());
		email.setSentDate(new Date());
		email.setLocale(user.getLocale());
		// Never filed as a document, wherever the admin keeps a copy of each email sent
		email.setHistoricyze(false);

		Map<String, Object> dictionary = new HashMap<>();
		dictionary.put("url", Context.get().getConfig().getProperty("server.url"));
		dictionary.put("user", user);
		// Escaped: the email is HTML, and a password may hold an &
		dictionary.put("password", StringEscapeUtils.escapeHtml(pending.password()));
		try {
			OutgoingEmail.fill(email, CREDENTIALS_TEMPLATE, dictionary);
		} catch (Exception e) {
			// The class only: a template's error can quote what it was given
			log.warn("Couldn't write the email of the temporary password of the account {} ({})", username,
					e.getClass().getSimpleName());
			return null;
		}

		Future<Object> future;
		try {
			future = ThreadPools.get().execute(() -> {
				OutgoingEmail.send(email);
				return null;
			}, EMAIL_POOL);
		} catch (RuntimeException e) {
			// Caught, so the passwords of the accounts emailed with this one are kept all the same
			log.warn("Couldn't email the temporary password of the account {}: the email pool didn't take it ({})",
					username, e.getClass().getSimpleName());
			return null;
		}
		if (future == null)
			log.warn("Couldn't email the temporary password of the account {}: the email pool has stopped", username);
		return future;
	}

	/** Whether the mail server took the email by the deadline */
	private static boolean taken(Future<Object> future, long deadline, String username) {
		try {
			future.get(Math.max(0L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
			return true;
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			log.warn("Couldn't email the temporary password of the account {}: {}", username, cause.toString());
		} catch (TimeoutException e) {
			// It may still go, with the same password
			log.warn("The mail server didn't take the email of the temporary password of the account {} in time",
					username);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return false;
	}
}
