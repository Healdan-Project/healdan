package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.util.spring.Context;

public class ProfileCommentsSeedTest extends AbstractHealdanTestCase {

	private Template template() throws Exception {
		return TemplateDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
	}

	@Test
	public void testAddsComments() throws Exception {
		Template t = template();
		Attribute comments = t.getAttribute(HealdanConstants.ATTR_COMMENTS);
		assertNotNull(comments);
		assertEquals("Comments", comments.getLabel());
		assertEquals(Attribute.TYPE_STRING, comments.getType());
		assertFalse(comments.isMandatory());
		assertEquals(6, comments.getPosition());
		assertEquals(5, t.getAttribute(HealdanConstants.ATTR_DOCSUBCLASS).getPosition());
		assertEquals(7, t.getAttribute(HealdanConstants.ATTR_LEGACYDOCNUM).getPosition());
		assertEquals(13, t.getAttribute(HealdanConstants.ATTR_EMAILRECEIVED).getPosition());
	}

	@Test
	public void testRunsOnceAndKeepsAnAdminsLabel() throws Exception {
		Template t = template();
		t.getAttribute(HealdanConstants.ATTR_COMMENTS).setLabel("Notes");
		TemplateDAO.get().store(t);

		Context.get(SeedRunner.class).runPending();

		Template after = template();
		assertEquals("Notes", after.getAttribute(HealdanConstants.ATTR_COMMENTS).getLabel());
		assertEquals(14, after.getTemplateAttributes().size());
		assertEquals(7, after.getAttribute(HealdanConstants.ATTR_LEGACYDOCNUM).getPosition());
		assertTrue(after.getAttribute(HealdanConstants.ATTR_EMAILRECEIVED).getPosition() == 13);
	}
}
