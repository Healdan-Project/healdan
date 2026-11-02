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
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.threading.ThreadPools;

/**
 * Emails the chosen recipients when {@code admin}, the break-glass account,
 * signs in with its password (M4 spec section 7.5).
 * <p>
 * It hangs on CE's {@link SessionListener} (plan correction 4), which
 * {@code HealdanStartup} registers once the seeds ran, and acts on a new
 * session of user id 1 that has no key and no impersonator: a password
 * sign-in by {@code admin}, not a device token, an API key or another
 * account impersonating it. It sends only while the alert is on, a provider
 * is on (with none, {@code admin}'s password is an ordinary sign-in) and
 * outgoing email is set up (plan correction 3). It's email only, with no
 * system message instead (owner).
 * <p>
 * Each recipient gets one email from the {@code signin.adminalert} template,
 * which the {@code signin-settings} seed adds, sent on CE's thread pools as
 * CE's throttle alert is ({@code LoginThrottle.notifyBruteForceAttack}), so
 * the sign-in never waits for the mail server. A recipient that isn't a user,
 * or has no email, is skipped with a warning.
 */
@Component
public class AdminSignInAlert implements SessionListener {

	/** The message template, which {@code SignInSettingsSeed} adds */
	static final String TEMPLATE = "signin.adminalert";

	/** The pool the emails are sent from */
	static final String POOL = "AdminSignInAlert";

	/** What the email says when the session has no address */
	static final String UNKNOWN_ADDRESS = "unknown";

	/** As CE's throttle alert waits */
	private static final long DELAY_MILLIS = 500L;

	private static final Logger log = LoggerFactory.getLogger(AdminSignInAlert.class);

	@Override
	public void onSessionCreated(Session session) {
		// SessionManager holds its lock while it calls this, so it only decides, and schedules
		try {
			if (!adminPasswordSignIn(session))
				return;
			SignInProviders providers = SignInProviders.get();
			if (!providers.adminAlert() || providers.enabled().isEmpty() || !OutgoingEmail.isSetUp())
				return;

			String address = session.getClient() == null ? null : session.getClient().getAddress();
			List<String> recipients = providers.adminAlertRecipients();
			Date date = new Date();
			log.info("admin signed in with its password; alerting {} {}", recipients.size(),
					recipients.size() == 1 ? "recipient" : "recipients");
			ThreadPools.get().schedule(() -> {
				alert(recipients, address, date);
				return null;
			}, POOL, DELAY_MILLIS);
		} catch (RuntimeException e) {
			// The sign-in stands whatever happens here
			log.warn("Couldn't alert anyone that admin signed in with its password", e);
		}
	}

	@Override
	public void onSessionClosed(Object sid) {
		// Nothing to do
	}

	/**
	 * @return true for a session of {@code admin} opened with its own password:
	 *         no key (a device token or an API key) and no impersonator
	 */
	static boolean adminPasswordSignIn(Session session) {
		return session != null && session.getUserId() == User.USERID_ADMIN && session.getKey() == null
				&& session.getDecodedKey() == null && StringUtils.isEmpty(session.getImpersonator());
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
