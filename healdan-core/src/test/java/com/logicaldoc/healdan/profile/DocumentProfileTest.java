package com.logicaldoc.healdan.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.channels.ChannelHarness;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;

/**
 * Spec §6.6 and §6.7: every document in a matter carries the Healdan profile,
 * inherited from its folder unless a value was set on the document itself.
 * The folder values here are set directly; Task 12 stamps folders.
 */
public class DocumentProfileTest extends AbstractRulesTestCase {

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String SUBCLASS = HealdanConstants.ATTR_DOCSUBCLASS;

	private static final String SUBCLASS_MISMATCH = "Subclass Sub1 doesn't belong to Class Docs.";

	private final ChannelHarness harness = new ChannelHarness();

	private Session session;

	private AttributeSet set;

	@Override
	protected List<String> getDatabaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "sql/logicaldoc-webservice.sql", "data.sql");
	}

	@Before
	public void createOptions() throws Exception {
		session = SessionManager.get().newSession("admin", "admin", (Client) null);

		set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
		AttributeOptionDAO options = AttributeOptionDAO.get();
		options.store(new AttributeOption(set.getId(), CLASS, "Letters"));
		options.store(new AttributeOption(set.getId(), SUBCLASS, "Sub1", "Letters"));
		options.store(new AttributeOption(set.getId(), SUBCLASS, "Sub2", "Docs"));
	}

	@After
	public void closeSession() {
		if (session != null)
			SessionManager.get().kill(session.getSid());
	}

	@Test
	public void testClassDefaultsToDocs() throws Exception {
		Document created = upload(documents, "new.txt", alice);

		assertEquals("Docs", text(created, CLASS));
		assertEquals("998", text(created, HealdanConstants.ATTR_CLIENT));
		assertEquals("1", text(created, HealdanConstants.ATTR_MATTER));
		assertEquals(HealdanConstants.PROFILE_TEMPLATE, reload(created).getTemplate().getName());
		assertEquals(Set.of(), locals(created));
		// Every attribute of the template is there, empty or not
		assertTrue(reload(created).getAttributeNames()
				.containsAll(ProfileSchema.get().template().getAttributeNames()));
	}

	@Test
	public void testCreateInheritsTheFoldersValues() throws Exception {
		setFolderValue(documents, CLASS, "Letters");

		Document inDocuments = upload(documents, "a.txt", alice);
		Document inCorrespondence = upload(correspondence, "b.txt", alice);

		assertEquals("Letters", text(inDocuments, CLASS));
		assertEquals(Set.of(), locals(inDocuments));
		assertEquals("Docs", text(inCorrespondence, CLASS));
	}

	@Test
	public void testDifferentClassAtUploadIsLocal() throws Exception {
		Document created = createWith(correspondence, "letter.txt", alice, Map.of(CLASS, "Letters"));
		assertEquals("Letters", text(created, CLASS));
		assertEquals(Set.of(CLASS), locals(created));

		// Different from what the folder passes down, even when it is the default
		setFolderValue(documents, CLASS, "Letters");
		Document docs = createWith(documents, "docs.txt", alice, Map.of(CLASS, "Docs"));
		assertEquals("Docs", text(docs, CLASS));
		assertEquals(Set.of(CLASS), locals(docs));
	}

	@Test
	public void testBlankOrEqualValueInherits() throws Exception {
		Document equal = createWith(correspondence, "equal.txt", alice, Map.of(CLASS, "Docs"));
		assertEquals(Set.of(), locals(equal));

		setFolderValue(documents, CLASS, "Letters");
		Document blank = createWith(documents, "blank.txt", alice, Map.of(CLASS, ""));
		assertEquals("Letters", text(blank, CLASS));
		assertEquals(Set.of(), locals(blank));
	}

	@Test
	public void testCopyKeepsDifferentClass() throws Exception {
		Document letter = createWith(documents, "letter.txt", alice, Map.of(CLASS, "Letters"));
		Document plain = upload(documents, "plain.txt", alice);

		Document copy = DocumentManager.get()
				.copyToFolder(reload(letter), correspondence, documentHistory(alice), false, false, false)
				.getDocument();
		Document plainCopy = DocumentManager.get()
				.copyToFolder(reload(plain), correspondence, documentHistory(alice), false, false, false)
				.getDocument();
		awaitAsyncWork();

		assertEquals("Letters", text(copy, CLASS));
		assertEquals(Set.of(CLASS), locals(copy));
		assertEquals("Docs", text(plainCopy, CLASS));
		assertEquals(Set.of(), locals(plainCopy));
		// The source is untouched
		assertEquals("Letters", text(letter, CLASS));
	}

	@Test
	public void testCopyToAnotherMatterTakesItsClientAndMatter() throws Exception {
		Matter other = fx.matter(fx.client(5, "Other"), 7, "Other Matter", store, "Documents");
		Folder target = child(folderDao.findById(other.getFolderId(), true), "Documents");
		Document copy = DocumentManager.get()
				.copyToFolder(reload(doc), target, documentHistory(alice), false, false, false).getDocument();
		awaitAsyncWork();

		assertEquals("5", text(copy, HealdanConstants.ATTR_CLIENT));
		assertEquals("7", text(copy, HealdanConstants.ATTR_MATTER));
		assertEquals("998", text(doc, HealdanConstants.ATTR_CLIENT));
		assertEquals("1", text(doc, HealdanConstants.ATTR_MATTER));
	}

	@Test
	public void testClientMatterCannotBeEdited() throws Exception {
		Document created = createWith(documents, "new.txt", alice,
				Map.of(HealdanConstants.ATTR_CLIENT, "5", HealdanConstants.ATTR_MATTER, "9"));
		assertEquals("998", text(created, HealdanConstants.ATTR_CLIENT));
		assertEquals("1", text(created, HealdanConstants.ATTR_MATTER));

		edit(created, alice, HealdanConstants.ATTR_CLIENT, "5");
		edit(created, alice, HealdanConstants.ATTR_MATTER, "");
		assertEquals("998", text(created, HealdanConstants.ATTR_CLIENT));
		assertEquals("1", text(created, HealdanConstants.ATTR_MATTER));
		assertEquals(Set.of(), locals(created));
	}

	@Test
	public void testAuthorTypistDefaultToCreator() throws Exception {
		Document created = upload(documents, "new.txt", alice);
		assertEquals("alice", text(created, HealdanConstants.ATTR_AUTHOR));
		assertEquals("alice", text(created, HealdanConstants.ATTR_TYPIST));
		assertEquals(alice.getId(), reload(created).getValue(HealdanConstants.ATTR_AUTHOR));

		// Filing on someone's behalf: the author is kept, the typist is the creator
		User author = bob;
		Document onBehalf = createWith(documents, "behalf.txt", alice, Map.of(HealdanConstants.ATTR_AUTHOR, author));
		assertEquals("bob", text(onBehalf, HealdanConstants.ATTR_AUTHOR));
		assertEquals("alice", text(onBehalf, HealdanConstants.ATTR_TYPIST));
		// Author and typist never pass down, so they are never local
		assertEquals(Set.of(), locals(onBehalf));
	}

	@Test
	public void testSubclassMustBelongToClass() throws Exception {
		assertRefused(SUBCLASS_MISMATCH, () -> createWith(documents, "bad.txt", alice, Map.of(SUBCLASS, "Sub1")));
		assertRefused("Subclass Sub2 doesn't belong to Class Letters.",
				() -> createWith(documents, "bad.txt", alice, Map.of(CLASS, "Letters", SUBCLASS, "Sub2")));

		Document good = createWith(documents, "good.txt", alice, Map.of(CLASS, "Letters", SUBCLASS, "Sub1"));
		assertEquals("Sub1", text(good, SUBCLASS));
		assertEquals(Set.of(CLASS, SUBCLASS), locals(good));
		Document docsSub = createWith(documents, "docs.txt", alice, Map.of(SUBCLASS, "Sub2"));
		assertEquals("Sub2", text(docsSub, SUBCLASS));

		// An update is refused before CE writes anything
		assertRefused(SUBCLASS_MISMATCH, () -> edit(doc, alice, SUBCLASS, "Sub1"));
		assertRefused("Subclass Sub2 doesn't belong to Class Letters.", () -> edit(good, alice, SUBCLASS, "Sub2"));
		allowed(() -> edit(good, alice, SUBCLASS, "Sub1"));
		allowed(() -> edit(good, alice, SUBCLASS, ""));
		assertEquals("", text(good, SUBCLASS));
	}

	@Test
	public void testCheckinSubclassMustBelongToClass() throws Exception {
		Document created = upload(documents, "checkin.txt", alice);
		DocumentManager.get().checkout(created.getId(), documentHistory(alice));

		assertRefused(SUBCLASS_MISMATCH, () -> checkin(created, alice, Map.of(SUBCLASS, "Sub1")));
		allowed(() -> checkin(created, alice, Map.of(CLASS, "Letters", SUBCLASS, "Sub1")));
		assertEquals("Sub1", text(created, SUBCLASS));
		assertEquals(Set.of(CLASS, SUBCLASS), locals(created));
	}

	@Test
	public void testOrdinaryStoreNeverVetoesTheSubclass() throws Exception {
		Document stored = reload(doc);
		stored.setValue(SUBCLASS, "Sub1");
		documentDao.store(stored);
		assertEquals("Sub1", text(doc, SUBCLASS));

		// A later ordinary store (as the indexer's) leaves it alone
		documentDao.store(reload(doc));
		assertEquals("Sub1", text(doc, SUBCLASS));
	}

	@Test
	public void testEditMakesTheValueLocalAndBackAgain() throws Exception {
		Document created = upload(documents, "new.txt", alice);

		edit(created, alice, CLASS, "Letters");
		assertEquals("Letters", text(created, CLASS));
		assertEquals(Set.of(CLASS), locals(created));

		// Back to what it would inherit
		edit(created, alice, CLASS, "Docs");
		assertEquals("Docs", text(created, CLASS));
		assertEquals(Set.of(), locals(created));
	}

	@Test
	public void testClearingOverrideInheritsAgain() throws Exception {
		setFolderValue(documents, CLASS, "Letters");
		Document created = createWith(documents, "override.txt", alice, Map.of(CLASS, "Docs"));
		assertEquals(Set.of(CLASS), locals(created));

		edit(created, alice, CLASS, "");
		assertEquals("Letters", text(created, CLASS));
		assertEquals(Set.of(), locals(created));
	}

	@Test
	public void testMoveRecalculatesTheValuesThatAreNotLocal() throws Exception {
		setFolderValue(documents, CLASS, "Letters");
		Document inherits = upload(correspondence, "inherits.txt", alice);
		Document overrides = createWith(correspondence, "overrides.txt", alice, Map.of(CLASS, "Letters"));
		Document local = createWith(correspondence, "local.txt", alice, Map.of(CLASS, "Financial"));
		setFolderValue(correspondence, CLASS, "Financial");
		// local now equals its folder; give it a different local value
		edit(local, alice, CLASS, "Docs");
		assertEquals(Set.of(CLASS), locals(local));

		for (Document d : List.of(inherits, overrides, local))
			DocumentManager.get().moveToFolder(reload(d), documents, documentHistory(alice));
		awaitAsyncWork();

		assertEquals("Letters", text(inherits, CLASS));
		assertEquals(Set.of(), locals(inherits));
		assertEquals("Letters", text(overrides, CLASS));
		assertEquals("Docs", text(local, CLASS));
		assertEquals(Set.of(CLASS), locals(local));
	}

	@Test
	public void testRestCreateWithoutClassGetsDocs() throws Exception {
		long[] id = new long[1];
		allowed(() -> id[0] = harness.restCreate(documents.getId(), "rest.txt", "rest".getBytes()));

		Document created = documentDao.findById(id[0], true);
		assertNotNull(created);
		assertEquals("Docs", text(created, CLASS));
		assertEquals("998", text(created, HealdanConstants.ATTR_CLIENT));
		assertEquals(Set.of(), locals(created));
	}

	@Test
	public void testClassDefaultRemovedFromOptions() throws Exception {
		AttributeOptionDAO.get().deleteBySetIdAndAttribute(set.getId(), CLASS);
		assertEquals(0, AttributeOptionDAO.get().findByAttribute(set.getId(), CLASS).size());

		Document created = upload(documents, "new.txt", alice);
		assertEquals("Docs", text(created, CLASS));
		assertEquals(Set.of(), locals(created));
	}

	private Document createWith(Folder folder, String fileName, User as, Map<String, Object> values)
			throws Exception {
		Document vo = documentVO(folder, fileName);
		values.forEach(vo::setValue);
		Document created = DocumentManager.get().create(content(), vo, documentHistory(as)).getDocument();
		awaitAsyncWork();
		return created;
	}

	/** Edits through CE's update, as the GUI and the web services do */
	private void edit(Document document, User as, String name, Object value) throws Exception {
		Document current = reload(document);
		Document vo = new Document(current);
		// The attribute objects are shared with the stored document
		vo.getAttributes().replaceAll((n, a) -> new Attribute(a));
		vo.setValue(name, value);
		DocumentManager.get().update(current, vo, documentHistory(as));
		awaitAsyncWork();
	}

	private void checkin(Document document, User as, Map<String, Object> values) throws Exception {
		Document vo = new Document(reload(document));
		vo.getAttributes().replaceAll((n, a) -> new Attribute(a));
		values.forEach(vo::setValue);
		java.io.File file = java.io.File.createTempFile("checkin", ".txt");
		try {
			java.nio.file.Files.writeString(file.toPath(), "new content");
			DocumentManager.get().checkin(document.getId(), file, document.getFileName(), false, vo,
					documentHistory(as));
		} finally {
			file.delete();
		}
	}

	private void setFolderValue(Folder folder, String name, String value) throws Exception {
		Folder current = reload(folder);
		current.setTemplate(ProfileSchema.get().template());
		current.setValue(name, value);
		folderDao.store(current);
	}

	private String text(Document document, String name) throws Exception {
		Attribute attribute = reload(document).getAttribute(name);
		return attribute != null ? attribute.getStringValue() : null;
	}

	private Set<String> locals(Document document) {
		return LocalValueDAO.get().locals(LocalValueDAO.DOCUMENT, document.getId());
	}
}
