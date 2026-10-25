package com.logicaldoc.healdan.seed;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.HealdanConstants;

/**
 * Adds the Comments attribute to the Healdan profile, at position 6 after
 * Subclass, moving the attributes from there down by one. A profile an admin
 * deleted stays deleted, and one that already has {@code comments} is left
 * alone.
 */
@Component
public class ProfileCommentsSeed implements SeedStep {

	/** Where Comments goes: after Subclass */
	static final int POSITION = 6;

	@Override
	public String name() {
		return "profile-comments";
	}

	@Override
	public void run() throws Exception {
		Template template = TemplateDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
		if (template == null || template.getAttribute(HealdanConstants.ATTR_COMMENTS) != null)
			return;

		for (Attribute attribute : template.getTemplateAttributes().values())
			if (attribute.getPosition() >= POSITION)
				attribute.setPosition(attribute.getPosition() + 1);

		Attribute comments = new Attribute();
		comments.setLabel("Comments");
		comments.setType(Attribute.TYPE_STRING);
		comments.setPosition(POSITION);
		comments.setEditor(Attribute.EDITOR_DEFAULT);
		template.getTemplateAttributes().put(HealdanConstants.ATTR_COMMENTS, comments);
		TemplateDAO.get().store(template);
	}
}
