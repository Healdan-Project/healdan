package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
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

/**
 * Recent folders, recent documents and check-outs (spec sections 6.2 and 6.4).
 */
public class ListsTest extends ApiTestCase {

	private User clerk;

	private User boss;

	private Folder documents;

	private Folder secret;

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
	}

	private static long number(Document d) {
		return Long.parseLong(d.getCustomId());
	}

	private Document file(Folder folder, String name, User as) throws Exception {
		Document d = fx.upload(folder, name, "Healdan " + name, as);
		awaitAsyncWork();
		return d;
	}

	private void checkout(Document d, User as) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(d.getId(), history);
	}

	/** Gives the folder an ACL of its own that grants Boss alone */
	private void hideFromOthers(Folder f) throws Exception {
		Folder own = FolderDAO.get().findById(f.getId(), true);
		own.setSecurityRef(null);
		FolderDAO.get().store(own, fx.folderHistory(boss));
		fx.grant(f, boss.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
	}

	@Test
	public void testRecentFoldersAndDocuments() throws Exception {
		Document first = file(documents, "first.docx", clerk);
		Thread.sleep(1100);
		Folder sub = fx.folder(documents, "Sub");
		Document second = file(sub, "second.docx", clerk);
		// Someone else's work is not the clerk's
		file(documents, "boss.docx", boss);

		String token = signIn(clerk);
		ApiResponse folders = get("/api/v1/folders/recent").token(token).send();
		assertEquals(folders.toString(), 200, folders.status());
		JsonNode items = folders.json().path("items");
		assertEquals(2, items.size());
		assertEquals(sub.getId(), items.get(0).path("id").asLong());
		assertEquals("folder", items.get(0).path("type").textValue());
		assertEquals("folder", items.get(0).path("kind").textValue());
		assertEquals("998.1 \u2013 Demo", items.get(0).path("matter").path("display").textValue());
		assertEquals("Healdan", items.get(0).path("matter").path("client").path("name").textValue());
		assertEquals(documents.getId(), items.get(1).path("id").asLong());

		ApiResponse docs = get("/api/v1/documents/recent").token(token).send();
		assertEquals(docs.toString(), 200, docs.status());
		JsonNode list = docs.json().path("items");
		assertEquals(2, list.size());
		assertEquals(number(second), list.get(0).path("docNumber").asLong());
		assertEquals("document", list.get(0).path("type").textValue());
		assertEquals("second.docx", list.get(0).path("name").textValue());
		assertEquals(number(first), list.get(1).path("docNumber").asLong());
		assertEquals(2, docs.json().path("total").asInt());

		// A folder that is no longer readable, and the documents in it, drop out
		hideFromOthers(sub);
		assertEquals(1, get("/api/v1/folders/recent").token(token).send().json().path("items").size());
		JsonNode left = get("/api/v1/documents/recent").token(token).send().json().path("items");
		assertEquals(1, left.size());
		assertEquals(number(first), left.get(0).path("docNumber").asLong());

		// Boss uploaded one document, in Documents
		assertEquals(1, get("/api/v1/folders/recent").token(signIn(boss)).send().json().path("items").size());
	}

	@Test
	public void testApiWritesFeedTheLists() throws Exception {
		// Spec section 14.1: the API's own writes record the caller, so they reach the caller's lists
		Document saved = file(documents, "saved.docx", boss);
		Document held = file(documents, "held.docx", boss);
		String token = signIn(clerk);
		assertEquals(List.of(), docNumbers(get("/api/v1/documents/recent").token(token).send()));

		ApiResponse filed = post("/api/v1/documents").token(token)
				.part("document", "{\"folderId\": %d}".formatted(documents.getId()))
				.file("file", "filed.docx", "Healdan filed".getBytes(StandardCharsets.UTF_8)).send();
		assertEquals(filed.toString(), 201, filed.status());
		long filedNumber = filed.json().path("docNumber").asLong();
		assertEquals(Set.of(filedNumber), Set.copyOf(docNumbers(get("/api/v1/documents/recent").token(token).send())));

		ApiResponse save = post("/api/v1/documents/" + number(saved) + "/versions").token(token)
				.part("version", "{\"kind\": \"snapshot\"}")
				.file("file", "saved.docx", "Healdan saved".getBytes(StandardCharsets.UTF_8)).send();
		assertEquals(save.toString(), 201, save.status());
		assertEquals(Set.of(filedNumber, number(saved)),
				Set.copyOf(docNumbers(get("/api/v1/documents/recent").token(token).send())));

		assertEquals(200, post("/api/v1/documents/" + number(held) + "/checkout").token(token).send().status());
		assertEquals(List.of(number(held)), docNumbers(get("/api/v1/me/checkouts").token(token).send()));
		assertEquals(Set.of(filedNumber, number(saved), number(held)),
				Set.copyOf(docNumbers(get("/api/v1/documents/recent").token(token).send())));

		assertEquals(200, delete("/api/v1/documents/" + number(held) + "/checkout").token(token).send().status());
		assertEquals(List.of(), docNumbers(get("/api/v1/me/checkouts").token(token).send()));
	}

	private static List<Long> docNumbers(ApiResponse page) {
		assertEquals(page.toString(), 200, page.status());
		List<Long> numbers = new ArrayList<>();
		for (JsonNode item : page.json().path("items"))
			numbers.add(item.path("docNumber").asLong());
		return numbers;
	}

	@Test
	public void testCheckouts() throws Exception {
		Document mineFirst = file(documents, "one.docx", boss);
		Document mineSecond = file(documents, "two.docx", boss);
		Document theirs = file(documents, "theirs.docx", boss);
		Document hidden = file(secret, "hidden.docx", boss);
		file(documents, "free.docx", boss);
		checkout(mineFirst, clerk);
		Thread.sleep(1100);
		checkout(mineSecond, clerk);
		checkout(theirs, boss);
		checkout(hidden, clerk);
		hideFromOthers(secret);

		ApiResponse response = get("/api/v1/me/checkouts").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		assertEquals(2, page.path("total").asInt());
		JsonNode items = page.path("items");
		assertEquals(number(mineSecond), items.get(0).path("docNumber").asLong());
		assertEquals(number(mineFirst), items.get(1).path("docNumber").asLong());
		assertEquals("checkout", items.get(0).path("hold").path("kind").textValue());
		assertEquals(clerk.getId(), items.get(0).path("hold").path("by").path("id").asLong());
		assertTrue(items.get(0).path("hold").path("since").textValue().endsWith("Z"));
		// The other holder sees only the one document
		JsonNode bosses = get("/api/v1/me/checkouts").token(signIn(boss)).send().json();
		assertEquals(1, bosses.path("total").asInt());
		assertEquals(number(theirs), bosses.path("items").get(0).path("docNumber").asLong());
	}
}
