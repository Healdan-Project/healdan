// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * {@code POST /v1/emails/inspect} and {@code GET /v1/emails} (M5 spec §6.7,
 * §6.8).
 * <p>
 * Clerk reads and writes in matter 998.1; Reader may read only; Outsider has
 * no grant; Secret, a folder of the matter, is for Boss and Clerk alone.
 */
public class EmailResourceTest extends ApiTestCase {

	private static final String MAX_SIZE = Tenant.DEFAULT_NAME + ".upload.maxsize";

	private static final String SERVER_URL = "server.url";

	/** The Message-ID of parts-attached.eml */
	private static final String MESSAGE_ID = "attached-1@example.com";

	private String savedMaxSize;

	private String savedServerUrl;

	private User clerk;

	private User reader;

	private User outsider;

	private User boss;

	private Folder store;

	private Folder documents;

	private Folder secret;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		savedMaxSize = config().getProperty(MAX_SIZE);
		savedServerUrl = config().getProperty(SERVER_URL);

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		boss = fx.user("boss", "admin");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		secret = fx.folder(documents, "Secret");
		Folder own = FolderDAO.get().findById(secret.getId(), true);
		own.setSecurityRef(null);
		FolderDAO.get().store(own, fx.folderHistory(boss));
		fx.grant(secret, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
	}

	@Override
	public void tearDown() throws IOException {
		restore(MAX_SIZE, savedMaxSize);
		restore(SERVER_URL, savedServerUrl);
		super.tearDown();
	}

	@Test
	public void testInspect() throws Exception {
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		ApiResponse response = inspect(token, eml("parts-attached.eml"));

		assertEquals(response.toString(), 200, response.status());
		JsonNode inspection = response.json();
		assertEquals(MESSAGE_ID, inspection.path("messageId").textValue());
		assertEquals("Contract for review", inspection.path("subject").textValue());
		assertEquals("Ann Smith <ann@example.com>", inspection.path("from").textValue());
		assertEquals("2026-11-03T10:00:00Z", inspection.path("sent").textValue());
		assertEquals(0, inspection.path("copies").size());

		JsonNode parts = inspection.path("attachments");
		assertEquals(3, parts.size());
		assertPart(parts.get(0), 0, "logo.png", "image/png", 70, true, false);
		assertPart(parts.get(1), 1, "Contract.pdf", "application/pdf", 38, false, false);
		// The forwarded message, whose size is its own bytes'
		assertPart(parts.get(2), 2, "Original request.eml", "message/rfc822", parts.get(2).path("size").asLong(), false,
				true);
		assertTrue(parts.get(2).path("size").asLong() > 0);

		// Nothing is stored, and nothing is left in the temporary folder
		awaitAsyncWork();
		before.assertUnchanged();
		assertEquals(List.of(), uploadFiles());
	}

	@Test
	public void testInspectWithoutAMessageIdOrAParts() throws Exception {
		String eml = "From: ann@example.com\r\nSubject: Short note\r\n\r\nHello.\r\n";

		ApiResponse response = inspect(signIn(clerk), eml.getBytes(StandardCharsets.UTF_8));

		assertEquals(response.toString(), 200, response.status());
		assertTrue(response.json().path("messageId").isNull());
		assertEquals("Short note", response.json().path("subject").textValue());
		assertTrue(response.json().path("sent").isNull());
		assertEquals(0, response.json().path("attachments").size());
		assertEquals(0, response.json().path("copies").size());
	}

	@Test
	public void testInspectNamesReadableCopies() throws Exception {
		byte[] eml = eml("parts-attached.eml");
		ApiResponse filed = post("/api/v1/documents").token(signIn(clerk))
				.part("document", "{\"folderId\": %d}".formatted(documents.getId())).file("file", "Contract.eml", eml)
				.send();
		assertEquals(filed.toString(), 201, filed.status());
		awaitAsyncWork();
		long docNumber = filed.json().path("docNumber").asLong();
		// Another matter's copy, in a Store that only an administrator reads
		Document hidden = file(matterDocuments(fx.store("Private Work"), 2, "Private"), "parts-attached.eml");

		// The clerk and the reader read the first copy, the outsider none, and the boss both
		JsonNode clerks = inspect(signIn(clerk), eml).json().path("copies");
		assertEquals(List.of(docNumber), numbers(clerks));
		assertEquals("document", clerks.get(0).path("type").textValue());
		assertEquals(List.of(docNumber), numbers(inspect(signIn(reader), eml).json().path("copies")));
		assertEquals(List.of(), numbers(inspect(signIn(outsider), eml).json().path("copies")));
		assertEquals(List.of(docNumber, number(hidden)), numbers(inspect(signIn(boss), eml).json().path("copies")));
	}

