package com.logicaldoc.healdan.seed;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * Creates the Healdan profile: the CE attribute set that holds the Class and
 * Subclass option lists, and the CE template every document and folder uses.
 * Options belong to the attribute set (not the template), so the template's
 * Class and Subclass attributes point at it.
 */
@Component
public class ProfileSeed implements SeedStep {

	private static final String DEFAULT_CLASS = "Docs";

	private final HealdanSettings settings;

	@Autowired
	public ProfileSeed(HealdanSettings settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return "profile";
	}

	@Override
	public void run() throws Exception {
		AttributeSet set = findOrCreateSet();

		Template template = TemplateDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
		if (template == null) {
			template = new Template();
			template.setName(HealdanConstants.PROFILE_TEMPLATE);
			template.setLabel(HealdanConstants.PROFILE_TEMPLATE);
			template.setDescription("Healdan's profile: the values every document and folder carries");
			template.setType(Template.TYPE_DEFAULT);
			template.setTenantId(Tenant.DEFAULT_ID);
			addAttributes(template, set.getId());
			TemplateDAO.get().store(template);
		}
		settings.set(HealdanSettings.PROFILE_TEMPLATE, Long.toString(template.getId()));

		addDefaultClassOption(set.getId());
	}

	private AttributeSet findOrCreateSet() throws Exception {
		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID,
				true);
		if (set != null)
			return set;

		set = new AttributeSet();
		set.setName(HealdanConstants.PROFILE_TEMPLATE);
		set.setLabel(HealdanConstants.PROFILE_TEMPLATE);
		set.setDescription("The Class and Subclass lists of the Healdan profile");
		set.setType(AttributeSet.TYPE_DEFAULT);
		set.setTenantId(Tenant.DEFAULT_ID);
		// The set must hold the list attributes, or CE drops their options when it stores the set
		set.getTemplateAttributes().put(HealdanConstants.ATTR_DOCCLASS,
				attribute("Class", Attribute.TYPE_STRING, 0, Attribute.EDITOR_LISTBOX));
		set.getTemplateAttributes().put(HealdanConstants.ATTR_DOCSUBCLASS,
				attribute("Subclass", Attribute.TYPE_STRING, 1, Attribute.EDITOR_LISTBOX));
		AttributeSetDAO.get().store(set);
		return AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
	}

	private void addAttributes(Template template, long setId) {
		String matterLabel = StringUtils.defaultIfBlank(settings.matterLabel(), "Matter");

		Attribute client = put(template, HealdanConstants.ATTR_CLIENT, "Client", Attribute.TYPE_STRING, 0);
		client.setReadonly(true);
		Attribute matter = put(template, HealdanConstants.ATTR_MATTER, matterLabel, Attribute.TYPE_STRING, 1);
		matter.setReadonly(true);
		put(template, HealdanConstants.ATTR_AUTHOR, "Author", Attribute.TYPE_USER, 2);
		put(template, HealdanConstants.ATTR_TYPIST, "Typist", Attribute.TYPE_USER, 3);

		Attribute docClass = put(template, HealdanConstants.ATTR_DOCCLASS, "Class", Attribute.TYPE_STRING, 4);
		docClass.setEditor(Attribute.EDITOR_LISTBOX);
		docClass.setMandatory(true);
		docClass.setSetId(setId);
		Attribute subclass = put(template, HealdanConstants.ATTR_DOCSUBCLASS, "Subclass", Attribute.TYPE_STRING, 5);
		subclass.setEditor(Attribute.EDITOR_LISTBOX);
		subclass.setDependsOn(HealdanConstants.ATTR_DOCCLASS);
		subclass.setSetId(setId);

		put(template, HealdanConstants.ATTR_LEGACYDOCNUM, "Legacy doc #", Attribute.TYPE_STRING, 6);
		put(template, HealdanConstants.ATTR_EMAILFROM, "From", Attribute.TYPE_STRING, 7);
		put(template, HealdanConstants.ATTR_EMAILTO, "To", Attribute.TYPE_STRING, 8);
		put(template, HealdanConstants.ATTR_EMAILCC, "CC", Attribute.TYPE_STRING, 9);
		put(template, HealdanConstants.ATTR_EMAILSUBJECT, "Subject", Attribute.TYPE_STRING, 10);
		put(template, HealdanConstants.ATTR_EMAILSENT, "Sent", Attribute.TYPE_DATE, 11);
		put(template, HealdanConstants.ATTR_EMAILRECEIVED, "Received", Attribute.TYPE_DATE, 12);
	}

	private Attribute put(Template template, String name, String label, int type, int position) {
		Attribute attribute = attribute(label, type, position, Attribute.EDITOR_DEFAULT);
		template.getTemplateAttributes().put(name, attribute);
		return attribute;
	}

	private Attribute attribute(String label, int type, int position, int editor) {
		Attribute attribute = new Attribute();
		attribute.setLabel(label);
		attribute.setType(type);
		attribute.setPosition(position);
		attribute.setEditor(editor);
		return attribute;
	}

	private void addDefaultClassOption(long setId) throws Exception {
		String value = StringUtils.defaultIfBlank(settings.defaultClass(), DEFAULT_CLASS);
		AttributeOptionDAO dao = AttributeOptionDAO.get();
		boolean present = dao.findByAttribute(setId, HealdanConstants.ATTR_DOCCLASS).stream()
				.anyMatch(o -> value.equals(o.getValue()));
		if (!present)
			dao.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCCLASS, value));
	}
}
