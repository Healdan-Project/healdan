package com.logicaldoc.healdan.seed;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.communication.MessageTemplate;
import com.logicaldoc.core.communication.MessageTemplateDAO;
import com.logicaldoc.core.security.Tenant;

/**
 * Adds the alert CE's sign-in throttle sends when it starts a block. CE looks
 * for a message template called {@code bfa.alert} but ships none, so its
 * alerts never went out. The template is in English, which CE falls back to
 * for every language, and its words come from CE's resource bundles. A
 * {@code bfa.alert} that already exists is kept.
 */
@Component
public class ThrottleAlertSeed implements SeedStep {

	static final String TEMPLATE = "bfa.alert";

	private static final String SUBJECT = "$product - $I18N.get('bfadetected')";

	// LoginThrottle passes one of the three, already HTML-escaped
	private static final String BODY = """
			$I18N.format('bfaalert', $product)<br/><br/>
			#if($suspectedUsername)$I18N.get('username'): <b>$suspectedUsername</b><br/>#end
			#if($suspectedIp)$I18N.get('ipaddress'): <b>$suspectedIp</b><br/>#end
			#if($suspectedApiKey)$I18N.get('apikey'): <b>$suspectedApiKey</b><br/>#end
			$I18N.get('dateandtime'): $DateTool.formatDate($date)
			""";

	@Override
	public String name() {
		return "throttle-alert";
	}

	@Override
	public void run() throws Exception {
		MessageTemplateDAO dao = MessageTemplateDAO.get();
		if (!dao.findByName(TEMPLATE, Tenant.DEFAULT_ID).isEmpty())
			return;
		MessageTemplate template = new MessageTemplate();
		template.setTenantId(Tenant.DEFAULT_ID);
		template.setName(TEMPLATE);
		template.setLanguage("en");
		template.setType(MessageTemplate.Type.SYSTEM);
		template.setDescription("Sent to throttle.alert.recipients when the sign-in throttle starts a block");
		template.setSubject(SUBJECT);
		template.setBody(BODY);
		dao.store(template);
	}
}
