package com.logicaldoc.healdan.signin;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.automation.Automation;
import com.logicaldoc.core.automation.AutomationException;
import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.communication.EMailSender;
import com.logicaldoc.core.communication.MessageTemplate;
import com.logicaldoc.core.communication.MessageTemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Whether Healdan can send email, and how provider sign-in's emails go out:
 * the {@code admin} alert (M4 spec section 7.5) and the temporary password of
 * an account that was unlinked (section 7.7).
 * <p>
 * Outgoing email is set up (plan correction 3) when the default tenant's mail
 * server isn't blank or this machine ({@code localhost}, {@code 127.0.0.1},
 * {@code ::1}), and its sender isn't blank or CE's shipped
 * {@code logicaldoc@acme.com}. CE ships {@code localhost} and that sender, and
 * a stack nobody set up keeps them.
 */
public final class OutgoingEmail {

	/** The sender CE ships */
	static final String SHIPPED_SENDER = "logicaldoc@acme.com";

	static final String HOST = "default.smtp.host";

	static final String SENDER = "default.smtp.sender";

	/** Names of this machine, which a container or a stack nobody set up has no mail server on */
	private static final Set<String> THIS_MACHINE = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

	/**
	 * Hands an email to the mail server
	 */
	@FunctionalInterface
	interface Sender {

		/**
		 * @param email the email, its subject and text filled in
		 * @throws Exception if the mail server didn't take it
		 */
		void send(EMail email) throws Exception;
	}

	/**
	 * CE's way, as its notify of a new password sends it
	 * ({@code SecurityServiceImpl.notifyAccount}): a sender made for the
	 * email's tenant, which reads the mail server's settings as they are now
	 */
	static final Sender CE = email -> new EMailSender(email.getTenantId()).send(email);

	/** How emails go out; {@code SignInTestHooks} puts it back to {@link #CE} after a test replaced it */
	static volatile Sender sender = CE;

	private OutgoingEmail() {
	}

	/**
	 * @return true if outgoing email is set up (plan correction 3)
	 */
	public static boolean isSetUp() {
		ContextProperties config = Context.get().getConfig();
		return isSetUp(config.getProperty(HOST), config.getProperty(SENDER));
	}

	/**
	 * @param host the mail server's address
	 * @param from the address emails come from
	 * @return true if the server is one other than this machine, and the sender
	 *         is one an admin set
	 */
	static boolean isSetUp(String host, String from) {
		String server = StringUtils.strip(host);
		String address = StringUtils.strip(from);
		return StringUtils.isNotEmpty(server) && !THIS_MACHINE.contains(server.toLowerCase(Locale.ROOT))
				&& StringUtils.isNotEmpty(address) && !SHIPPED_SENDER.equalsIgnoreCase(address);
	}

	/**
	 * Fills an email's subject and text from a message template, in the
	 * email's language or else English, as CE's
	 * {@code EMailSender.send(email, template, dictionary)} does.
	 *
	 * @param email the email, its locale and tenant set
	 * @param templateName the template's name
	 * @param dictionary what the template is given; the locale is added
	 * @throws PersistenceException if the template can't be read
	 * @throws AutomationException if the template can't be formatted
	 * @throws IllegalStateException if there is no such template
	 */
	static void fill(EMail email, String templateName, Map<String, Object> dictionary)
			throws PersistenceException, AutomationException {
		Locale locale = email.getLocale() == null ? Locale.ENGLISH : email.getLocale();
		MessageTemplate template = MessageTemplateDAO.get().findByNameAndLanguage(templateName, locale.toString(),
				Tenant.DEFAULT_ID);
		if (template == null)
			throw new IllegalStateException("There is no message template " + templateName);
		dictionary.put(Automation.LOCALE, locale);
		email.setSubject(template.getFormattedSubject(dictionary));
		email.setMessageText(template.getFormattedBody(dictionary));
	}

	/**
	 * Hands an email to the mail server, on the calling thread
	 *
	 * @param email the email, its subject and text filled in
	 * @throws Exception if the mail server didn't take it
	 */
	static void send(EMail email) throws Exception {
		sender.send(email);
	}
}
