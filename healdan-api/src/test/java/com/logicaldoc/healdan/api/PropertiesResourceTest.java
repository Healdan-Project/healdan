package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentStatus;
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
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.profile.LocalValueDAO;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.util.spring.Context;

/**
 * {@code GET} and {@code PATCH /v1/documents/{docNumber}/properties} (spec
 * section 6.7).
 * <p>
 * Clerk reads and writes in matter 998.1; Reader may read only; Outsider has
 * no grant. Boss is an admin.
 */
public class PropertiesResourceTest extends ApiTestCase {

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String SUBCLASS = HealdanConstants.ATTR_DOCSUBCLASS;

	private User clerk;

	private User reader;

	private User outsider;

	private User boss;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID,
				true);
		AttributeOptionDAO options = AttributeOptionDAO.get();
		options.store(new AttributeOption(set.getId(), CLASS, "Letters"));
		options.store(new AttributeOption(set.getId(), CLASS, "Memos"));
		options.store(new AttributeOption(set.getId(), SUBCLASS, "Sub1", "Letters"));
		options.store(new AttributeOption(set.getId(), SUBCLASS, "Sub2", "Docs"));
	}

	@Test
	public void testOverrideAndInheritAgain() throws Exception {
		setFolderValue(documents, CLASS, "Memos");
		Document d = file("a.docx");
		String token = signIn(clerk);

		ApiResponse read = get(properties(d)).token(token).send();
		assertEquals(read.toString(), 200, read.status());
		assertInherited(field(read, CLASS), "Memos");

		ApiResponse local = patch(properties(d)).token(token).json(values(CLASS, "Letters")).send();
		assertEquals(local.toString(), 200, local.status());
		JsonNode docclass = field(local, CLASS);
		assertEquals("Letters", docclass.path("value").textValue());
		assertEquals("local", docclass.path("source").textValue());
		assertTrue(docclass.path("inheritedFrom").isNull());
		assertEquals("local", field(get(properties(d)).token(token).send(), CLASS).path("source").textValue());

		// The inherited value inherits again
		assertInherited(field(patch(properties(d)).token(token).json(values(CLASS, "Memos")).send(), CLASS), "Memos");

		// So does null
		patch(properties(d)).token(token).json(values(CLASS, "Letters")).send();
		ApiResponse cleared = patch(properties(d)).token(token).json(values(CLASS, null)).send();
		assertEquals(cleared.toString(), 200, cleared.status());
		assertInherited(field(cleared, CLASS), "Memos");
		assertEquals(Set.of(), LocalValueDAO.get().locals(LocalValueDAO.DOCUMENT, d.getId()));
	}

	@Test
	public void testLegacyDocNumber() throws Exception {
		Document d = file("a.docx");
		// The publishing period must survive an edit of the properties
		Document stored = DocumentDAO.get().findById(d.getId(), true);
		Date stop = new Date(System.currentTimeMillis() + 86_400_000L * 30);
		stored.setStopPublishing(stop);
		DocumentDAO.get().store(stored);
		String publishing = "select ld_published, ld_startpublishing, ld_stoppublishing from ld_document where ld_id = ?";
		Map<String, Object> before = jdbc().queryForMap(publishing, d.getId());
		assertNotNull(before.get("LD_STOPPUBLISHING"));
		String token = signIn(clerk);

		JsonNode set = field(patch(properties(d)).token(token).json(values("legacydocnum", "OLD-1")).send(),
				"legacydocnum");
		assertEquals("OLD-1", set.path("value").textValue());
		assertEquals("local", set.path("source").textValue());
		JsonNode found = get("/api/v1/documents?legacyDocNumber=OLD-1").token(token).send().json().path("items");
		assertEquals(1, found.size());
		assertEquals(Long.parseLong(d.getCustomId()), found.get(0).path("docNumber").asLong());

		JsonNode cleared = field(patch(properties(d)).token(token).json(values("legacydocnum", null)).send(),
				"legacydocnum");
		assertTrue(cleared.path("value").isNull());
		assertTrue(cleared.path("source").isNull());
		assertEquals(0, get("/api/v1/documents?legacyDocNumber=OLD-1").token(token).send().json().path("items")
				.size());

		assertEquals(before, jdbc().queryForMap(publishing, d.getId()));
	}

	@Test
	public void testRefusals() throws Exception {
		Template profile = TemplateDAO.get().findById(ProfileSchema.get().template().getId(), true);
		Attribute court = new Attribute();
		court.setLabel("Court");
		court.setType(Attribute.Type.STRING);
		court.setMandatory(true);
		court.setPosition(13);
		profile.getTemplateAttributes().put("court", court);
		Attribute box = new Attribute();
		box.setLabel("Box");
		box.setType(Attribute.Type.FOLDER);
		box.setPosition(14);
		profile.getTemplateAttributes().put("box", box);
		TemplateDAO.get().store(profile);
		// Clerk has no grant on it
		Folder secret = fx.store("Private");
		Document d = file("a.docx");
		String token = signIn(clerk);
		// Clearing a required property that has a value, and inherits none, is refused
		assertEquals(200, patch(properties(d)).token(token).json(values("court", "High Court")).send().status());
		DbSnapshot before = fx.snapshot();

		assertRefused(d, token, values("colour", "red"), List.of("colour unknown"));
		assertRefused(d, token, values(HealdanConstants.ATTR_CLIENT, "5"), List.of("client read-only"));
		assertRefused(d, token, values(HealdanConstants.ATTR_EMAILSUBJECT, "Hi"), List.of("emailsubject read-only"));
		assertRefused(d, token, values(CLASS, 5), List.of("docclass type"));
		assertRefused(d, token, values(CLASS, "Lettres"), List.of("docclass not-an-option"));
		assertRefused(d, token, values(SUBCLASS, "Nonsense"), List.of("docsubclass not-an-option"));
		assertRefused(d, token, values(SUBCLASS, "Sub1"), List.of("docsubclass class-mismatch"));
		assertRefused(d, token, values(CLASS, "Letters", SUBCLASS, "Sub2"), List.of("docsubclass class-mismatch"));
		assertRefused(d, token, values(HealdanConstants.ATTR_AUTHOR, 99999), List.of("author unknown"));
		assertRefused(d, token, values(HealdanConstants.ATTR_AUTHOR, "boss"), List.of("author type"));
		assertRefused(d, token, values("court", null), List.of("court required"));
		assertRefused(d, token, values("court", ""), List.of("court required"));
		// A folder the caller can't read is answered as one that doesn't exist
		JsonNode unreadable = assertRefused(d, token, values("box", secret.getId()), List.of("box unknown"))
				.path("fields").get(0);
		JsonNode missing = assertRefused(d, token, values("box", 987654), List.of("box unknown")).path("fields")
				.get(0);
		assertEquals(missing.path("detail").textValue().replace("987654", "N"),
				unreadable.path("detail").textValue().replace(Long.toString(secret.getId()), "N"));

		// Every field that is wrong, in the order sent
		assertRefused(d, token,
				values("colour", "red", HealdanConstants.ATTR_CLIENT, "5", CLASS, 5, HealdanConstants.ATTR_AUTHOR,
						99999, "court", null),
				List.of("colour unknown", "client read-only", "docclass type", "author unknown", "court required"));

		before.assertUnchanged();

		// A body without the values map is malformed
		ApiResponse malformed = patch(properties(d)).token(token).json("{\"colour\": \"red\"}").send();
		assertEquals(malformed.toString(), 400, malformed.status());
		assertEquals("bad-request", malformed.code());
		before.assertUnchanged();

		// A folder the caller can read is taken
		JsonNode set = field(patch(properties(d)).token(token).json(values("box", documents.getId())).send(), "box");
		assertEquals(documents.getId(), set.path("value").asLong());
	}

	@Test
	public void testUnchangedFormIsIgnored() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);
		assertEquals(200, patch(properties(d)).token(token).json(values(CLASS, "Letters", "legacydocnum", "OLD-1"))
				.send().status());
		// Letters is then taken off the list
		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID);
		for (AttributeOption option : AttributeOptionDAO.get().findByAttribute(set.getId(), CLASS))
			if ("Letters".equals(option.getValue()))
				AttributeOptionDAO.get().delete(option.getId());

		JsonNode form = get(properties(d)).token(token).send().json().path("items");
		Map<String, Object> all = new LinkedHashMap<>();
		for (JsonNode field : form)
			all.put(field.path("name").textValue(), field.path("value"));
		DbSnapshot before = fx.snapshot();

		// Client, Matter and the email fields as they are, a Class no longer listed, Author as {id, name}
		ApiResponse response = patch(properties(d)).token(token).json(Map.of("values", all)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(form, response.json().path("items"));
		before.assertUnchanged();

		// A read-only property that is changed is still refused
		all.put(HealdanConstants.ATTR_CLIENT, "5");
		assertRefused(d, token, Map.of("values", all), List.of("client read-only"));
		before.assertUnchanged();
	}

	@Test
	public void testUserAndDateFields() throws Exception {
		Template profile = TemplateDAO.get().findById(ProfileSchema.get().template().getId(), true);
		Attribute hearing = new Attribute();
		hearing.setLabel("Hearing");
		hearing.setType(Attribute.Type.DATE);
		hearing.setPosition(13);
		profile.getTemplateAttributes().put("hearing", hearing);
		TemplateDAO.get().store(profile);
		Document d = file("a.docx");
		String token = signIn(clerk);

		ApiResponse response = patch(properties(d)).token(token)
				.json(values(HealdanConstants.ATTR_AUTHOR, boss.getId(), "hearing", "2026-01-31T00:00:00Z")).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode author = field(response, HealdanConstants.ATTR_AUTHOR);
		assertEquals(boss.getId(), author.path("value").path("id").asLong());
		assertEquals(boss.getFullName(), author.path("value").path("name").textValue());
		assertEquals("local", author.path("source").textValue());
		assertEquals(clerk.getId(),
				field(response, HealdanConstants.ATTR_TYPIST).path("value").path("id").asLong());
		JsonNode date = field(get(properties(d)).token(token).send(), "hearing");
		assertEquals("date", date.path("type").textValue());
		assertEquals("2026-01-31T00:00:00Z", date.path("value").textValue());
		assertEquals("local", date.path("source").textValue());

		// A time of day and an offset are kept, and written in UTC
		JsonNode later = field(patch(properties(d)).token(token).json(values("hearing", "2026-01-31T14:05:30+01:00"))
				.send(), "hearing");
		assertEquals("2026-01-31T13:05:30Z", later.path("value").textValue());
		assertRefused(d, token, values("hearing", "31/01/2026"), List.of("hearing type"));
	}

	@Test
	public void testPermissions() throws Exception {
		Document d = file("a.docx");
		String readerToken = signIn(reader);
		String outsiderToken = signIn(outsider);
		String clerkToken = signIn(clerk);
		String bossToken = signIn(boss);

		// The fields the clerk may change are shown so, and the others aren't
		ApiResponse free = get(properties(d)).token(clerkToken).send();
		assertTrue(field(free, CLASS).path("editable").booleanValue());
		assertFalse(field(free, HealdanConstants.ATTR_CLIENT).path("editable").booleanValue());

		ApiResponse read = get(properties(d)).token(readerToken).send();
		assertEquals(200, read.status());
		assertNoneEditable(read);
		ApiResponse forbidden = patch(properties(d)).token(readerToken).json(values(CLASS, "Letters")).send();
		assertEquals(forbidden.toString(), 403, forbidden.status());
		assertEquals("forbidden", forbidden.code());

		ApiResponse unreadable = get(properties(d)).token(outsiderToken).send();
		ApiResponse unknown = get("/api/v1/documents/987654/properties").token(outsiderToken).send();
		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals(unknown.text().replace("987654", "N"), unreadable.text().replace(d.getCustomId(), "N"));
		assertEquals(404, patch(properties(d)).token(outsiderToken).json(values(CLASS, "Letters")).send().status());

		// An immutable document is refused as check-out and save refuse it; CE lets an administrator change it
		Document stored = DocumentDAO.get().findById(d.getId(), true);
		stored.setImmutable(true);
		DocumentDAO.get().store(stored);
		ApiResponse immutable = patch(properties(d)).token(clerkToken).json(values(CLASS, "Letters")).send();
		assertEquals(immutable.toString(), 409, immutable.status());
		assertEquals("refused", immutable.code());
		assertEquals("Document #%s is immutable and can't be changed.".formatted(d.getCustomId()),
				immutable.json().path("detail").textValue());
		assertNoneEditable(get(properties(d)).token(clerkToken).send());
		assertTrue(field(get(properties(d)).token(bossToken).send(), CLASS).path("editable").booleanValue());
		assertEquals(200, patch(properties(d)).token(bossToken).json(values(HealdanConstants.ATTR_LEGACYDOCNUM, "OLD-1"))
				.send().status());
		stored = DocumentDAO.get().findById(d.getId(), true);
		stored.setImmutable(false);
		DocumentDAO.get().store(stored);

		// Someone else's check-out
		hold(d, boss, DocumentStatus.CHECKEDOUT);
		ApiResponse checkedOut = patch(properties(d)).token(clerkToken).json(values(CLASS, "Letters")).send();
		assertEquals(checkedOut.toString(), 409, checkedOut.status());
		assertEquals("checked-out", checkedOut.code());
		assertEquals("checkout", checkedOut.json().path("hold").path("kind").textValue());
		assertEquals(boss.getId(), checkedOut.json().path("hold").path("by").path("id").asLong());
		ApiResponse held = get(properties(d)).token(clerkToken).send();
		assertEquals("Docs", field(held, CLASS).path("value").textValue());
		assertNoneEditable(held);
		// The holder may edit
		assertTrue(field(get(properties(d)).token(bossToken).send(), CLASS).path("editable").booleanValue());
		assertEquals(200, patch(properties(d)).token(bossToken).json(values(CLASS, "Memos")).send().status());

		// Someone else's lock
		DocumentManager.get().unlock(d.getId(), fx.documentHistory(boss));
		hold(d, boss, DocumentStatus.LOCKED);
		ApiResponse locked = patch(properties(d)).token(clerkToken).json(values(CLASS, "Letters")).send();
		assertEquals(locked.toString(), 409, locked.status());
		assertEquals("locked", locked.code());
		assertNotNull(locked.json().path("hold").path("by"));
		ApiResponse lockedView = get(properties(d)).token(clerkToken).send();
		assertEquals("Memos", field(lockedView, CLASS).path("value").textValue());
		assertNoneEditable(lockedView);

		// A lock is a hold even for the user who took it, as for check-out and save (spec correction 10)
		DocumentManager.get().unlock(d.getId(), fx.documentHistory(boss));
		hold(d, clerk, DocumentStatus.LOCKED);
		DbSnapshot before = fx.snapshot();
		ApiResponse own = patch(properties(d)).token(clerkToken).json(values(CLASS, "Letters")).send();
		assertEquals(own.toString(), 409, own.status());
		assertEquals("locked", own.code());
		assertEquals("Document #%s is locked by you. Release it first.".formatted(d.getCustomId()),
				own.json().path("detail").textValue());
		assertNoneEditable(get(properties(d)).token(clerkToken).send());
		before.assertUnchanged();
	}

	@Test
	public void testProtected() throws Exception {
		Document d = file("a.docx");
		DocumentHistory history = fx.documentHistory(boss);
		history.setEvent(DocumentEvent.CHANGED);
		DocumentDAO.get().setPassword(d.getId(), "S3cret-pw!", history);
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		// As content, check-out and save refuse it
		ApiResponse refused = patch(properties(d)).token(token).json(values(CLASS, "Letters")).send();

		assertEquals(refused.toString(), 403, refused.status());
		assertEquals("forbidden", refused.code());
		assertEquals("Document #%s is protected by a password. Open it in Healdan's web page."
				.formatted(d.getCustomId()), refused.json().path("detail").textValue());
		before.assertUnchanged();
		// Its properties may still be read, and none is shown editable
		ApiResponse read = get(properties(d)).token(token).send();
		assertEquals(read.toString(), 200, read.status());
		assertNoneEditable(read);
	}

	@Test
	public void testMarkupRemoved() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);
		String legacy = HealdanConstants.ATTR_LEGACYDOCNUM;

		// As the old UI's Save cleans text (I1): the tag goes, the text stays
		JsonNode cleaned = field(patch(properties(d)).token(token)
				.json(values(legacy, "Re <img src=x onerror=alert(1)>Smith")).send(), legacy);
		assertEquals("Re Smith", cleaned.path("value").textValue());
		assertEquals("Re Smith", stringValue(d, legacy));
		// Sent again, it is the value the property has once cleaned: nothing is saved
		DbSnapshot before = fx.snapshot();
		ApiResponse again = patch(properties(d)).token(token).json(values(legacy, "Re <img src=x onerror=alert(1)>Smith"))
				.send();
		assertEquals(again.toString(), 200, again.status());
		before.assertUnchanged();

		// Text that only looks like markup is kept, as the old UI keeps it
		assertEquals("a < b & c",
				field(patch(properties(d)).token(token).json(values(legacy, "a < b & c")).send(), legacy).path("value")
						.textValue());
		assertEquals("a < b & c", stringValue(d, legacy));

		// A listed value is checked once it's cleaned
		assertEquals("Letters", field(patch(properties(d)).token(token)
				.json(values(CLASS, "<img src=x onerror=alert(1)>Letters")).send(), CLASS).path("value").textValue());
	}

	@Test
	public void testEmailFormSentBack() throws Exception {
		String eml = """
				From: "Ann Smith" <ann@example.com>
				To: bob@example.com
				Subject: Re: <urgent> plan
				Date: Tue, 14 Oct 2025 09:29:00 +0200
				MIME-Version: 1.0
				Content-Type: text/plain; charset=UTF-8

				Hello Bob.
				""".replace("\n", "\r\n");
		Document d = file("x.eml", eml);
		String token = signIn(clerk);
		JsonNode form = get(properties(d)).token(token).send().json().path("items");
		Map<String, Object> all = new LinkedHashMap<>();
		for (JsonNode field : form)
			all.put(field.path("name").textValue(), field.path("value"));
		// Values the message gave, which look like markup to the cleaning
		assertEquals("Ann Smith <ann@example.com>", ((JsonNode) all.get(HealdanConstants.ATTR_EMAILFROM)).textValue());
		DbSnapshot before = fx.snapshot();

		// Sent back as they are, they are the values the properties have: not refused, and nothing is saved
		ApiResponse response = patch(properties(d)).token(token).json(Map.of("values", all)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(form, response.json().path("items"));
		before.assertUnchanged();
	}

	@Test
	public void testTooLong() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);
		String legacy = HealdanConstants.ATTR_LEGACYDOCNUM;
		DbSnapshot before = fx.snapshot();

		JsonNode refused = assertRefused(d, token, values(legacy, "x".repeat(4001)), List.of("legacydocnum too-long"));

		assertEquals("Legacy doc # can't be longer than 4000 characters.",
				refused.path("fields").get(0).path("detail").textValue());
		before.assertUnchanged();
		// The length is the text's as it's saved, without its markup
		String longest = "x".repeat(4000);
		JsonNode taken = field(patch(properties(d)).token(token)
				.json(values(legacy, longest + "<img src=x onerror=alert(1)>")).send(), legacy);
		assertEquals(longest, taken.path("value").textValue());
	}

	@Test
	public void testWaitsForTheDocumentsLock() throws Exception {
		// M2: a PATCH runs after any other API request on the document, and sees what that request left
		Document d = file("a.docx");
		String token = signIn(clerk);
		DocumentLocks locks = Context.get(DocumentLocks.class);
		CountDownLatch held = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Object> other = pool.submit(() -> locks.withLock(d.getId(), () -> {
				held.countDown();
				// Long enough for the PATCH to arrive and wait
				Thread.sleep(1500L);
				hold(d, boss, DocumentStatus.CHECKEDOUT);
				return null;
			}));
			held.await();
			Future<ApiResponse> patched = pool
					.submit(() -> patch(properties(d)).token(token).json(values(CLASS, "Letters")).send());
			other.get(30, TimeUnit.SECONDS);
			ApiResponse response = patched.get(30, TimeUnit.SECONDS);

			assertEquals(response.toString(), 409, response.status());
			assertEquals("checked-out", response.code());
		} finally {
			pool.shutdownNow();
		}
		assertEquals("Docs", field(get(properties(d)).token(token).send(), CLASS).path("value").textValue());
	}

	@Test
	public void testChangePastABackgroundWrite() throws Exception {
		// The Office preview saves the document when its conversion ends, after the API read it to change it
		Document d = file("a.docx");
		String token = signIn(clerk);
		String legacy = HealdanConstants.ATTR_LEGACYDOCNUM;
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		ApiResponse response;
		try (BackgroundWrite conversion = BackgroundWrite.conversion(d.getId()).start()) {
			response = patch(properties(d)).token(token).json(values(legacy, "OLD-1")).send();
			conversion.assertWritten();
		}

		assertEquals(response.toString(), 200, response.status());
		assertEquals("OLD-1", field(response, legacy).path("value").textValue());
		assertEquals("OLD-1", stringValue(d, legacy));
		assertEquals(List.of("1.0", "1.1"), versions(d));
		assertEquals("1.1", DocumentDAO.get().findById(d.getId()).getVersion());
	}

	@Test
	public void testChangePastABackgroundWriteAtTheFlush() throws Exception {
		// The preview's save commits just before CE's UPDATE of the change, whose failure CE's DAO swallows: the change
		// answered 200 with the new value, which was never stored, and CE kept a version of it
		Document d = file("a.docx");
		String token = signIn(clerk);
		String legacy = HealdanConstants.ATTR_LEGACYDOCNUM;
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		ApiResponse response;
		try (BackgroundWrite conversion = BackgroundWrite.atFlush(d.getId()).start()) {
			response = patch(properties(d)).token(token).json(values(legacy, "OLD-1")).send();
			conversion.assertWritten();
		}

		assertEquals(response.toString(), 200, response.status());
		assertEquals("OLD-1", field(response, legacy).path("value").textValue());
		assertEquals("OLD-1", stringValue(d, legacy));
		assertEquals(List.of("1.0", "1.1"), versions(d));
		assertEquals("1.1", DocumentDAO.get().findById(d.getId()).getVersion());
	}

	private static void assertNoneEditable(ApiResponse response) {
		assertEquals(response.toString(), 200, response.status());
		for (JsonNode field : response.json().path("items"))
			assertFalse(field.toString(), field.path("editable").booleanValue());
	}

	private String stringValue(Document d, String name) {
		return jdbc().queryForObject("select ld_stringvalue from ld_document_ext where ld_docid = ? and ld_name = ?",
				String.class, d.getId(), name);
	}

	/** The document's versions that aren't deleted, oldest first */
	private List<String> versions(Document d) {
		return jdbc().queryForList("select ld_version from ld_version where ld_documentid = ? and ld_deleted = 0 order by ld_id",
				String.class, d.getId());
	}

	private JsonNode assertRefused(Document d, String token, Map<String, Object> values, List<String> expected) {
		ApiResponse refused = patch(properties(d)).token(token).json(values).send();
		assertEquals(refused.toString(), 422, refused.status());
		assertEquals("validation", refused.code());
		List<String> fields = new ArrayList<>();
		for (JsonNode field : refused.json().path("fields")) {
			fields.add(field.path("field").textValue() + " " + field.path("code").textValue());
			assertTrue(field.toString(), field.path("detail").textValue().length() > 0);
		}
		assertEquals(refused.toString(), expected, fields);
		return refused.json();
	}

	private void assertInherited(JsonNode field, String value) {
		assertEquals(field.toString(), value, field.path("value").textValue());
		assertEquals(field.toString(), "inherited", field.path("source").textValue());
		assertEquals(documents.getId(), field.path("inheritedFrom").path("id").asLong());
		assertEquals("Documents", field.path("inheritedFrom").path("name").textValue());
		assertEquals("folder", field.path("inheritedFrom").path("kind").textValue());
	}

	private void hold(Document d, User as, DocumentStatus status) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		if (status == DocumentStatus.CHECKEDOUT) {
			history.setEvent(DocumentEvent.CHECKEDOUT);
			DocumentManager.get().checkout(d.getId(), history);
		} else {
			history.setEvent(DocumentEvent.LOCKED);
			DocumentManager.get().lock(d.getId(), status, history);
		}
	}

	private Document file(String name) throws Exception {
		return file(name, "Healdan");
	}

	private Document file(String name, String content) throws Exception {
		Document d = fx.upload(documents, name, content, clerk);
		awaitAsyncWork();
		return d;
	}

	private static String properties(Document d) {
		return "/api/v1/documents/" + d.getCustomId() + "/properties";
	}

	/** A body, from names and values; a value may be null */
	private static Map<String, Object> values(Object... pairs) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
			values.put((String) pairs[i], pairs[i + 1]);
		return Map.of("values", values);
	}

	private static JsonNode field(ApiResponse response, String name) {
		assertEquals(response.toString(), 200, response.status());
		for (JsonNode field : response.json().path("items"))
			if (name.equals(field.path("name").textValue()))
				return field;
		throw new AssertionError("No field " + name + " in " + response);
	}

	/** As the old UI's folder edit does: the listener records the local value and passes it down */
	private void setFolderValue(Folder folder, String name, String value) throws Exception {
		Folder current = FolderDAO.get().findById(folder.getId(), true);
		current.setValue(name, value);
		FolderHistory transaction = fx.folderHistory(fx.admin());
		transaction.setEvent(FolderEvent.CHANGED);
		FolderDAO.get().store(current, transaction);
	}
}
