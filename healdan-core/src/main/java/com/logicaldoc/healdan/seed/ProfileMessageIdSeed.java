// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.HealdanConstants;

/**
 * Adds the Message-ID attribute to the Healdan profile, at position 14 after
 * Received, so an email's Message-ID is a property like its From and Subject
 * (M5 spec §6.1). Properties an admin added after Received move down one, as
 * {@link ProfileCommentsSeed} moves them. A profile an admin deleted stays
 * deleted, and one that already has {@code emailmessageid} is left alone.
 */
@Component
public class ProfileMessageIdSeed implements SeedStep {

	/** Where Message-ID goes: after Received */
	static final int POSITION = 14;

	@Override
	public String name() {
		return "profile-message-id";
	}

	@Override
	public void run() throws Exception {
		Template template = TemplateDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
		if (template == null || template.getAttribute(HealdanConstants.ATTR_EMAILMESSAGEID) != null)
			return;

		// An admin's own properties after Received make room, as for Comments
		for (Attribute attribute : template.getTemplateAttributes().values())
			if (attribute.getPosition() >= POSITION)
				attribute.setPosition(attribute.getPosition() + 1);

		Attribute messageId = new Attribute();
		messageId.setLabel("Message-ID");
		messageId.setType(Attribute.Type.STRING);
		messageId.setPosition(POSITION);
		messageId.setEditor(Attribute.EDITOR_DEFAULT);
		template.getTemplateAttributes().put(HealdanConstants.ATTR_EMAILMESSAGEID, messageId);
		TemplateDAO.get().store(template);
	}
}