	@Test
	public void testInspectRefusals() throws Exception {
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		ApiResponse noFile = post("/api/v1/emails/inspect").token(token).file("extra", "a.eml", eml("parts-attached.eml"))
				.send();
		assertEquals(noFile.toString(), 400, noFile.status());
		assertEquals("bad-request", noFile.code());
		assertEquals("Send the email in a part named file.", noFile.json().path("detail").textValue());

		// The limit is read for each request. CXF itself stops reading far over it
		config().setProperty(MAX_SIZE, "1");
		for (int size : new int[] { 1024 * 1024 + 1, 2 * 1024 * 1024, 4 * 1024 * 1024 }) {
			ApiResponse tooLarge = inspect(token, bytes(size));
			assertEquals(tooLarge.toString(), 413, tooLarge.status());
			assertEquals("too-large", tooLarge.code());
			assertEquals("The file is larger than the upload limit of 1 MB.", tooLarge.json().path("detail").textValue());
		}
		config().setProperty(MAX_SIZE, "10");

		// Nothing that isn't a message: an empty file, notes, JSON, a PDF, binary data, an Outlook .msg's bytes
		byte[] binary = new byte[256];
		for (int i = 0; i < binary.length; i++)
			binary[i] = (byte) i;
		byte[] ole = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 };
		for (byte[] notAnEmail : new byte[][] { new byte[0], "Just some notes.\nSecond line.\n".getBytes(StandardCharsets.UTF_8),
				"{\"a\":1}".getBytes(StandardCharsets.UTF_8), "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8),
				binary, ole, "\r\n\r\nBody only: it has no headers.\r\n".getBytes(StandardCharsets.UTF_8) }) {
			ApiResponse refused = inspect(token, notAnEmail);
			assertEquals(refused.toString(), 422, refused.status());
			assertEquals("validation", refused.code());
			assertEquals(1, refused.json().path("fields").size());
			assertEquals("file", refused.json().path("fields").get(0).path("field").textValue());
			assertEquals("not-an-email", refused.json().path("fields").get(0).path("code").textValue());
		}

