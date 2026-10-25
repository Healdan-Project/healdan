package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.HealdanConstants;

/**
 * {@code GET /v1/lookup} and {@code GET /v1/documents?legacyDocNumber=} (spec
 * section 6.3).
 */
public class LookupTest extends ApiTestCase {

	private User clerk;

	private User boss;

	private Folder documents;

	private Folder secret;

	private Document doc;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		secret = fx.folder(documents, "Secret");
		doc = file(documents, "memo.docx", null);
	}

	private Document file(Folder folder, String name, String legacy) throws Exception {
		Document d = fx.upload(folder, name, "Healdan " + name, boss);
		awaitAsyncWork();
		if (legacy != null) {
			Document current = DocumentDAO.get().findById(d.getId(), true);
			current.setValue(HealdanConstants.ATTR_LEGACYDOCNUM, legacy);
			DocumentDAO.get().store(current, fx.documentHistory(boss));
		}
		return d;
	}

	private static long number(Document d) {
		return Long.parseLong(d.getCustomId());
	}

	private ApiResponse lookup(String q) {
		return get("/api/v1/lookup?q=" + q.replace("#", "%23")).token(signIn(clerk)).send();
	}

	@Test
	public void testResolves() {
		long n = number(doc);
		for (String typed : new String[] { "#" + n, "#" + n + ".1", "#" + n + ".1.0", "#" + n + "v1" }) {
			ApiResponse response = lookup(typed);
			assertEquals(typed + " " + response, 200, response.status());
			assertEquals(n, response.json().path("docNumber").asLong());
			assertEquals(typed, "1.0", response.json().path("version").textValue());
		}
	}

	/** The title bar shows {@code #1234 v1.1} */
	@Test
	public void testTitleForm() throws Exception {
		DocumentHistory history = fx.documentHistory(boss);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(doc.getId(), history);
		DocumentManager.get().checkin(doc.getId(), new ByteArrayInputStream("Second".getBytes(StandardCharsets.UTF_8)),
				doc.getFileName(), false, null, fx.documentHistory(boss)).get();
		awaitAsyncWork();

		ApiResponse response = lookup("#" + number(doc) + " v1.1");

		assertEquals(response.toString(), 200, response.status());
		assertEquals(number(doc), response.json().path("docNumber").asLong());
		assertEquals("1.1", response.json().path("version").textValue());
	}

	@Test
	public void testStripsTheInput() {
		ApiResponse response = get("/api/v1/lookup?q=%20%23" + number(doc) + "%20").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(number(doc), response.json().path("docNumber").asLong());
	}

	@Test
	public void testNotFound() {
		long n = number(doc);
		ApiResponse unknown = lookup("#999999");
		assertEquals(404, unknown.status());
		assertEquals("not-found", unknown.json().path("code").textValue());
		assertEquals("No document #999999", unknown.json().path("detail").textValue());

		ApiResponse noVersion = lookup("#" + n + ".9");
		assertEquals(404, noVersion.status());
		assertEquals("Document #" + n + " has no version 9", noVersion.json().path("detail").textValue());
	}

	@Test
	public void testNotAHash() {
		for (String typed : new String[] { "1234", "#", "#abc", "" }) {
			ApiResponse response = lookup(typed);
			assertEquals(typed + " " + response, 400, response.status());
			assertEquals("bad-request", response.json().path("code").textValue());
		}
		assertEquals("'1234' isn't a document number. Type # and the number, e.g. #1234.",
				lookup("1234").json().path("detail").textValue());
		assertEquals(400, get("/api/v1/lookup").token(signIn(clerk)).send().status());
	}

	@Test
	public void testUnreadableIsNotFound() throws Exception {
		Folder own = FolderDAO.get().findById(secret.getId(), true);
		own.setSecurityRef(null);
		FolderDAO.get().store(own, fx.folderHistory(boss));
		fx.grant(secret, boss.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		Document hidden = file(secret, "hidden.docx", null);

		ApiResponse response = lookup("#" + number(hidden));
		assertEquals(404, response.status());
		assertEquals("No document #" + number(hidden), response.json().path("detail").textValue());
	}

	@Test
	public void testLegacyDocNumber() throws Exception {
		Document first = file(documents, "a.docx", "ABC-1");
		Document second = file(documents, "b.docx", "ABC-1");
		file(documents, "c.docx", "ABC-10");
		Document hidden = file(secret, "hidden.docx", "ABC-1");
		Folder own = FolderDAO.get().findById(secret.getId(), true);
		own.setSecurityRef(null);
		FolderDAO.get().store(own, fx.folderHistory(boss));
		fx.grant(secret, boss.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);

		ApiResponse response = get("/api/v1/documents?legacyDocNumber=ABC-1").token(signIn(clerk)).send();
		assertEquals(response.toString(), 200, response.status());
		List<Long> numbers = new ArrayList<>();
		for (JsonNode item : response.json().path("items"))
			numbers.add(item.path("docNumber").asLong());
		assertEquals(List.of(number(first), number(second)), numbers);
		assertEquals(2, response.json().path("total").asInt());

		JsonNode all = get("/api/v1/documents?legacyDocNumber=ABC-1").token(signIn(boss)).send().json();
		assertEquals(3, all.path("items").size());
		assertEquals(number(hidden), all.path("items").get(2).path("docNumber").asLong());

		assertEquals(0, get("/api/v1/documents?legacyDocNumber=ABC").token(signIn(clerk)).send().json().path("items").size());
		// A doc # is not a legacy doc #
		assertEquals(0, get("/api/v1/documents?legacyDocNumber=" + number(doc)).token(signIn(clerk)).send().json()
				.path("items").size());
	}

	@Test
	public void testLegacyNeedsANumber() {
		String token = signIn(clerk);
		assertEquals(400, get("/api/v1/documents").token(token).send().status());
		assertEquals(400, get("/api/v1/documents?legacyDocNumber=").token(token).send().status());
		assertEquals(400, get("/api/v1/documents?legacyDocNumber=%20").token(token).send().status());
	}
}
