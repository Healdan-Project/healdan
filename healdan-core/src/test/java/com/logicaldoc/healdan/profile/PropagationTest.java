package com.logicaldoc.healdan.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
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
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;

/**
 * Spec §6.7: folders carry the Healdan profile too, a folder's local value
 * passes down to every folder and document below that has none of its own,
 * and a move or a restore re-stamps the whole subtree from its new place.
 * Propagation writes the values with SQL: no versions, no history, but the
 * documents it changes are indexed again.
 */
public class PropagationTest extends AbstractRulesTestCase {

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String SUBCLASS = HealdanConstants.ATTR_DOCSUBCLASS;

	private static final String CLIENT = HealdanConstants.ATTR_CLIENT;

	private static final String MATTER = HealdanConstants.ATTR_MATTER;

	@Before
	public void createOptions() throws Exception {
		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID,
				true);
		AttributeOptionDAO options = AttributeOptionDAO.get();
		options.store(new AttributeOption(set.getId(), CLASS, "Letters"));
		options.store(new AttributeOption(set.getId(), SUBCLASS, "Sub1", "Letters"));
		options.store(new AttributeOption(set.getId(), SUBCLASS, "Sub2", "Docs"));
	}

	@Test
	public void testFoldersAreStamped() throws Exception {
		Folder created = fx.folder(documents, "New");
		Folder stamped = reload(created);
		assertEquals(HealdanConstants.PROFILE_TEMPLATE, stamped.getTemplate().getName());
		assertTrue(stamped.isTemplateLocked());
		assertTrue(stamped.getAttributeNames().containsAll(ProfileSchema.get().template().getAttributeNames()));
		assertEquals("Docs", text(created, CLASS));
		assertEquals("998", text(created, CLIENT));
		assertEquals("1", text(created, MATTER));
		assertEquals(Set.of(), locals(created));

		// The matter's own folders, created before its row existed, too
		for (Folder folder : List.of(workspace, documents, correspondence, search)) {
			assertEquals(folder.getName(), "998", text(folder, CLIENT));
			assertEquals(folder.getName(), "1", text(folder, MATTER));
			assertEquals(folder.getName(), "Docs", text(folder, CLASS));
		}

		// A value that differs from the parent's is the folder's own; an equal one inherits
		Folder letters = create(documents, "Letters", Map.of(CLASS, "Letters"), alice);
		assertEquals("Letters", text(letters, CLASS));
		assertEquals(Set.of(CLASS), locals(letters));
		Folder same = create(documents, "Same", Map.of(CLASS, "Docs"), alice);
		assertEquals(Set.of(), locals(same));
		Folder inside = fx.folder(letters, "Inside");
		assertEquals("Letters", text(inside, CLASS));
		assertEquals(Set.of(), locals(inside));
	}

	@Test
	public void testTemplateFoldersAreStampedWithoutClientAndMatter() throws Exception {
		Folder invoices = fx.folder(template, "Invoices");
		assertEquals(HealdanConstants.PROFILE_TEMPLATE, reload(invoices).getTemplate().getName());
		assertEquals("Docs", text(invoices, CLASS));
		assertBlank(text(invoices, CLIENT));
		assertBlank(text(invoices, MATTER));

		// A template folder's own values are recorded, for the matters made from it
		edit(invoices, admin, CLASS, "Financial");
		assertEquals(Set.of(CLASS), locals(invoices));
		Folder paid = fx.folder(invoices, "Paid");
		assertEquals("Financial", text(paid, CLASS));
		assertEquals(Set.of(), locals(paid));
	}

	@Test
	public void testFolderEditMakesTheValueLocalAndBackAgain() throws Exception {
		edit(documents, alice, CLASS, "Letters");
		assertEquals("Letters", text(documents, CLASS));
		assertEquals(Set.of(CLASS), locals(documents));

		edit(documents, alice, CLASS, "Docs");
		assertEquals("Docs", text(documents, CLASS));
		assertEquals(Set.of(), locals(documents));

		edit(documents, alice, CLASS, "Letters");
		edit(documents, alice, CLASS, "");
		assertEquals("Docs", text(documents, CLASS));
		assertEquals(Set.of(), locals(documents));
	}

	@Test
	public void testClientMatterOfFoldersCannotBeEdited() throws Exception {
		edit(documents, alice, Map.of(CLIENT, "5", MATTER, ""));
		assertEquals("998", text(documents, CLIENT));
		assertEquals("1", text(documents, MATTER));
		assertEquals(Set.of(), locals(documents));

		// The workspace's come from the matter
		edit(workspace, admin, CLIENT, "5");
		assertEquals("998", text(workspace, CLIENT));
		assertEquals("998", text(documents, CLIENT));
	}

	@Test
	public void testOnlyAnEditChangesTheFolderMarks() throws Exception {
		edit(documents, alice, CLASS, "Letters");
		edit(workspace, admin, CLASS, "Letters");
		// Documents' own value now equals what it would inherit; stores that aren't edits keep the mark
		assertEquals(Set.of(CLASS), locals(documents));
		fx.grant(documents, lawyers, Permission.READ, Permission.WRITE);
		Folder renamed = reload(documents);
		renamed.setName("Papers");
		FolderHistory rename = folderHistory(admin);
		rename.setEvent(FolderEvent.RENAMED);
		folderDao.store(renamed, rename);
		assertEquals(Set.of(CLASS), locals(documents));

		// An edit that leaves it equal makes it inherit
		edit(documents, alice, Map.of());
		assertEquals(Set.of(), locals(documents));
	}

	@Test
	public void testFolderSubclassMustBelongToClass() throws Exception {
		assertRefused("Subclass Sub1 doesn't belong to Class Docs.", () -> edit(documents, alice, SUBCLASS, "Sub1"));
		assertRefused("Subclass Sub2 doesn't belong to Class Letters.",
				() -> create(documents, "Bad", Map.of(CLASS, "Letters", SUBCLASS, "Sub2"), alice));

		allowed(() -> edit(documents, alice, Map.of(CLASS, "Letters", SUBCLASS, "Sub1")));
		assertEquals(Set.of(CLASS, SUBCLASS), locals(documents));

		// An inherited Subclass that doesn't fit is cleared, never refused
		Folder sub = fx.folder(documents, "Sub");
		assertEquals("Sub1", text(sub, SUBCLASS));
		edit(sub, alice, CLASS, "Docs");
		assertEquals("Docs", text(sub, CLASS));
		assertBlank(text(sub, SUBCLASS));
		assertEquals(Set.of(CLASS), locals(sub));
	}

	@Test
	public void testFolderLocalChangeReachesNonLocalDescendants() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		Folder deeper = fx.folder(sub, "Deeper");
		Document inDocuments = upload(documents, "a.txt", alice);
		Document inSub = upload(sub, "b.txt", alice);
		Document inDeeper = upload(deeper, "c.txt", alice);
		Document elsewhere = upload(correspondence, "d.txt", alice);

		edit(documents, alice, CLASS, "Letters");

		for (Folder folder : List.of(sub, deeper)) {
			assertEquals(folder.getName(), "Letters", text(folder, CLASS));
			assertEquals(Set.of(), locals(folder));
		}
		for (Document document : List.of(inDocuments, inSub, inDeeper)) {
			assertEquals(document.getFileName(), "Letters", text(document, CLASS));
			assertEquals(Set.of(), locals(document));
		}
		assertEquals("Docs", text(elsewhere, CLASS));
		assertEquals("Docs", text(correspondence, CLASS));

		// A document stored afterwards keeps it
		documentDao.store(reload(inDeeper));
		assertEquals("Letters", text(inDeeper, CLASS));
	}

	@Test
	public void testLocalDescendantStopsPropagation() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		edit(sub, alice, CLASS, "Financial");
		Folder deeper = fx.folder(sub, "Deeper");
		Document inDeeper = upload(deeper, "a.txt", alice);
		Document override = createWith(documents, "override.txt", alice, Map.of(CLASS, "Financial"));
		Document plain = upload(documents, "plain.txt", alice);

		edit(documents, alice, CLASS, "Letters");

		assertEquals("Financial", text(sub, CLASS));
		assertEquals(Set.of(CLASS), locals(sub));
		assertEquals("Financial", text(deeper, CLASS));
		assertEquals("Financial", text(inDeeper, CLASS));
		assertEquals("Financial", text(override, CLASS));
		assertEquals(Set.of(CLASS), locals(override));
		assertEquals("Letters", text(plain, CLASS));
	}

	@Test
	public void testRemovingFolderLocalRestoresInheritedBelow() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		Document inSub = upload(sub, "a.txt", alice);
		edit(documents, alice, CLASS, "Letters");
		assertEquals("Letters", text(inSub, CLASS));

		edit(documents, alice, CLASS, "");

		assertEquals("Docs", text(documents, CLASS));
		assertEquals(Set.of(), locals(documents));
		assertEquals("Docs", text(sub, CLASS));
		assertEquals("Docs", text(inSub, CLASS));

		// The same when a folder further up passes another value down
		edit(workspace, admin, CLASS, "Financial");
		edit(documents, alice, CLASS, "Letters");
		edit(documents, alice, CLASS, "Financial");
		assertEquals(Set.of(), locals(documents));
		assertEquals("Financial", text(inSub, CLASS));
		edit(workspace, admin, CLASS, "");
		assertEquals("Docs", text(inSub, CLASS));
	}

	@Test
	public void testDocumentMoveRestamps() throws Exception {
		edit(documents, alice, CLASS, "Letters");
		Document moving = upload(correspondence, "moving.txt", alice);
		assertEquals("Docs", text(moving, CLASS));

		DocumentManager.get().moveToFolder(reload(moving), documents, documentHistory(alice));
		awaitAsyncWork();
		assertEquals("Letters", text(moving, CLASS));
		assertEquals(Set.of(), locals(moving));

		Matter other = fx.matter(fx.client(5, "Other"), 7, "Other Matter", store, "Documents");
		Folder target = child(folderDao.findById(other.getFolderId(), true), "Documents");
		DocumentManager.get().moveToFolder(reload(moving), target, documentHistory(admin));
		awaitAsyncWork();
		assertEquals("Docs", text(moving, CLASS));
		assertEquals("5", text(moving, CLIENT));
		assertEquals("7", text(moving, MATTER));
	}

	@Test
	public void testFolderMoveRestampsSubtreeIncludingClientMatter() throws Exception {
		Folder moving = fx.folder(correspondence, "Moving");
		Folder inner = fx.folder(moving, "Inner");
		Document a = upload(moving, "a.txt", alice);
		Document b = upload(inner, "b.txt", alice);
		Document own = createWith(inner, "own.txt", alice, Map.of(CLASS, "Financial"));
		Matter other = fx.matter(fx.client(5, "Other"), 7, "Other Matter", store, "Documents");
		Folder target = child(folderDao.findById(other.getFolderId(), true), "Documents");
		edit(target, admin, CLASS, "Letters");

		folderDao.move(reload(moving), target, folderHistory(admin));
		awaitAsyncWork();

		assertEquals(Long.valueOf(target.getId()),
				jdbc.queryForObject("select ld_parentid from ld_folder where ld_id = ?", Long.class, moving.getId()));
		for (Folder folder : List.of(moving, inner)) {
			assertEquals(folder.getName(), "5", text(folder, CLIENT));
			assertEquals(folder.getName(), "7", text(folder, MATTER));
			assertEquals(folder.getName(), "Letters", text(folder, CLASS));
			assertEquals(Set.of(), locals(folder));
		}
		for (Document document : List.of(a, b)) {
			assertEquals(document.getFileName(), "5", text(document, CLIENT));
			assertEquals(document.getFileName(), "7", text(document, MATTER));
			assertEquals(document.getFileName(), "Letters", text(document, CLASS));
		}
		assertEquals("5", text(own, CLIENT));
		assertEquals("Financial", text(own, CLASS));
		assertEquals(Set.of(CLASS), locals(own));
	}

	@Test
	public void testMovedFolderClearsALocalSubclassThatBecomesForeign() throws Exception {
		Folder foreign = create(correspondence, "Foreign", Map.of(SUBCLASS, "Sub2"), alice);
		Folder fitting = create(correspondence, "Fitting", Map.of(SUBCLASS, "Sub2"), alice);
		assertEquals(Set.of(SUBCLASS), locals(foreign));
		Folder letters = create(documents, "Letters", Map.of(CLASS, "Letters"), alice);
		Folder sub1 = create(documents, "Sub1", Map.of(CLASS, "Letters", SUBCLASS, "Sub1"), alice);

		folderDao.move(reload(foreign), letters, folderHistory(alice));
		folderDao.move(reload(fitting), sub1, folderHistory(alice));
		awaitAsyncWork();

		assertEquals("Letters", text(foreign, CLASS));
		assertBlank(text(foreign, SUBCLASS));
		assertEquals(Set.of(), locals(foreign));
		// The target's Subclass, inherited
		assertEquals("Sub1", text(fitting, SUBCLASS));
		assertEquals(Set.of(), locals(fitting));
	}

	@Test
	public void testRestoreIntoOtherMatterRestamps() throws Exception {
		Folder old = fx.folder(correspondence, "Old");
		Folder inner = fx.folder(old, "Inner");
		Document a = upload(old, "a.txt", alice);
		Document b = upload(inner, "b.txt", alice);
		Matter other = fx.matter(fx.client(5, "Other"), 7, "Other Matter", store, "Documents");
		Folder target = child(folderDao.findById(other.getFolderId(), true), "Documents");
		edit(target, admin, CLASS, "Letters");

		allowed(() -> folderDao.deleteTree(old.getId(), folderHistory(admin)));
		allowed(() -> folderDao.restore(old.getId(), target.getId(), folderHistory(admin)));

		assertEquals(target.getId(), reload(old).getParentId());
		for (Folder folder : List.of(old, inner)) {
			assertEquals(folder.getName(), "5", text(folder, CLIENT));
			assertEquals(folder.getName(), "7", text(folder, MATTER));
			assertEquals(folder.getName(), "Letters", text(folder, CLASS));
		}
		for (Document document : List.of(a, b)) {
			assertEquals(document.getFileName(), "5", text(document, CLIENT));
			assertEquals(document.getFileName(), "7", text(document, MATTER));
			assertEquals(document.getFileName(), "Letters", text(document, CLASS));
		}
	}

	@Test
	public void testClassChangeClearsForeignSubclass() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		Document override = createWith(sub, "override.txt", alice, Map.of(CLASS, "Letters", SUBCLASS, "Sub1"));
		assertEquals(Set.of(CLASS, SUBCLASS), locals(override));
		edit(workspace, admin, CLASS, "Letters");
		edit(documents, alice, SUBCLASS, "Sub1");
		Document inSub = upload(sub, "a.txt", alice);
		assertEquals("Sub1", text(inSub, SUBCLASS));
		assertEquals("Sub1", text(sub, SUBCLASS));

		edit(workspace, admin, CLASS, "Docs");

		// Below, the inherited Subclass no longer belongs and goes
		assertEquals("Docs", text(sub, CLASS));
		assertBlank(text(sub, SUBCLASS));
		assertEquals("Docs", text(inSub, CLASS));
		assertBlank(text(inSub, SUBCLASS));
		// A document's own Class and Subclass stay
		assertEquals("Letters", text(override, CLASS));
		assertEquals("Sub1", text(override, SUBCLASS));
		// Documents' own Subclass is its own: only a value that isn't local is cleared (plan, R20)
		assertEquals("Docs", text(documents, CLASS));
		assertEquals(Set.of(SUBCLASS), locals(documents));
	}

	@Test
	public void testAttributeAddedLaterInherits() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		Document inDocuments = upload(documents, "a.txt", alice);
		Document inSub = upload(sub, "b.txt", alice);

		Template profile = TemplateDAO.get().findById(ProfileSchema.get().template().getId(), true);
		Attribute region = new Attribute();
		region.setLabel("Region");
		region.setType(Attribute.TYPE_STRING);
		region.setPosition(20);
		profile.getTemplateAttributes().put("region", region);
		TemplateDAO.get().store(profile);
		assertEquals(0, jdbc.queryForObject(
				"select count(*) from ld_document_ext where ld_docid = ? and ld_name = 'region'", Integer.class,
				inSub.getId()).intValue());

		edit(documents, alice, "region", "EU");

		assertEquals(Set.of("region"), locals(documents));
		assertEquals("EU", text(sub, "region"));
		assertEquals("EU", text(inDocuments, "region"));
		assertEquals("EU", text(inSub, "region"));
		assertEquals(Set.of(), locals(inSub));
		assertEquals(Integer.valueOf(Attribute.TYPE_STRING), jdbc.queryForObject(
				"select ld_type from ld_document_ext where ld_docid = ? and ld_name = 'region'", Integer.class,
				inSub.getId()));
		assertEquals("EU", text(upload(sub, "c.txt", alice), "region"));
	}

	@Test
	public void testPropagationCreatesNoVersionsOrHistory() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		Document inSub = upload(sub, "a.txt", alice);
		upload(documents, "b.txt", alice);
		awaitAsyncWork();
		long versions = count("ld_version");
		long history = count("ld_history");

		edit(documents, alice, CLASS, "Letters");
		awaitAsyncWork();

		assertEquals("Letters", text(inSub, CLASS));
		assertEquals(versions, count("ld_version"));
		assertEquals(history, count("ld_history"));
	}

	@Test
	public void testPropagationFlagsReindex() throws Exception {
		Document override = createWith(correspondence, "override.txt", alice, Map.of(CLASS, "Letters"));
		Document stored = reload(override);
		stored.setIndexingStatus(IndexingStatus.INDEXED);
		documentDao.store(stored);
		assertEquals(IndexingStatus.INDEXED, reload(doc).getIndexingStatus());

		edit(correspondence, alice, CLASS, "Letters");

		assertEquals("Letters", text(doc, CLASS));
		assertEquals(IndexingStatus.TO_INDEX, reload(doc).getIndexingStatus());
		assertEquals(Integer.valueOf(IndexingStatus.TO_INDEX.ordinal()),
				jdbc.queryForObject("select ld_indexed from ld_document where ld_id = ?", Integer.class, doc.getId()));
		// Nothing changed on the override: it stays indexed
		assertEquals(IndexingStatus.INDEXED, reload(override).getIndexingStatus());
	}

	@Test
	public void testRestampSubtreeStampsClientAndMatterFromTheMatterRow() throws Exception {
		Folder[] created = new Folder[3];
		Document[] inside = new Document[1];
		HealdanOperation.run(HealdanOperation.Kind.MATTER_CREATE, () -> {
			Folder vo = new Folder(MatterDisplay.format(998, 2, "Second"));
			vo.setTenantId(Tenant.DEFAULT_ID);
			created[0] = folderDao.create(store, vo, true, folderHistory(admin));
			created[1] = fx.folder(created[0], "Top");
			created[2] = fx.folder(created[1], "Inner");
			inside[0] = upload(created[2], "a.txt", admin);
		});
		// No matter row yet: client and matter are left alone
		assertBlank(text(created[1], CLIENT));
		assertBlank(text(inside[0], CLIENT));

		Matter matter = new Matter();
		matter.setClientId(this.matter.getClientId());
		matter.setNumber(2);
		matter.setName("Second");
		matter.setFolderId(created[0].getId());
		matter.setOwnerId(admin.getId());
		MatterDAO.get().store(matter);
		Propagation.get().restampSubtree(created[0].getId());

		for (Folder folder : created) {
			assertEquals(folder.getName(), "998", text(folder, CLIENT));
			assertEquals(folder.getName(), "2", text(folder, MATTER));
		}
		assertEquals("998", text(inside[0], CLIENT));
		assertEquals("2", text(inside[0], MATTER));
		assertFalse(locals(inside[0]).contains(CLIENT));
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	/** Edits a folder's metadata as CE's folder save does */
	private void edit(Folder folder, User as, String name, Object value) throws Exception {
		edit(folder, as, Map.of(name, value));
	}

	private void edit(Folder folder, User as, Map<String, Object> values) throws Exception {
		Folder current = reload(folder);
		values.forEach(current::setValue);
		FolderHistory transaction = folderHistory(as);
		transaction.setEvent(FolderEvent.CHANGED);
		folderDao.store(current, transaction);
		awaitAsyncWork();
	}

	private Folder create(Folder parent, String name, Map<String, Object> values, User as) throws Exception {
		Folder vo = folderVO(name, Folder.TYPE_DEFAULT);
		vo.setTemplate(ProfileSchema.get().template());
		values.forEach(vo::setValue);
		return folderDao.create(reload(parent), vo, true, folderHistory(as));
	}

	private Document createWith(Folder folder, String fileName, User as, Map<String, Object> values)
			throws Exception {
		Document vo = documentVO(folder, fileName);
		values.forEach(vo::setValue);
		Document created = DocumentManager.get().create(content(), vo, documentHistory(as)).getDocument();
		awaitAsyncWork();
		return created;
	}

	private String text(Folder folder, String name) throws Exception {
		Attribute attribute = reload(folder).getAttribute(name);
		return attribute != null ? attribute.getStringValue() : null;
	}

	private String text(Document document, String name) throws Exception {
		Attribute attribute = reload(document).getAttribute(name);
		return attribute != null ? attribute.getStringValue() : null;
	}

	private Set<String> locals(Folder folder) {
		return LocalValueDAO.get().locals(LocalValueDAO.FOLDER, folder.getId());
	}

	private Set<String> locals(Document document) {
		return LocalValueDAO.get().locals(LocalValueDAO.DOCUMENT, document.getId());
	}

	private static void assertBlank(String value) {
		assertTrue("[" + value + "]", value == null || value.isEmpty());
	}
}
