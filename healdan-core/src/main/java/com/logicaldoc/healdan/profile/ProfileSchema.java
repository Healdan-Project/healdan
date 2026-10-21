package com.logicaldoc.healdan.profile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * What the rules need to know about the Healdan profile template. Nothing is
 * cached, so attributes an admin adds to the template later are seen at once.
 */
@Component
public class ProfileSchema {

	/** Attributes that belong to a document alone and never pass down */
	private static final Set<String> DOCUMENT_ONLY = Set.of(HealdanConstants.ATTR_AUTHOR,
			HealdanConstants.ATTR_TYPIST, HealdanConstants.ATTR_LEGACYDOCNUM, HealdanConstants.ATTR_EMAILFROM,
			HealdanConstants.ATTR_EMAILTO, HealdanConstants.ATTR_EMAILCC, HealdanConstants.ATTR_EMAILSUBJECT,
			HealdanConstants.ATTR_EMAILSENT, HealdanConstants.ATTR_EMAILRECEIVED);

	private static final Set<String> FIXED = Set.of(HealdanConstants.ATTR_CLIENT, HealdanConstants.ATTR_MATTER);

	public static ProfileSchema get() {
		return Context.get(ProfileSchema.class);
	}

	/**
	 * @return the Healdan profile template, with its attributes
	 * @throws IllegalStateException if there is no such template
	 */
	public Template template() {
		Long id = HealdanSettings.get().profileTemplateId();
		try {
			Template template = id != null ? TemplateDAO.get().findById(id, true) : null;
			if (template == null)
				throw new IllegalStateException("The template '%s' doesn't exist".formatted(HealdanConstants.PROFILE_TEMPLATE));
			return template;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Cannot read the template '%s'".formatted(HealdanConstants.PROFILE_TEMPLATE), e);
		}
	}

	/**
	 * @return the attributes that pass down: all but the document-only ones,
	 *         including those added to the template later
	 */
	public Set<String> inheritable() {
		Set<String> names = new HashSet<>(template().getAttributeNames());
		names.removeAll(DOCUMENT_ONLY);
		return names;
	}

	/**
	 * @return the attributes that always come from the workspace
	 */
	public Set<String> fixed() {
		return FIXED;
	}

	/**
	 * @return the Class options admins maintain, in their order; empty when
	 *         the Class attribute has none
	 */
	public List<String> classOptions() {
		Attribute attribute = template().getAttribute(HealdanConstants.ATTR_DOCCLASS);
		if (attribute == null || attribute.getSetId() == null)
			return List.of();
		try {
			return AttributeOptionDAO.get().findByAttribute(attribute.getSetId(), HealdanConstants.ATTR_DOCCLASS)
					.stream().map(AttributeOption::getValue).toList();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Cannot read the Class options", e);
		}
	}

	/**
	 * @param docClass a Class value
	 * @return the Subclass options that belong to that Class, in their order;
	 *         empty for no Class
	 */
	public List<String> subclassOptions(String docClass) {
		if (StringUtils.isEmpty(docClass))
			return List.of();
		Attribute attribute = template().getAttribute(HealdanConstants.ATTR_DOCSUBCLASS);
		if (attribute == null || attribute.getSetId() == null)
			return List.of();
		try {
			return AttributeOptionDAO.get()
					.findByAttributeAndCategory(attribute.getSetId(), HealdanConstants.ATTR_DOCSUBCLASS, docClass)
					.stream().map(AttributeOption::getValue).toList();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Cannot read the Subclass options", e);
		}
	}

	/**
	 * @param subclass a Subclass value
	 * @param docClass a Class value
	 * @return true if the Subclass has an option whose category is that Class
	 */
	public boolean subclassBelongs(String subclass, String docClass) {
		if (StringUtils.isEmpty(subclass) || StringUtils.isEmpty(docClass))
			return false;
		Attribute attribute = template().getAttribute(HealdanConstants.ATTR_DOCSUBCLASS);
		if (attribute == null || attribute.getSetId() == null)
			return false;
		try {
			return AttributeOptionDAO.get()
					.findByAttributeAndCategory(attribute.getSetId(), HealdanConstants.ATTR_DOCSUBCLASS, docClass)
					.stream().map(AttributeOption::getValue).anyMatch(subclass::equals);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Cannot read the Subclass options", e);
		}
	}
}
