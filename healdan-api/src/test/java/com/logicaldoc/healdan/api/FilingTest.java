package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.logicaldoc.healdan.docnumber.DocNumberCounter;
import com.logicaldoc.healdan.profile.LocalValueDAO;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * {@code POST /v1/documents}: filing a new document (spec section 6.5).
 * <p>
 * Clerk reads and writes in matter 998.1; Reader may read only; Outsider has
 * no grant.
 */
public class FilingTest extends ApiTestCase {

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String SUBCLASS = HealdanConstants.ATTR_DOCSUBCLASS;

	private static final String MAX_SIZE = Tenant.DEFAULT_NAME + ".upload.maxsize";

	private static final String DISALLOW = Tenant.DEFAULT_NAME + ".upload.disallow";

	private static final byte[] CONTENT = "Lease draft".getBytes(StandardCharsets.UTF_8);

	private final Map<String, String> savedSettings = new LinkedHashMap<>();

	private User clerk;

	private User reader;

	private User outsider;

	private Folder store;

	private Folder workspace;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		ContextProperties config = Context.get().getConfig();
		for (String key : List.of(MAX_SIZE, DISALLOW))
			savedSettings.put(key, config.getProperty(key));

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID,
				true);
		AttributeOptionDAO options = AttributeOptionDAO.get();
		options.store(new AttributeOption(set.getId(), CLASS, "Letters"));
		options.store(new AttributeOption(set.getId(), CLASS, "Memos"));
		options.store(new AttributeOption(set.getId(), SUBCLASS, "Sub1", "Letters"));
	}

	@Override
	public void tearDown() throws IOException {
		ContextProperties config = Context.get().getConfig();
		savedSettings.forEach((key, value) -> {
			if (value == null)
				config.remove(key);
			else
				config.setProperty(key, value);
		});
		super.tearDown();
	}

	@Test
	public void testFiles() throws Exception {
		setFolderValue(documents, CLASS, "Memos");
		long next = DocNumberCounter.get().peekNext();
		Instant before = Instant.now();

		ApiResponse response = file(signIn(clerk), document(documents, null, null, null), "Lease draft.docx",
				CONTENT);

		assertEquals(response.toString(), 201, response.status());
		JsonNode doc = response.json();
		assertEquals("document", doc.path("type").textValue());
		assertEquals(next, doc.path("docNumber").asLong());
		assertEquals("/api/v1/documents/" + next, response.header("Location"));
		assertEquals("Lease draft.docx", doc.path("name").textValue());
		assertEquals(CONTENT.length, doc.path("size").asLong());
		assertEquals("1.0", doc.path("version").textValue());
		assertEquals("1.0", doc.path("fileVersion").textValue());
		assertEquals(documents.getId(), doc.path("folder").path("id").asLong());
		assertEquals("998.1 \u2013 Demo", doc.path("matter").path("display").textValue());
		assertTrue(doc.path("hold").isNull());
		assertTrue(doc.path("canEdit").booleanValue());
		Instant filed = Instant.parse(doc.path("dateFiled").textValue());
		assertTrue(filed.toString(), Duration.between(before, filed).getSeconds() > -2
				&& Duration.between(filed, Instant.now()).getSeconds() < 60);

		JsonNode properties = get("/api/v1/documents/" + next + "/properties").token(signIn(clerk)).send().json()
				.path("items");
		JsonNode docclass = field(properties, CLASS);
		assertEquals("Memos", docclass.path("value").textValue());
		assertEquals("inherited", docclass.path("source").textValue());
		assertEquals(documents.getId(), docclass.path("inheritedFrom").path("id").asLong());
		assertEquals(clerk.getId(), field(properties, HealdanConstants.ATTR_AUTHOR).path("value").path("id").asLong());
		assertEquals(clerk.getId(), field(properties, HealdanConstants.ATTR_TYPIST).path("value").path("id").asLong());

		ApiResponse content = get("/api/v1/documents/" + next + "/content").token(signIn(clerk)).send();
		assertArrayEquals(CONTENT, content.body());
		assertEquals(1, jdbc().queryForObject(
				"select count(*) from ld_history where ld_event = 'event.stored' and ld_docid = (select ld_id from ld_document where ld_customid = ?)",
				Integer.class, Long.toString(next)).intValue());
	}

	@Test
	public void testOverrideOnCreate() throws Exception {
		setFolderValue(documents, CLASS, "Memos");

		ApiResponse response = file(signIn(clerk), document(documents, null, Map.of(CLASS, "Letters"), null), "a.docx",
				CONTENT);

		assertEquals(response.toString(), 201, response.status());
		long docNumber = response.json().path("docNumber").asLong();
		JsonNode docclass = field(get("/api/v1/documents/" + docNumber + "/properties").token(signIn(clerk)).send()
				.json().path("items"), CLASS);
		assertEquals("Letters", docclass.path("value").textValue());
		assertEquals("local", docclass.path("source").textValue());
		long id = jdbc().queryForObject("select ld_id from ld_document where ld_customid = ?", Long.class,
				Long.toString(docNumber));
		assertTrue(LocalValueDAO.get().locals(LocalValueDAO.DOCUMENT, id).contains(CLASS));
	}

	@Test
	public void testCheckOut() throws Exception {
		ApiResponse response = file(signIn(clerk), document(documents, null, null, true), "a.docx", CONTENT);

		assertEquals(response.toString(), 201, response.status());
		JsonNode hold = response.json().path("hold");
		assertEquals("checkout", hold.path("kind").textValue());
		assertEquals(clerk.getId(), hold.path("by").path("id").asLong());
		assertTrue(response.json().path("canEdit").booleanValue());
		assertEquals(1, jdbc().queryForObject(
				"select count(*) from ld_history where ld_event = 'event.checkedout' and ld_userid = ?", Integer.class,
				clerk.getId()).intValue());
	}

	@Test
	public void testFilingRule() throws Exception {
		Folder search = fx.searchFolder(documents, "Recent");
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		for (Folder folder : List.of(workspace, search, store)) {
			ApiResponse refused = file(token, document(folder, null, null, null), "a.docx", CONTENT);
			assertEquals(refused.toString(), 409, refused.status());
			assertEquals("filing-rule", refused.code());
		}
		assertEquals("Documents can't be saved at the top level of a matter. Choose one of its folders.",
				file(token, document(workspace, null, null, null), "a.docx", CONTENT).json().path("detail")
						.textValue());
		before.assertUnchanged();
	}

	@Test
	public void testPermissions() throws Exception {
		String readerToken = signIn(reader);
		String token = signIn(outsider);
		DbSnapshot before = fx.snapshot();

		ApiResponse forbidden = file(readerToken, document(documents, null, null, null), "a.docx", CONTENT);
		assertEquals(forbidden.toString(), 403, forbidden.status());
		assertEquals("forbidden", forbidden.code());

		ApiResponse unreadable = file(token, document(documents, null, null, null), "a.docx", CONTENT);
		ApiResponse unknown = file(token, "{\"folderId\": 987654}", "a.docx", CONTENT);
		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals("not-found", unreadable.code());
		assertEquals(404, unknown.status());
		assertEquals(unknown.text().replace("987654", "N"),
				unreadable.text().replace(Long.toString(documents.getId()), "N"));
		before.assertUnchanged();
	}

	@Test
	public void testNothingStoredOnRefusal() throws Exception {
		Template profile = TemplateDAO.get().findById(ProfileSchema.get().template().getId(), true);
		Attribute court = new Attribute();
		court.setLabel("Court");
		court.setType(Attribute.TYPE_STRING);
		court.setMandatory(true);
		court.setPosition(13);
		profile.getTemplateAttributes().put("court", court);
		TemplateDAO.get().store(profile);
		String token = signIn(clerk);
		int documentsBefore = count(documents);
		DbSnapshot before = fx.snapshot();

		assertRefused(token, document(documents, null, null, null), List.of("court required"));
		assertRefused(token, document(documents, null, values("court", ""), null), List.of("court required"));
		assertRefused(token, document(documents, null, values("court", "High Court", "colour", "red"), null),
				List.of("colour unknown"));
		assertRefused(token, document(documents, null, values("court", "High Court", HealdanConstants.ATTR_CLIENT,
				"5", HealdanConstants.ATTR_EMAILSUBJECT, "Hi"), null),
				List.of("client read-only", "emailsubject read-only"));
		assertRefused(token, document(documents, null, values("court", "High Court", CLASS, "Lettres"), null),
				List.of("docclass not-an-option"));
		// Sub1 belongs to Letters, not to the default Class
		assertRefused(token, document(documents, null, values("court", "High Court", SUBCLASS, "Sub1"), null),
				List.of("docsubclass class-mismatch"));
		assertRefused(token, document(documents, null, values("court", "High Court", HealdanConstants.ATTR_AUTHOR,
				99999), null), List.of("author unknown"));
		// One answer lists the name, the file and the properties
		ApiResponse all = post("/api/v1/documents").token(token).part("document",
				document(documents, "a/b.docx", values(CLASS, 5), null)).file("file", "a.docx", new byte[0]).send();
		assertEquals(List.of("name invalid", "file empty", "docclass type", "court required"), fields(all));

		before.assertUnchanged();
		assertEquals(documentsBefore, count(documents));

		// What the form shows, sent back as it is, is taken
		JsonNode form = get("/api/v1/folders/" + documents.getId() + "/form").token(token).send().json()
				.path("items");
		Map<String, Object> sent = new LinkedHashMap<>();
		for (JsonNode field : form)
			sent.put(field.path("name").textValue(), field.path("value"));
		sent.put("court", "High Court");
		ApiResponse filed = file(token, document(documents, null, sent, null), "a.docx", CONTENT);
		assertEquals(filed.toString(), 201, filed.status());
	}

	@Test
	public void testFileRules() throws Exception {
		String token = signIn(clerk);
		String outsiderToken = signIn(outsider);
		DbSnapshot before = fx.snapshot();
		Set<String> temporaryFiles = temporaryFiles();

		assertRefused(token, document(documents, null, null, null), "a.docx", new byte[0], List.of("file empty"));

		// The limit is read for each request, so it follows the setting. CXF itself stops reading far over it
		Context.get().getConfig().setProperty(MAX_SIZE, "1");
		for (int size : new int[] { 1024 * 1024 + 1, 2 * 1024 * 1024, 4 * 1024 * 1024 }) {
			ApiResponse tooLarge = file(token, document(documents, null, null, null), "a.docx", bytes(size));
			assertEquals(tooLarge.toString(), 413, tooLarge.status());
			assertEquals("too-large", tooLarge.code());
			assertEquals("The file is larger than the upload limit of 1 MB.",
					tooLarge.json().path("detail").textValue());
		}
		// The name is checked first
		assertRefused(token, document(documents, null, null, null), "README", bytes(1024 * 1024 + 1),
				List.of("name extension"));
		// and so is the folder, but CXF refuses a part more than 1 MB over the limit before anything is checked
		assertEquals(404, file(outsiderToken, document(documents, null, null, null), "a.docx",
				bytes(1024 * 1024 + 1)).status());
		assertEquals(413, file(outsiderToken, document(documents, null, null, null), "a.docx",
				bytes(2 * 1024 * 1024 + 2)).status());

		Context.get().getConfig().setProperty(DISALLOW, "exe, ISO");
		assertRefused(token, document(documents, null, null, null), "a.exe", CONTENT, List.of("name disallowed"));
		assertRefused(token, document(documents, null, null, null), "a.ISO", CONTENT, List.of("name disallowed"));
		assertRefused(token, document(documents, "b.EXE", null, null), "a.docx", CONTENT,
				List.of("name disallowed"));

		assertRefused(token, document(documents, null, null, null), "README", CONTENT, List.of("name extension"));
		assertRefused(token, document(documents, "Notes.", null, null), "a.docx", CONTENT,
				List.of("name extension"));
		assertRefused(token, document(documents, ".docx", null, null), "a.docx", CONTENT,
				List.of("name extension"));
		for (String name : List.of("a/b.docx", "a\\b.docx", "a\u0007b.docx", "a\nb.docx"))
			assertRefused(token, document(documents, name, null, null), "a.docx", CONTENT, List.of("name invalid"));
		// CXF keeps a part over 1 MB in a temporary file: the file sent first, and a part nothing takes
		assertEquals(404, post("/api/v1/documents").token(outsiderToken).file("file", "a.docx", bytes(1024 * 1024 + 1))
				.part("document", document(documents, null, null, null)).send().status());
		ApiResponse extra = post("/api/v1/documents").token(token).file("extra", "b.docx", bytes(1024 * 1024 + 1))
				.part("document", document(documents, null, null, null)).file("file", "README", CONTENT).send();
		assertEquals(List.of("name extension"), fields(extra));
		before.assertUnchanged();
		// Neither the API's copies of the uploads nor CXF's are left behind
		assertEquals(temporaryFiles, temporaryFiles());

		// The name sent wins over the file's; a file of exactly the limit is taken
		ApiResponse filed = file(token, document(documents, "Lease.docx", null, null), "upload.docx",
				bytes(1024 * 1024));
		assertEquals(filed.toString(), 201, filed.status());
		assertEquals("Lease.docx", filed.json().path("name").textValue());
		assertTrue(temporaryFiles().stream().noneMatch(name -> name.startsWith("healdan-upload")));
	}

	@Test
	public void testBadRequests() throws Exception {
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();
		String valid = document(documents, null, null, null);

		ApiResponse unknownField = file(token,
				"{\"folderId\": %d, \"colour\": \"red\"}".formatted(documents.getId()), "a.docx", CONTENT);
		assertBadRequest(unknownField);
		assertEquals("The request has a field the server doesn't know: colour.",
				unknownField.json().path("detail").textValue());
		assertBadRequest(file(token, "{\"folderId\": ", "a.docx", CONTENT));
		assertBadRequest(file(token, "{\"folderId\": \"Documents\"}", "a.docx", CONTENT));
		assertBadRequest(file(token, "{}", "a.docx", CONTENT));
		assertBadRequest(file(token, "null", "a.docx", CONTENT));
		ApiResponse noFile = post("/api/v1/documents").token(token).part("document", valid).send();
		assertBadRequest(noFile);
		assertEquals("Send the file in a part named file.", noFile.json().path("detail").textValue());
		ApiResponse noDocument = post("/api/v1/documents").token(token).file("file", "a.docx", CONTENT).send();
		assertBadRequest(noDocument);
		assertEquals("Send the document's details as JSON in a part named document.",
				noDocument.json().path("detail").textValue());
		ApiResponse notMultipart = post("/api/v1/documents").token(token).json(valid).send();
		assertEquals(notMultipart.toString(), 415, notMultipart.status());
		assertEquals("bad-request", notMultipart.code());

		before.assertUnchanged();
	}

	@Test
	public void testEmail() throws Exception {
		String eml = """
				From: "Ann Smith" <ann@example.com>
				To: bob@example.com
				Subject: Re: Demo plan
				Date: Tue, 14 Oct 2025 09:29:00 +0200
				MIME-Version: 1.0
				Content-Type: text/plain; charset=UTF-8

				Hello Bob.
				""".replace("\n", "\r\n");

		ApiResponse response = file(signIn(clerk), document(documents, null, null, null), "x.eml",
				eml.getBytes(StandardCharsets.UTF_8));

		assertEquals(response.toString(), 201, response.status());
		assertEquals("Re_ Demo plan.eml", response.json().path("name").textValue());
		JsonNode properties = get("/api/v1/documents/" + response.json().path("docNumber").asLong() + "/properties")
				.token(signIn(clerk)).send().json().path("items");
		assertEquals("Ann Smith <ann@example.com>",
				field(properties, HealdanConstants.ATTR_EMAILFROM).path("value").textValue());
		assertEquals("Re: Demo plan", field(properties, HealdanConstants.ATTR_EMAILSUBJECT).path("value").textValue());
	}

	@Test
	public void testNameKept() throws Exception {
		String name = "Board minutes \u2013 \u00e9.docx";

		// The file part names it with RFC 5987's filename* alone
		ApiResponse response = file(signIn(clerk), document(documents, null, null, null), name, CONTENT);

		assertEquals(response.toString(), 201, response.status());
		assertEquals(name, response.json().path("name").textValue());
		assertEquals(name, jdbc().queryForObject("select ld_filename from ld_document where ld_customid = ?",
				String.class, response.json().path("docNumber").asText()));
	}

	@Test
	public void testNameSentKept() throws Exception {
		String name = "Board minutes \u2013 \u00e9 (2).docx";

		// As an add-in sends it, in the document part
		ApiResponse response = file(signIn(clerk), document(documents, name, null, null), "upload.docx", CONTENT);

		assertEquals(response.toString(), 201, response.status());
		assertEquals(name, response.json().path("name").textValue());
	}

	@Test
	public void testFormNameKept() throws Exception {
		String name = "Board minutes \u2013 \u00e9.docx";

		// As a browser's form sends it, in UTF-8 in a plain filename
		ApiResponse response = post("/api/v1/documents").token(signIn(clerk))
				.part("document", document(documents, null, null, null)).formFile("file", name, CONTENT).send();

		assertEquals(response.toString(), 201, response.status());
		assertEquals(name, response.json().path("name").textValue());
	}

	private ApiResponse file(String token, String document, String fileName, byte[] content) {
		return post("/api/v1/documents").token(token).part("document", document).file("file", fileName, content)
				.send();
	}

	private static String document(Folder folder, String name, Map<String, Object> properties, Boolean checkOut)
			throws JsonProcessingException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("folderId", folder.getId());
		if (name != null)
			body.put("name", name);
		if (properties != null)
			body.put("properties", properties);
		if (checkOut != null)
			body.put("checkOut", checkOut);
		return ApiJson.mapper().writeValueAsString(body);
	}

	/** Properties, from names and values; a value may be null */
	private static Map<String, Object> values(Object... pairs) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
			values.put((String) pairs[i], pairs[i + 1]);
		return values;
	}

	private void assertRefused(String token, String document, List<String> expected) {
		assertRefused(token, document, "a.docx", CONTENT, expected);
	}

	private void assertRefused(String token, String document, String fileName, byte[] content,
			List<String> expected) {
		ApiResponse refused = file(token, document, fileName, content);
		assertEquals(refused.toString(), expected, fields(refused));
	}

	private static List<String> fields(ApiResponse refused) {
		assertEquals(refused.toString(), 422, refused.status());
		assertEquals("validation", refused.code());
		List<String> fields = new ArrayList<>();
		for (JsonNode field : refused.json().path("fields")) {
			fields.add(field.path("field").textValue() + " " + field.path("code").textValue());
			assertTrue(field.toString(), field.path("detail").textValue().length() > 0);
		}
		return fields;
	}

	private static void assertBadRequest(ApiResponse response) {
		assertEquals(response.toString(), 400, response.status());
		assertEquals("bad-request", response.code());
	}

	private static JsonNode field(JsonNode items, String name) {
		for (JsonNode field : items)
			if (name.equals(field.path("name").textValue()))
				return field;
		throw new AssertionError("No field " + name + " in " + items);
	}

	private int count(Folder folder) {
		return jdbc().queryForObject("select count(*) from ld_document where ld_folderid = ? and ld_deleted = 0",
				Integer.class, folder.getId());
	}

	private static byte[] bytes(int size) {
		byte[] bytes = new byte[size];
		Arrays.fill(bytes, (byte) 'x');
		return bytes;
	}

	private static Set<String> temporaryFiles() {
		String[] names = new File(System.getProperty("java.io.tmpdir")).list();
		return names == null ? Set.of() : new TreeSet<>(List.of(names));
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
