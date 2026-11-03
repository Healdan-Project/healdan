// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;

/**
 * The Healdan profile's attribute set holds the Class and Subclass lists, so
 * nobody, an administrator included, deletes it or takes those attributes out
 * of it: either empties the lists. Whoever holds Document metadata, CE's
 * poweruser group among them, could. The check is in the attribute set DAO,
 * which every route reaches.
 */
public class ProfileSetTest extends AbstractHealdanTestCase {

	@Test
	public void testTheProfilesSetCantBeDeleted() throws Exception {
		AttributeSet set = profileSet();
		List<String> classes = ProfileSchema.get().classOptions();
		assertFalse(classes.isEmpty());

		HealdanRuleException refusal = assertThrows(HealdanRuleException.class,
				() -> AttributeSetDAO.get().delete(set.getId()));
		assertEquals("The Healdan profile's attribute set can't be deleted: it holds the Class and Subclass lists.",
				refusal.getMessage());
		assertEquals(0, AttributeSetDAO.get().findById(set.getId()).getDeleted());
		assertEquals(classes, ProfileSchema.get().classOptions());
	}

	@Test
	public void testTheProfilesSetKeepsClassAndSubclass() throws Exception {
		List<String> classes = ProfileSchema.get().classOptions();
		for (String name : List.of(HealdanConstants.ATTR_DOCCLASS, HealdanConstants.ATTR_DOCSUBCLASS)) {
			AttributeSet set = profileSet();
			set.removeAttribute(name);
			HealdanRuleException refusal = assertThrows(name, HealdanRuleException.class,
					() -> AttributeSetDAO.get().store(set));
			assertEquals("The Healdan profile's attribute set must keep its Class and Subclass attributes.",
					refusal.getMessage());
			assertNotNull(name, profileSet().getAttribute(name));
		}
		assertEquals(classes, ProfileSchema.get().classOptions());
	}

	@Test
	public void testOtherChangesToTheProfilesSetAreSaved() throws Exception {
		AttributeSet set = profileSet();
		set.getAttribute(HealdanConstants.ATTR_DOCCLASS).setLabel("Kind");
		Attribute region = new Attribute();
		region.setLabel("Region");
		region.setType(Attribute.Type.STRING);
		set.getTemplateAttributes().put("region", region);

		AttributeSetDAO.get().store(set);

		AttributeSet stored = profileSet();
		assertEquals("Kind", stored.getAttribute(HealdanConstants.ATTR_DOCCLASS).getLabel());
		assertNotNull(stored.getAttribute("region"));
	}

	@Test
	public void testOtherSetsCanBeDeletedOrEmptied() throws Exception {
		AttributeSet other = new AttributeSet();
		other.setName("Other");
		other.setTenantId(Tenant.DEFAULT_ID);
		Attribute docClass = new Attribute();
		docClass.setType(Attribute.Type.STRING);
		other.getTemplateAttributes().put(HealdanConstants.ATTR_DOCCLASS, docClass);
		AttributeSetDAO dao = AttributeSetDAO.get();
		dao.store(other);

		other = dao.findById(other.getId(), true);
		other.removeAttribute(HealdanConstants.ATTR_DOCCLASS);
		dao.store(other);
		dao.delete(other.getId());

		assertNull(dao.findById(other.getId()));
	}

	private static AttributeSet profileSet() throws Exception {
		return AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
	}
}
