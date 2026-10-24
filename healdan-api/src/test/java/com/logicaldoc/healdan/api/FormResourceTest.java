package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * {@code GET /v1/folders/{folderId}/form}: the properties form for filing a
 * new document (spec section 6.7).
 * <p>
 * Clerk reads and writes in matter 998.1; Outsider has no grant.
 */
public class FormResourceTest extends ApiTestCase {

	private static final List<String> MEMBERS = List.of("name", "label", "type", "required", "editable", "value",
			"source", "inheritedFrom", "options");

	private User clerk;

	private User outsider;

	private Folder store;

	private Folder workspace;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		clerk = fx.user("clerk");
		outsider = fx.user("outsider");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE);
		Matter matter = fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents");
		workspace = FolderDAO.get().findById(matter.getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private String form(Folder folder) {
		return "/api/v1/folders/" + folder.getId() + "/form";
	}

	@Test
	public void testForm() {
		ApiResponse response = get(form(documents)).token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		assertEquals(13, page.path("total").asInt());
		JsonNode items = page.path("items");
		for (JsonNode field : items)
			assertEquals(field.toString(), MEMBERS, names(field));

		JsonNode client = field(items, "client");
		assertEquals("Client", client.path("label").textValue());
		assertEquals("string", client.path("type").textValue());
		assertEquals("998", client.path("value").textValue());
		assertEquals("inherited", client.path("source").textValue());
		assertEquals(workspace.getId(), client.path("inheritedFrom").path("id").asLong());
		assertEquals(workspace.getName(), client.path("inheritedFrom").path("name").textValue());
		assertEquals("matter", client.path("inheritedFrom").path("kind").textValue());
		assertFalse(client.path("editable").booleanValue());
		assertTrue(client.path("options").isNull());

		JsonNode author = field(items, "author");
		assertEquals("user", author.path("type").textValue());
		assertEquals(clerk.getId(), author.path("value").path("id").asLong());
		assertEquals(clerk.getFullName(), author.path("value").path("name").textValue());
		assertEquals("default", author.path("source").textValue());
		assertTrue(author.path("inheritedFrom").isNull());
		assertTrue(author.path("editable").booleanValue());

		JsonNode docclass = field(items, "docclass");
		assertEquals("Docs", docclass.path("value").textValue());
		assertEquals("default", docclass.path("source").textValue());
		assertTrue(docclass.path("required").booleanValue());
		assertEquals("[{\"value\":\"Docs\",\"label\":\"Docs\",\"class\":null}]", docclass.path("options").toString());

		JsonNode sent = field(items, "emailsent");
		assertEquals("date", sent.path("type").textValue());
		assertTrue(sent.path("value").isNull());
		assertTrue(sent.path("source").isNull());
		assertFalse(sent.path("editable").booleanValue());
		assertTrue(field(items, "legacydocnum").path("editable").booleanValue());
	}

	@Test
	public void testInheritedFromAFolder() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		Folder current = FolderDAO.get().findById(documents.getId(), true);
		current.setValue("docclass", "Letters");
		FolderHistory transaction = fx.folderHistory(fx.admin());
		transaction.setEvent(FolderEvent.CHANGED);
		FolderDAO.get().store(current, transaction);

		JsonNode docclass = field(get(form(sub)).token(signIn(clerk)).send().json().path("items"), "docclass");

		assertEquals("Letters", docclass.path("value").textValue());
		assertEquals("inherited", docclass.path("source").textValue());
		assertEquals(documents.getId(), docclass.path("inheritedFrom").path("id").asLong());
		assertEquals("Documents", docclass.path("inheritedFrom").path("name").textValue());
		assertEquals("folder", docclass.path("inheritedFrom").path("kind").textValue());
	}

	@Test
	public void testTypes() throws Exception {
		Template profile = TemplateDAO.get().findById(ProfileSchema.get().template().getId(), true);
		addAttribute(profile, "pages", Attribute.TYPE_INT, 13);
		addAttribute(profile, "fee", Attribute.TYPE_DOUBLE, 14);
		addAttribute(profile, "urgent", Attribute.TYPE_BOOLEAN, 15);
		addAttribute(profile, "box", Attribute.TYPE_FOLDER, 16);
		addAttribute(profile, "related", Attribute.TYPE_DOCUMENT, 17);
		TemplateDAO.get().store(profile);

		// Values set on Documents pass down
		Folder current = FolderDAO.get().findById(documents.getId(), true);
		current.setValue("pages", 3L);
		current.setValue("fee", 1.5);
		current.setValue("urgent", Boolean.TRUE);
		current.setValue("box", workspace);
		FolderHistory transaction = fx.folderHistory(fx.admin());
		transaction.setEvent(FolderEvent.CHANGED);
		FolderDAO.get().store(current, transaction);
		Folder sub = fx.folder(documents, "Sub");

		JsonNode items = get(form(sub)).token(signIn(clerk)).send().json().path("items");

		assertEquals(List.of("string", "string", "user", "user", "string", "string", "string", "string", "string",
				"string", "string", "date", "date", "integer", "decimal", "boolean", "folder", "document"), types(items));
		assertEquals(3, field(items, "pages").path("value").intValue());
		assertTrue(field(items, "pages").path("value").isIntegralNumber());
		assertEquals(1.5, field(items, "fee").path("value").doubleValue(), 0.0);
		assertTrue(field(items, "urgent").path("value").booleanValue());
		assertEquals(workspace.getId(), field(items, "box").path("value").asLong());
		assertEquals("inherited", field(items, "box").path("source").textValue());
		assertTrue(field(items, "related").path("value").isNull());
	}

	@Test
	public void testFilingRule() throws Exception {
		Folder search = fx.searchFolder(documents, "Recent");
		String token = signIn(clerk);

		for (Folder folder : List.of(workspace, store, search)) {
			ApiResponse refused = get(form(folder)).token(token).send();
			assertEquals(refused.toString(), 409, refused.status());
			assertEquals("filing-rule", refused.code());
		}
		assertEquals("Documents can't be saved at the top level of a matter. Choose one of its folders.",
				get(form(workspace)).token(token).send().json().path("detail").textValue());
	}

	@Test
	public void testUnreadable() throws Exception {
		String token = signIn(outsider);

		ApiResponse unreadable = get(form(documents)).token(token).send();
		ApiResponse unknown = get("/api/v1/folders/987654/form").token(token).send();

		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals("not-found", unreadable.code());
		assertEquals(404, unknown.status());
		assertEquals(unknown.text().replace("987654", "N"),
				unreadable.text().replace(Long.toString(documents.getId()), "N"));

		// Outside the Stores, even for an admin
		String admin = signInAdmin();
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		assertEquals(404, get(form(templates)).token(admin).send().status());
		assertEquals(404,
				get(form(FolderDAO.get().findDefaultWorkspace(Tenant.DEFAULT_ID))).token(admin).send().status());
	}

	private static void addAttribute(Template template, String name, int type, int position) {
		Attribute attribute = new Attribute();
		attribute.setLabel(name);
		attribute.setType(type);
		attribute.setPosition(position);
		template.getTemplateAttributes().put(name, attribute);
	}

	private static JsonNode field(JsonNode items, String name) {
		for (JsonNode field : items)
			if (name.equals(field.path("name").textValue()))
				return field;
		throw new AssertionError("No field " + name + " in " + items);
	}

	private static List<String> names(JsonNode object) {
		List<String> names = new ArrayList<>();
		object.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static List<String> types(JsonNode items) {
		List<String> types = new ArrayList<>();
		items.forEach(field -> types.add(field.path("type").textValue()));
		return types;
	}
}
