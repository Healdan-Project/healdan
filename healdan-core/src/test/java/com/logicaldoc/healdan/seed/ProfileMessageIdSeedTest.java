// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

public class ProfileMessageIdSeedTest extends AbstractHealdanTestCase {

	private static final String MARKER = "seed.profile-message-id";

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private Template template() throws Exception {
		return TemplateDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
	}

	private Map<String, Integer> positions(Template t) {
		Map<String, Integer> positions = new HashMap<>();
		t.getTemplateAttributes().forEach((name, attribute) -> positions.put(name, attribute.getPosition()));
		return positions;
	}

	@Test
	public void testAddsMessageId() throws Exception {
		assertNotNull(HealdanSettings.get().get(MARKER));

		Template t = template();
		Attribute messageId = t.getAttribute(HealdanConstants.ATTR_EMAILMESSAGEID);
		assertNotNull(messageId);
		assertEquals("Message-ID", messageId.getLabel());
		assertEquals(Attribute.Type.STRING, messageId.getType());
		assertEquals(14, messageId.getPosition());
		assertEquals(Attribute.EDITOR_DEFAULT, messageId.getEditor());
		assertFalse(messageId.isMandatory());
		assertEquals(13, t.getAttribute(HealdanConstants.ATTR_EMAILRECEIVED).getPosition());
		assertEquals(15, t.getTemplateAttributes().size());
	}

	@Test
	public void testRunningTwiceAddsItOnceAndKeepsAnAdminsLabel() throws Exception {
		Template t = template();
		t.getAttribute(HealdanConstants.ATTR_EMAILMESSAGEID).setLabel("Internet Message ID");
		TemplateDAO.get().store(t);
		Map<String, Integer> before = positions(template());

		jdbc().update("delete from hd_setting where name = ?", MARKER);
		Context.get(SeedRunner.class).runPending();
		Context.get(SeedRunner.class).runPending();

		Template after = template();
		assertEquals("Internet Message ID", after.getAttribute(HealdanConstants.ATTR_EMAILMESSAGEID).getLabel());
		assertEquals(15, after.getTemplateAttributes().size());
		assertEquals(before, positions(after));
		assertEquals("done", HealdanSettings.get().get(MARKER));
	}

	@Test
	public void testATemplateMadeBeforeTheStepGainsItAndKeepsTheRest() throws Exception {
		Template t = template();
		t.getTemplateAttributes().remove(HealdanConstants.ATTR_EMAILMESSAGEID);
		TemplateDAO.get().store(t);
		Map<String, Integer> before = positions(template());
		assertNull(template().getAttribute(HealdanConstants.ATTR_EMAILMESSAGEID));
		assertEquals(14, before.size());

		jdbc().update("delete from hd_setting where name = ?", MARKER);
		Context.get(SeedRunner.class).runPending();

		Template after = template();
		Attribute messageId = after.getAttribute(HealdanConstants.ATTR_EMAILMESSAGEID);
		assertNotNull(messageId);
		assertEquals("Message-ID", messageId.getLabel());
		assertEquals(14, messageId.getPosition());
		Map<String, Integer> now = positions(after);
		now.remove(HealdanConstants.ATTR_EMAILMESSAGEID);
		assertEquals(before, now);
	}

	@Test
	public void testAProfileAnAdminDeletedStaysDeleted() throws Exception {
		TemplateDAO.get().delete(template().getId());
		jdbc().update("delete from hd_setting where name = ?", MARKER);

		Context.get(SeedRunner.class).runPending();

		assertNull(TemplateDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID));
		assertTrue(HealdanSettings.get().get(MARKER) != null);
	}
}
