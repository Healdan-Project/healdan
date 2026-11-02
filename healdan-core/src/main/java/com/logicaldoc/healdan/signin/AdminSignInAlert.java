package com.logicaldoc.healdan.signin;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.communication.Recipient;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionListener;
import com.logicaldoc.core.security.apikey.ApiKey;
import com.logicaldoc.core.security.apikey.ApiKeyDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.token.HealdanTokenAuthenticator;

/**
 * Emails the chosen recipients when {@code admin}, the break-glass account,
 * signs in with its password (M4 spec section 7.5).
 * <p>
 * It hangs on CE's {@link SessionListener} (plan correction 4), which
 * {@code HealdanStartup} registers once the seeds ran, and acts on every
 * session opened with {@code admin}'s password, and on no other (ruling R22):
 * <ul>
 * <li>{@code admin}'s own session, whatever key the request also carried: CE's
 * web sign-in takes a key from any request ({@code j_secretkey} or
 * {@code key}) and keeps it on a session its password opened;</li>
 * <li>a session {@code admin} impersonates another account in
 * ({@code admin>bob}), which its password opened too;</li>
 * <li>not a session a key really opened: one of {@code admin}'s own LogicalDOC
 * API keys, or the one-time key a device token or a provider sign-in opens its
 * session with on this thread. A key that merely starts as Healdan's do
 * counts for nothing, as a request can carry any;</li>
 * <li>not another account's password impersonating {@code admin}
 * ({@code bob>admin}).</li>
 * </ul>
 * It sends only while the alert is on, a provider is on (with none,
 * {@code admin}'s password is an ordinary sign-in) and outgoing email is set up
 * (plan correction 3). It's email only, with no system message instead
 * (owner).
 * <p>
 * {@code SessionManager} holds its lock while it calls this, so here only what
 * the session and the thread hold is read; the rest, the settings included, is
 * read on CE's thread pools, as CE's throttle alert is sent
 * ({@code LoginThrottle.notifyBruteForceAttack}). Each recipient gets one
 * email from the {@code signin.adminalert} template, which the
 * {@code signin-settings} seed adds. A recipient that isn't a user, or has no
 * email, is skipped with a warning.
 */
@Component
public class AdminSignInAlert implements SessionListener {

	/** The message template, which {@code SignInSettingsSeed} adds */
	static final String TEMPLATE = "signin.adminalert";

	/** The pool the alert is decided and sent on */
	static final String POOL = "AdminSignInAlert";

	/** What the email says when the session has no address */
	static final String UNKNOWN_ADDRESS = "unknown";

	/** CE's impersonation separator, in a username or a key */
	private static final String IMPERSONATION = ">";

	/** As CE's throttle alert waits */
	private static final long DELAY_MILLIS = 500L;

	private static final Logger log = LoggerFactory.getLogger(AdminSignInAlert.class);

	/**
	 * A new session that a password may have opened, as it was when it opened
	 *
	 * @param impersonator who impersonates the session's account, or null
	 * @param key the key the chain was given, before any {@code >}, or null;
	 *        never logged
	 * @param address the client's address, or null
	 * @param date when it opened
	 */
	private record Candidate(String impersonator, String key, String address, Date date) {

		@Override
		public String toString() {
			// Never the key
			return "Candidate[impersonator=%s, address=%s]".formatted(impersonator, address);
		}
	}

	@Override
	public void onSessionCreated(Session session) {
		try {
			Candidate candidate = candidate(session);
			if (candidate != null)
				ThreadPools.get().schedule(() -> {
					alertIfAdminsPassword(candidate);
					return null;
				}, POOL, DELAY_MILLIS);
		} catch (RuntimeException e) {
			// The sign-in stands whatever happens here
			log.warn("Couldn't check whether admin signed in with its password", e);
		}
	}

	@Override
	public void onSessionClosed(Object sid) {
		// Nothing to do
	}