		before.assertUnchanged();
		assertEquals(List.of(), uploadFiles());
	}

	@Test
	public void testCopies() throws Exception {
		Document copy = file(documents, "parts-attached.eml");
		String token = signIn(clerk);

		ApiResponse response = copies(token, "<" + MESSAGE_ID + ">", "nobody@example.com");

		assertEquals(response.toString(), 200, response.status());
		JsonNode items = response.json().path("items");
		assertEquals(2, items.size());
		// In the order asked, each under its Message-ID without the angle brackets
		assertEquals(MESSAGE_ID, items.get(0).path("messageId").textValue());
		JsonNode found = items.get(0).path("copies");
		assertEquals(1, found.size());
		assertEquals(number(copy), found.get(0).path("document").path("docNumber").asLong());
		assertEquals("document", found.get(0).path("document").path("type").textValue());
		assertEquals(documents.getId(), found.get(0).path("document").path("folder").path("id").asLong());
		assertEquals("998.1 \u2013 Demo", found.get(0).path("document").path("matter").path("display").textValue());
		assertEquals("http://localhost:8080/frontend.jsp?docId=" + copy.getId(), found.get(0).path("address").textValue());
		assertEquals("nobody@example.com", items.get(1).path("messageId").textValue());
		assertEquals(0, items.get(1).path("copies").size());

		// The same, in the other order, with and without brackets, each answered once
		JsonNode again = copies(token, "nobody@example.com", MESSAGE_ID, "<nobody@example.com>", "<" + MESSAGE_ID + ">")
				.json().path("items");
		assertEquals(2, again.size());
		assertEquals("nobody@example.com", again.get(0).path("messageId").textValue());
		assertEquals(0, again.get(0).path("copies").size());
		assertEquals(MESSAGE_ID, again.get(1).path("messageId").textValue());
		assertEquals(1, again.get(1).path("copies").size());

		// Two copies, in different matters, oldest first
		Document second = file(matterDocuments(store, 2, "Second"), "parts-attached.eml");
		JsonNode both = copies(token, MESSAGE_ID).json().path("items").get(0).path("copies");
		assertEquals(List.of(number(copy), number(second)), numbers(both, "document"));
	}

	@Test
	public void testCopiesFindMessageIdsWithOddCharacters() throws Exception {
		String eml = "From: ann@example.com\r\nSubject: Odd\r\nMessage-ID: <Odd+Tag&1=2@Example.com>\r\n\r\nHi.\r\n";
		Document copy = fx.upload(documents, "odd.eml", eml, boss);
		awaitAsyncWork();

		// A plus sign is %2B in a query, or it reads as a space; the Message-ID is compared as it is
		JsonNode items = get("/api/v1/emails?messageId=%3COdd%2BTag%261%3D2%40Example.com%3E&messageId=odd%2Btag%261%3D2%40example.com")
				.token(signIn(clerk)).send().json().path("items");
		assertEquals(2, items.size());
		assertEquals("Odd+Tag&1=2@Example.com", items.get(0).path("messageId").textValue());
		assertEquals(1, items.get(0).path("copies").size());
		assertEquals(number(copy), items.get(0).path("copies").get(0).path("document").path("docNumber").asLong());
		assertEquals(0, items.get(1).path("copies").size());
	}

	@Test
	public void testCopiesOnlyReadable() throws Exception {
		Document open = file(documents, "parts-attached.eml");
		Document hidden = file(secret, "healdan-cc.eml");

		JsonNode clerks = copies(signIn(clerk), MESSAGE_ID, "1@example.com").json().path("items");
		assertEquals(List.of(number(open)), numbers(clerks.get(0).path("copies"), "document"));
		assertEquals(List.of(number(hidden)), numbers(clerks.get(1).path("copies"), "document"));

		// The reader can't read Secret: its copy is none, not a gap that shows
		JsonNode readers = copies(signIn(reader), MESSAGE_ID, "1@example.com").json().path("items");
		assertEquals(List.of(number(open)), numbers(readers.get(0).path("copies"), "document"));
		assertEquals("1@example.com", readers.get(1).path("messageId").textValue());
		assertEquals(0, readers.get(1).path("copies").size());

		JsonNode outsiders = copies(signIn(outsider), MESSAGE_ID, "1@example.com").json().path("items");
		assertEquals(0, outsiders.get(0).path("copies").size());
		assertEquals(0, outsiders.get(1).path("copies").size());
	}

	@Test
	public void testCopiesLeaveOutDeleted() throws Exception {
		Document copy = file(documents, "parts-attached.eml");
		assertEquals(1, copies(signIn(clerk), MESSAGE_ID).json().path("items").get(0).path("copies").size());

		DocumentDAO.get().delete(copy.getId(), fx.documentHistory(boss));

		assertEquals(0, copies(signIn(clerk), MESSAGE_ID).json().path("items").get(0).path("copies").size());
		assertEquals(0, inspect(signIn(clerk), eml("parts-attached.eml")).json().path("copies").size());
	}

	@Test
	public void testCopiesLimits() throws Exception {
		String token = signIn(clerk);

		ApiResponse none = get("/api/v1/emails").token(token).send();
		assertEquals(none.toString(), 400, none.status());
		assertEquals("bad-request", none.code());

		List<String> ids = new ArrayList<>();
		for (int i = 1; i <= 101; i++)
			ids.add("m" + i + "@example.com");
		ApiResponse tooMany = copies(token, ids.toArray(new String[0]));
		assertEquals(tooMany.toString(), 400, tooMany.status());
		assertEquals("bad-request", tooMany.code());

		ApiResponse most = copies(token, ids.subList(0, 100).toArray(new String[0]));
		assertEquals(most.toString(), 200, most.status());
		assertEquals(100, most.json().path("items").size());

		// What isn't a Message-ID is answered as it was sent, once, with no copies
		String tooLong = "x".repeat(999);
		JsonNode odd = copies(token, "", "   ", tooLong, "<>", "<>").json().path("items");
		assertEquals(4, odd.size());
		assertEquals(List.of("", "   ", tooLong, "<>"), messageIds(odd));
		for (JsonNode item : odd)
			assertEquals(0, item.path("copies").size());
	}

	@Test
	public void testAddressesFollowThePublicAddress() throws Exception {
		Document copy = file(documents, "parts-attached.eml");
		String token = signIn(clerk);

		// With a public address, its path included
		config().setProperty(SERVER_URL, "https://dms.example.com/healdan/");
		assertEquals("https://dms.example.com/healdan/frontend.jsp?docId=" + copy.getId(), address(token, null));

		// With none, the address the request was made to, whatever its scheme
		config().setProperty(SERVER_URL, "http://server:port");
		assertEquals("http://dms.example.net:8081/frontend.jsp?docId=" + copy.getId(),
				address(token, new Object[] { "http", "dms.example.net", 8081 }));
		assertEquals("https://dms.example.net/frontend.jsp?docId=" + copy.getId(),
				address(token, new Object[] { "https", "dms.example.net", 443 }));
	}

	@Test
	public void testNeedsAToken() throws Exception {
		ApiResponse inspect = post("/api/v1/emails/inspect").file("file", "a.eml", eml("parts-attached.eml")).send();
		assertEquals(inspect.toString(), 401, inspect.status());
		assertEquals("unauthenticated", inspect.code());

		ApiResponse copies = get("/api/v1/emails?messageId=" + MESSAGE_ID).send();
		assertEquals(copies.toString(), 401, copies.status());
		assertEquals("unauthenticated", copies.code());
	}

	private String address(String token, Object[] server) {
		ApiCall call = get("/api/v1/emails?messageId=" + encode(MESSAGE_ID)).token(token);
		if (server != null)
			call.server((String) server[0], (String) server[1], (Integer) server[2]);
		ApiResponse response = call.send();
		assertEquals(response.toString(), 200, response.status());
		return response.json().path("items").get(0).path("copies").get(0).path("address").textValue();
	}

	private ApiResponse inspect(String token, byte[] eml) {
		return post("/api/v1/emails/inspect").token(token).file("file", "message.eml", eml).send();
	}

	private ApiResponse copies(String token, String... messageIds) {
		StringBuilder query = new StringBuilder();
		for (String id : messageIds)
			query.append(query.isEmpty() ? "?" : "&").append("messageId=").append(encode(id));
		return get("/api/v1/emails" + query).token(token).send();
	}

	/** Files a fixture into a folder as the boss does, so the rules and the index see it as any upload */
	private Document file(Folder folder, String fixture) throws Exception {
		Document filed = fx.upload(folder, fixture, eml(fixture), boss);
		awaitAsyncWork();
		return DocumentDAO.get().findById(filed.getId(), true);
	}

	/** The Documents folder of a new matter of client 998 in a Store */
	private Folder matterDocuments(Folder inStore, long number, String name) throws Exception {
		Folder workspace = FolderDAO.get().findById(
				fx.matter(ClientDAO.get().findByNumber(998), number, name, inStore, "Documents").getFolderId());
		return FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private static byte[] eml(String fixture) throws IOException {
		try (var in = EmailResourceTest.class.getResourceAsStream("/email/" + fixture)) {
			if (in == null)
				throw new IOException("No fixture " + fixture);
			return in.readAllBytes();
		}
	}

	private static void assertPart(JsonNode part, int index, String name, String type, long size, boolean inline,
			boolean message) {
		assertEquals(index, part.path("index").asInt());
		assertEquals(name, part.path("name").textValue());
		assertEquals(type, part.path("type").textValue());
		assertEquals(size, part.path("size").asLong());
		assertEquals(inline, part.path("inline").booleanValue());
		assertEquals(message, part.path("message").booleanValue());
	}

	private static long number(Document d) {
		return Long.parseLong(d.getCustomId());
	}

	private static List<Long> numbers(JsonNode documents) {
		return numbers(documents, null);
	}

	/** The doc #s of views, or of the {@code field} of each item that holds a view */
	private static List<Long> numbers(JsonNode items, String field) {
		List<Long> numbers = new ArrayList<>();
		for (JsonNode item : items)
			numbers.add((field != null ? item.path(field) : item).path("docNumber").asLong());
		return numbers;
	}

	private static List<String> messageIds(JsonNode items) {
		List<String> ids = new ArrayList<>();
		for (JsonNode item : items)
			ids.add(item.path("messageId").textValue());
		return ids;
	}

	private static List<String> uploadFiles() {
		String[] names = new File(System.getProperty("java.io.tmpdir")).list();
		Set<String> uploads = new TreeSet<>();
		for (String name : names == null ? new String[0] : names)
			if (name.startsWith("healdan-upload"))
				uploads.add(name);
		return List.copyOf(uploads);
	}

	private static byte[] bytes(int size) {
		byte[] bytes = new byte[size];
		Arrays.fill(bytes, (byte) 'x');
		return bytes;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static ContextProperties config() {
		return Context.get().getConfig();
	}

	private static void restore(String key, String value) {
		if (value == null)
			config().remove(key);
		else
			config().setProperty(key, value);
	}
}
