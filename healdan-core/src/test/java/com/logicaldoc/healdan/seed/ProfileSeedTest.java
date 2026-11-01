package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanStartup;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

public class ProfileSeedTest extends AbstractHealdanTestCase {

	private static final List<String> NAMES = List.of("client", "matter", "author", "typist", "docclass",
			"docsubclass", "comments", "legacydocnum", "emailfrom", "emailto", "emailcc", "emailsubject", "emailsent",
			"emailreceived");

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private Template template() throws Exception {
		return TemplateDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
	}

	private Attribute at(Template t, int position) {
		return t.getTemplateAttributes().values().stream().filter(a -> a.getPosition() == position).findFirst()
				.orElseThrow();
	}

	private String nameAt(Template t, int position) {
		return t.getTemplateAttributes().entrySet().stream().filter(e -> e.getValue().getPosition() == position)
				.map(e -> e.getKey()).findFirst().orElseThrow();
	}

	@Test
	public void testTemplateHasFourteenAttributesInOrder() throws Exception {
		Template t = template();
		assertNotNull(t);
		assertEquals(t.getId(), HealdanSettings.get().profileTemplateId().longValue());
		assertEquals(14, t.getTemplateAttributes().size());
		for (int i = 0; i < NAMES.size(); i++)
			assertEquals(NAMES.get(i), nameAt(t, i));

		Attribute client = at(t, 0);
		assertEquals("Client", client.getLabel());
		assertEquals(Attribute.Type.STRING, client.getType());
		assertTrue(client.isReadonly());
		Attribute matter = at(t, 1);
		assertEquals(HealdanSettings.get().matterLabel(), matter.getLabel());
		assertTrue(matter.isReadonly());
		assertFalse(at(t, 2).isReadonly());
		assertEquals(Attribute.Type.USER, at(t, 2).getType());
		assertEquals("Author", at(t, 2).getLabel());
		assertEquals(Attribute.Type.USER, at(t, 3).getType());
		assertEquals("Typist", at(t, 3).getLabel());

		Attribute docclass = at(t, 4);
		assertEquals("Class", docclass.getLabel());
		assertEquals(Attribute.EDITOR_LISTBOX, docclass.getEditor());
		assertTrue(docclass.isMandatory());
		Attribute sub = at(t, 5);
		assertEquals("Subclass", sub.getLabel());
		assertEquals(Attribute.EDITOR_LISTBOX, sub.getEditor());
		assertEquals("docclass", sub.getDependsOn());
		assertFalse(sub.isMandatory());
		assertEquals("Comments", at(t, 6).getLabel());
		assertEquals(Attribute.Type.STRING, at(t, 6).getType());
		assertFalse(at(t, 6).isMandatory());
		assertEquals("Legacy doc #", at(t, 7).getLabel());

		assertEquals(List.of("From", "To", "CC", "Subject"),
				List.of(at(t, 8).getLabel(), at(t, 9).getLabel(), at(t, 10).getLabel(), at(t, 11).getLabel()));
		for (int i = 8; i <= 11; i++)
			assertEquals(Attribute.Type.STRING, at(t, i).getType());
		assertEquals("Sent", at(t, 12).getLabel());
		assertEquals("Received", at(t, 13).getLabel());
		assertEquals(Attribute.Type.DATE, at(t, 12).getType());
		assertEquals(Attribute.Type.DATE, at(t, 13).getType());
	}

	@Test
	public void testDocsOptionExists() throws Exception {
		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID);
		assertNotNull(set);
		List<AttributeOption> options = AttributeOptionDAO.get().findByAttribute(set.getId(), "docclass");
		assertEquals(List.of("Docs"), options.stream().map(AttributeOption::getValue).toList());

		// The template's list attributes point at the set, so the GUI finds the options
		Template t = template();
		assertEquals(Long.valueOf(set.getId()), t.getAttribute("docclass").getSetId());
		assertEquals(Long.valueOf(set.getId()), t.getAttribute("docsubclass").getSetId());
	}

	@Test
	public void testInheritableExcludesDocumentOnly() throws Exception {
		ProfileSchema schema = ProfileSchema.get();
		assertEquals(Set.of("client", "matter", "docclass", "docsubclass"), schema.inheritable());
		assertEquals(Set.of("client", "matter"), schema.fixed());

		// An attribute an admin adds later is inheritable
		Template t = template();
		Attribute extra = new Attribute();
		extra.setType(Attribute.Type.STRING);
		extra.setLabel("Office");
		extra.setPosition(14);
		t.getTemplateAttributes().put("office", extra);
		TemplateDAO.get().store(t);
		assertTrue(schema.inheritable().contains("office"));
		assertEquals(Set.of("client", "matter", "docclass", "docsubclass", "office"),
				schema.inheritable().stream().collect(Collectors.toSet()));
		assertEquals(15, schema.template().getTemplateAttributes().size());
	}

	@Test
	public void testSubclassBelongs() throws Exception {
		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID);
		AttributeOptionDAO.get().store(new AttributeOption(set.getId(), "docclass", "Financial"));
		AttributeOptionDAO.get().store(new AttributeOption(set.getId(), "docsubclass", "Invoices", "Financial"));
		AttributeOptionDAO.get().store(new AttributeOption(set.getId(), "docsubclass", "Letters", "Docs"));

		ProfileSchema schema = ProfileSchema.get();
		assertTrue(schema.subclassBelongs("Invoices", "Financial"));
		assertTrue(schema.subclassBelongs("Letters", "Docs"));
		assertFalse(schema.subclassBelongs("Invoices", "Docs"));
		assertFalse(schema.subclassBelongs("Letters", "Financial"));
		assertFalse(schema.subclassBelongs("Nope", "Docs"));
		assertFalse(schema.subclassBelongs("Invoices", null));
	}

	@Test
	public void testSeedTwiceNoDuplicates() throws Exception {
		long id = template().getId();
		jdbc().update("delete from hd_setting where name = ?", "seed.profile");

		Context.get(HealdanStartup.class).run();

		assertEquals(1, jdbc().queryForObject("select count(*) from ld_template where ld_name = ?", Integer.class,
				HealdanConstants.PROFILE_TEMPLATE).intValue());
		assertEquals(1, jdbc().queryForObject("select count(*) from ld_attributeset where ld_name = ?", Integer.class,
				HealdanConstants.PROFILE_TEMPLATE).intValue());
		assertEquals(1, jdbc().queryForObject(
				"select count(*) from ld_extoption where ld_attribute = 'docclass' and ld_value = 'Docs'",
				Integer.class).intValue());
		assertEquals(id, HealdanSettings.get().profileTemplateId().longValue());
		assertEquals(14, template().getTemplateAttributes().size());
	}

	@Test
	public void testAdminDeletedTemplateNotRecreated() throws Exception {
		TemplateDAO.get().delete(template().getId());
		assertNull(TemplateDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID));

		Context.get(HealdanStartup.class).run();

		assertNull(TemplateDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID));
		assertEquals("done", HealdanSettings.get().get("seed.profile"));
	}
}
