package com.logicaldoc.healdan.seed;

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
				attribute("Class", Attribute.Type.STRING, 0, Attribute.EDITOR_LISTBOX));
		set.getTemplateAttributes().put(HealdanConstants.ATTR_DOCSUBCLASS,
				attribute("Subclass", Attribute.Type.STRING, 1, Attribute.EDITOR_LISTBOX));
		AttributeSetDAO.get().store(set);
		return AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
	}

	private void addAttributes(Template template, long setId) {
		String matterLabel = settings.matterLabel();

		Attribute client = put(template, HealdanConstants.ATTR_CLIENT, "Client", Attribute.Type.STRING, 0);
		client.setReadonly(true);
		Attribute matter = put(template, HealdanConstants.ATTR_MATTER, matterLabel, Attribute.Type.STRING, 1);
		matter.setReadonly(true);
		put(template, HealdanConstants.ATTR_AUTHOR, "Author", Attribute.Type.USER, 2);
		put(template, HealdanConstants.ATTR_TYPIST, "Typist", Attribute.Type.USER, 3);

		Attribute docClass = put(template, HealdanConstants.ATTR_DOCCLASS, "Class", Attribute.Type.STRING, 4);
		docClass.setEditor(Attribute.EDITOR_LISTBOX);
		docClass.setMandatory(true);
		docClass.setSetId(setId);
		Attribute subclass = put(template, HealdanConstants.ATTR_DOCSUBCLASS, "Subclass", Attribute.Type.STRING, 5);
		subclass.setEditor(Attribute.EDITOR_LISTBOX);
		subclass.setDependsOn(HealdanConstants.ATTR_DOCCLASS);
		subclass.setSetId(setId);

		put(template, HealdanConstants.ATTR_LEGACYDOCNUM, "Legacy doc #", Attribute.Type.STRING, 6);
		put(template, HealdanConstants.ATTR_EMAILFROM, "From", Attribute.Type.STRING, 7);
		put(template, HealdanConstants.ATTR_EMAILTO, "To", Attribute.Type.STRING, 8);
		put(template, HealdanConstants.ATTR_EMAILCC, "CC", Attribute.Type.STRING, 9);
		put(template, HealdanConstants.ATTR_EMAILSUBJECT, "Subject", Attribute.Type.STRING, 10);
		put(template, HealdanConstants.ATTR_EMAILSENT, "Sent", Attribute.Type.DATE, 11);
		put(template, HealdanConstants.ATTR_EMAILRECEIVED, "Received", Attribute.Type.DATE, 12);
	}

	private Attribute put(Template template, String name, String label, Attribute.Type type, int position) {
		Attribute attribute = attribute(label, type, position, Attribute.EDITOR_DEFAULT);
		template.getTemplateAttributes().put(name, attribute);
		return attribute;
	}

	private Attribute attribute(String label, Attribute.Type type, int position, int editor) {
		Attribute attribute = new Attribute();
		attribute.setLabel(label);
		attribute.setType(type);
		attribute.setPosition(position);
		attribute.setEditor(editor);
		return attribute;
	}

	private void addDefaultClassOption(long setId) throws Exception {
		String value = settings.defaultClass();
		AttributeOptionDAO dao = AttributeOptionDAO.get();
		boolean present = dao.findByAttribute(setId, HealdanConstants.ATTR_DOCCLASS).stream()
				.anyMatch(o -> value.equals(o.getValue()));
		if (!present)
			dao.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCCLASS, value));
	}
}
