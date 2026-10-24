package com.logicaldoc.healdan.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.profile.ProfileView.Option;
import com.logicaldoc.healdan.profile.ProfileView.ProfileField;
import com.logicaldoc.healdan.profile.ProfileView.Source;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * The properties form and a document's properties, with where each value
 * comes from (M2 spec section 6.7, M1 spec sections 6.6 and 6.7).
 */
public class ProfileViewTest extends AbstractHealdanTestCase {

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String SUBCLASS = HealdanConstants.ATTR_DOCSUBCLASS;

	private static final List<String> EMAIL = List.of(HealdanConstants.ATTR_EMAILFROM, HealdanConstants.ATTR_EMAILTO,
			HealdanConstants.ATTR_EMAILCC, HealdanConstants.ATTR_EMAILSUBJECT, HealdanConstants.ATTR_EMAILSENT,
			HealdanConstants.ATTR_EMAILRECEIVED);

	private final HealdanFixtures fx = new HealdanFixtures();

	private User clerk;

	private Folder workspace;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE);
		Matter matter = fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents");
		workspace = FolderDAO.get().findById(matter.getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID,
				true);
		AttributeOptionDAO options = AttributeOptionDAO.get();
		options.store(option(set, CLASS, "Letters", null, null, 1));
		options.store(option(set, CLASS, "Memos", null, null, 2));
		options.store(option(set, SUBCLASS, "Sub1", "Sub one", "Letters", 1));
		options.store(option(set, SUBCLASS, "Sub2", null, "Docs", 2));
	}

	@Test
	public void testNewDocumentInDocuments() throws Exception {
		List<ProfileField> fields = ProfileView.get().forNewDocument(documents.getId(), clerk);

		assertEquals(List.of("client", "matter", "author", "typist", "docclass", "docsubclass", "legacydocnum",
				"emailfrom", "emailto", "emailcc", "emailsubject", "emailsent", "emailreceived"),
				fields.stream().map(ProfileField::name).toList());

		ProfileField client = field(fields, HealdanConstants.ATTR_CLIENT);
		assertEquals("998", client.value());
		assertEquals(Source.INHERITED, client.source());
		assertEquals(Long.valueOf(workspace.getId()), client.inheritedFromId());
		assertEquals(workspace.getName(), client.inheritedFromName());
		assertFalse(client.editable());
		assertEquals("Client", client.label());
		assertEquals(Attribute.TYPE_STRING, client.type());
		ProfileField matter = field(fields, HealdanConstants.ATTR_MATTER);
		assertEquals("1", matter.value());
		assertEquals(Source.INHERITED, matter.source());
		assertEquals(Long.valueOf(workspace.getId()), matter.inheritedFromId());
		assertFalse(matter.editable());

		ProfileField docclass = field(fields, CLASS);
		assertEquals("Docs", docclass.value());
		assertEquals(Source.DEFAULT, docclass.source());
		assertNull(docclass.inheritedFromId());
		assertNull(docclass.inheritedFromName());
		assertTrue(docclass.required());
		assertTrue(docclass.editable());

		for (String name : List.of(HealdanConstants.ATTR_AUTHOR, HealdanConstants.ATTR_TYPIST)) {
			ProfileField user = field(fields, name);
			assertEquals(name, Long.valueOf(clerk.getId()), user.value());
			assertEquals(name, Source.DEFAULT, user.source());
			assertEquals(Attribute.TYPE_USER, user.type());
			assertTrue(user.editable());
			assertFalse(user.required());
		}

		for (String name : EMAIL) {
			ProfileField email = field(fields, name);
			assertFalse(name, email.editable());
			assertNull(name, email.value());
			assertNull(name, email.source());
		}
		assertEquals(Attribute.TYPE_DATE, field(fields, HealdanConstants.ATTR_EMAILSENT).type());

		// Empty fields have no source
		ProfileField legacy = field(fields, HealdanConstants.ATTR_LEGACYDOCNUM);
		assertTrue(legacy.editable());
		assertNull(legacy.value());
		assertNull(legacy.source());
		assertNull(field(fields, SUBCLASS).source());
	}

	@Test
	public void testInheritedFromTheNearestFolder() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		Folder deeper = fx.folder(sub, "Deeper");
		setFolderValue(documents, CLASS, "Letters");

		ProfileField docclass = field(ProfileView.get().forNewDocument(deeper.getId(), clerk), CLASS);
		assertEquals("Letters", docclass.value());
		assertEquals(Source.INHERITED, docclass.source());
		assertEquals(Long.valueOf(documents.getId()), docclass.inheritedFromId());
		assertEquals("Documents", docclass.inheritedFromName());

		// A nearer folder that sets its own wins
		setFolderValue(sub, CLASS, "Memos");
		docclass = field(ProfileView.get().forNewDocument(deeper.getId(), clerk), CLASS);
		assertEquals("Memos", docclass.value());
		assertEquals(Long.valueOf(sub.getId()), docclass.inheritedFromId());
		assertEquals("Sub", docclass.inheritedFromName());

		// The folder's own local value is what a new document in it inherits
		docclass = field(ProfileView.get().forNewDocument(sub.getId(), clerk), CLASS);
		assertEquals(Long.valueOf(sub.getId()), docclass.inheritedFromId());
	}

	@Test
	public void testDocumentOverride() throws Exception {
		setFolderValue(documents, CLASS, "Letters");
		Document memo = create(documents, "memo.txt", Map.of(CLASS, "Memos"));

		List<ProfileField> fields = ProfileView.get().forDocument(reload(memo));
		ProfileField docclass = field(fields, CLASS);
		assertEquals("Memos", docclass.value());
		assertEquals(Source.LOCAL, docclass.source());
		assertNull(docclass.inheritedFromId());
		// A document's own Author is local; client and matter come from the workspace
		ProfileField author = field(fields, HealdanConstants.ATTR_AUTHOR);
		assertEquals(Long.valueOf(clerk.getId()), author.value());
		assertEquals(Source.LOCAL, author.source());
		assertEquals(Source.INHERITED, field(fields, HealdanConstants.ATTR_CLIENT).source());
		assertEquals(Long.valueOf(workspace.getId()), field(fields, HealdanConstants.ATTR_CLIENT).inheritedFromId());
		assertNull(field(fields, HealdanConstants.ATTR_LEGACYDOCNUM).source());

		// Back to what it would inherit
		update(memo, CLASS, "Letters");
		docclass = field(ProfileView.get().forDocument(reload(memo)), CLASS);
		assertEquals("Letters", docclass.value());
		assertEquals(Source.INHERITED, docclass.source());
		assertEquals(Long.valueOf(documents.getId()), docclass.inheritedFromId());
		assertEquals("Documents", docclass.inheritedFromName());
	}

	@Test
	public void testSubclassOptionsCarryTheirClass() throws Exception {
		List<ProfileField> fields = ProfileView.get().forNewDocument(documents.getId(), clerk);

		assertEquals(List.of(new Option("Sub1", "Sub one", "Letters"), new Option("Sub2", "Sub2", "Docs")),
				field(fields, SUBCLASS).options());
		assertEquals(List.of("Docs", "Letters", "Memos"),
				field(fields, CLASS).options().stream().map(Option::value).sorted().toList());
		assertTrue(field(fields, CLASS).options().stream().allMatch(o -> o.docClass() == null));
		assertTrue(field(fields, CLASS).options().stream().allMatch(o -> o.value().equals(o.label())));
		// Fields that aren't picked from a list have no options
		assertNull(field(fields, HealdanConstants.ATTR_LEGACYDOCNUM).options());
		assertNull(field(fields, HealdanConstants.ATTR_AUTHOR).options());
	}

	@Test
	public void testAdminAddedAttribute() throws Exception {
		Document before = create(documents, "before.txt", Map.of());

		Template profile = TemplateDAO.get().findById(ProfileSchema.get().template().getId(), true);
		Attribute court = new Attribute();
		court.setLabel("Court");
		court.setType(Attribute.TYPE_STRING);
		court.setMandatory(true);
		court.setPosition(13);
		profile.getTemplateAttributes().put("court", court);
		Attribute secret = new Attribute();
		secret.setLabel("Secret");
		secret.setType(Attribute.TYPE_STRING);
		secret.setHidden(true);
		secret.setPosition(14);
		profile.getTemplateAttributes().put("secret", secret);
		Attribute section = new Attribute();
		section.setLabel("More");
		section.setType(Attribute.TYPE_SECTION);
		section.setPosition(15);
		profile.getTemplateAttributes().put("more", section);
		TemplateDAO.get().store(profile);

		List<ProfileField> fields = ProfileView.get().forNewDocument(documents.getId(), clerk);
		ProfileField last = fields.get(fields.size() - 1);
		assertEquals("court", last.name());
		assertEquals("Court", last.label());
		assertEquals(Attribute.TYPE_STRING, last.type());
		assertTrue(last.required());
		assertTrue(last.editable());
		assertNull(last.value());
		assertNull(last.source());
		// Hidden attributes and sections aren't fields
		assertEquals(14, fields.size());

		// A document filed before the attribute existed shows it empty
		ProfileField onBefore = field(ProfileView.get().forDocument(reload(before)), "court");
		assertNull(onBefore.value());
		assertNull(onBefore.source());
	}

	private static AttributeOption option(AttributeSet set, String attribute, String value, String label,
			String category, int position) {
		AttributeOption option = new AttributeOption(set.getId(), attribute, value, category);
		option.setLabel(label);
		option.setPosition(position);
		return option;
	}

	private static ProfileField field(List<ProfileField> fields, String name) {
		return fields.stream().filter(f -> f.name().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError("No field " + name));
	}

	/** As the old UI's folder edit does: the listener records the local value and passes it down */
	private void setFolderValue(Folder folder, String name, String value) throws Exception {
		Folder current = FolderDAO.get().findById(folder.getId(), true);
		current.setValue(name, value);
		FolderHistory transaction = fx.folderHistory(fx.admin());
		transaction.setEvent(FolderEvent.CHANGED);
		FolderDAO.get().store(current, transaction);
	}

	private Document create(Folder folder, String fileName, Map<String, Object> values) throws Exception {
		Document vo = new Document();
		vo.setFileName(fileName);
		vo.setFolder(folder);
		vo.setTenantId(Tenant.DEFAULT_ID);
		vo.setLanguage("en");
		values.forEach(vo::setValue);
		Document created = DocumentManager.get()
				.create(new ByteArrayInputStream("Healdan".getBytes(StandardCharsets.UTF_8)), vo,
						fx.documentHistory(clerk))
				.getDocument();
		awaitAsyncWork();
		return created;
	}

	/** Edits through CE's update, as the GUI does */
	private void update(Document document, String name, Object value) throws Exception {
		Document current = reload(document);
		Document vo = new Document(current);
		// The attribute objects are shared with the stored document
		vo.getAttributes().replaceAll((n, a) -> new Attribute(a));
		vo.setValue(name, value);
		DocumentHistory transaction = fx.documentHistory(clerk);
		transaction.setEvent(DocumentEvent.CHANGED);
		DocumentManager.get().update(current, vo, transaction);
	}

	private static Document reload(Document document) throws Exception {
		return DocumentDAO.get().findById(document.getId(), true);
	}

	private static void awaitAsyncWork() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 20000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				fail("Background version saves didn't finish");
			Thread.sleep(50L);
		}
	}
}