	/**
	 * Decides, from what the session and this thread hold, whether a password
	 * may have opened the session for {@code admin}: it's {@code admin}'s with no
	 * impersonator, or someone impersonates its account; and it isn't one a
	 * device token or a provider sign-in is opening on this thread.
	 *
	 * @return what the rest of the decision needs, or null if it can't be
	 *         {@code admin}'s password
	 */
	private static Candidate candidate(Session session) {
		if (session == null)
			return null;
		String impersonator = StringUtils.trimToNull(session.getImpersonator());
		if (impersonator == null && session.getUserId() != User.USERID_ADMIN)
			return null;
		String key = StringUtils.trimToNull(session.getDecodedKey());
		if (HealdanTokenAuthenticator.opening(key) || SignIns.opening(key))
			return null;
		if (key != null && key.contains(IMPERSONATION))
			key = StringUtils.trimToNull(StringUtils.substringBefore(key, IMPERSONATION));
		String address = session.getClient() == null ? null : session.getClient().getAddress();
		return new Candidate(impersonator, key, address, new Date());
	}

	/** On the pool: the rest of the decision, then the emails */
	private static void alertIfAdminsPassword(Candidate candidate) {
		try {
			if (!adminsPassword(candidate))
				return;
			SignInProviders providers = SignInProviders.get();
			if (!providers.adminAlert() || providers.enabled().isEmpty() || !OutgoingEmail.isSetUp())
				return;
			List<String> recipients = providers.adminAlertRecipients();
			log.info("admin signed in with its password; alerting {} {}", recipients.size(),
					recipients.size() == 1 ? "recipient" : "recipients");
			alert(recipients, candidate.address(), candidate.date());
		} catch (Exception e) {
			log.warn("Couldn't alert anyone that admin signed in with its password", e);
		}
	}

	/**
	 * @return true if {@code admin}'s password opened the session: the password
	 *         is the impersonator's when there is one, and no key of
	 *         {@code admin}'s own opened it
	 */
	private static boolean adminsPassword(Candidate candidate) throws Exception {
		if (candidate.impersonator() != null) {
			User by = UserDAO.get().findByUsername(candidate.impersonator());
			if (by == null || by.getId() != User.USERID_ADMIN)
				return false;
		}
		if (candidate.key() == null)
			return true;
		// Only live rows: CE's lookup leaves the deleted ones out
		ApiKey key = ApiKeyDAO.get().findByKey(candidate.key());
		return key == null || key.getUserId() != User.USERID_ADMIN;
	}

	private static void alert(List<String> recipients, String address, Date date) {
		// The email is HTML, and the address is whatever reached the server
		String ip = StringEscapeUtils.escapeHtml(StringUtils.defaultIfBlank(address, UNKNOWN_ADDRESS));
		for (String username : recipients) {
			try {
				User user = UserDAO.get().findByUsername(username);
				if (user == null) {
					log.warn("The admin sign-in alert's recipient {} isn't a user, so it was skipped",
							SignInAccounts.printable(username));
					continue;
				}
				if (StringUtils.isBlank(user.getEmail())) {
					log.warn("The admin sign-in alert's recipient {} has no email, so it was skipped",
							SignInAccounts.printable(username));
					continue;
				}

				EMail email = new EMail();
				email.setTenantId(user.getTenantId());
				email.setHtml(true);
				email.setLocale(user.getLocale());
				email.setAuthor("SYSTEM");
				Recipient recipient = new Recipient();
				recipient.setAddress(user.getEmail());
				recipient.setName(user.getFullName());
				recipient.setMode(Recipient.Mode.TO);
				email.getRecipients().add(recipient);

				Map<String, Object> dictionary = new HashMap<>();
				dictionary.put("ip", ip);
				dictionary.put("date", date);
				OutgoingEmail.fill(email, TEMPLATE, dictionary);
				OutgoingEmail.send(email);
			} catch (Exception e) {
				log.warn("Couldn't send the admin sign-in alert to {}", SignInAccounts.printable(username), e);
			}
		}
	}
}
