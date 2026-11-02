package com.logicaldoc.healdan.seed;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.communication.MessageTemplate;
import com.logicaldoc.core.communication.MessageTemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * Writes the defaults of provider sign-in's settings (M4, spec section 10.1),
 * leaving alone any that already has a value, and adds the message template
 * the {@code admin} alert sends (spec section 7.5), in English, which CE falls
 * back to for every language. A {@code signin.adminalert} that already exists
 * is kept.
 * <p>
 * The list of providers starts empty, which is a value like any other: a
 * server with no provider gets today's sign-in window.
 */
@Component
public class SignInSettingsSeed implements SeedStep {

	static final String TEMPLATE = "signin.adminalert";

	private static final String SUBJECT = "$product - admin signed in with its password";

	private static final String BODY = "The emergency account <b>admin</b> signed in to $product with its password."
			+ "<br/><br/>$I18N.get('ipaddress'): <b>$ip</b><br/>"
			+ "$I18N.get('dateandtime'): $DateTool.formatDate($date)";

	private final HealdanSettings settings;

	@Autowired
	public SignInSettingsSeed(HealdanSettings settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return "signin-settings";
	}

	@Override
	public void run() throws Exception {
		settings.setIfMissing("signin.providers", "");
		settings.setIfMissing("signin.password.label", "Sign In with a Password");
		settings.setIfMissing("signin.adminAlert", "false");
		settings.setIfMissing("signin.adminAlert.recipients", "admin");
		settings.setIfMissing("signin.recheck.minutes", "480");

		MessageTemplateDAO dao = MessageTemplateDAO.get();
		if (!dao.findByName(TEMPLATE, Tenant.DEFAULT_ID).isEmpty())
			return;
		MessageTemplate template = new MessageTemplate();
		template.setTenantId(Tenant.DEFAULT_ID);
		template.setName(TEMPLATE);
		template.setLanguage("en");
		template.setType(MessageTemplate.Type.SYSTEM);
		template.setDescription("Sent to signin.adminAlert.recipients when admin signs in with its password");
		template.setSubject(SUBJECT);
		template.setBody(BODY);
		dao.store(template);
	}
}
