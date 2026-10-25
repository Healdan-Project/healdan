package com.logicaldoc.healdan.api;

import static com.logicaldoc.healdan.stamp.StampFixtures.PROPERTY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.DocumentListenerManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.docnumber.DocNumberCounter;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.stamp.StampFixtures;

/**
 * Stamping the doc ID into the files the API stores (M3 spec section 9.7):
 * a file that carries the {@code Healdan Doc ID} property is stored with the
 * doc # and version it's stored as, in its property and its fields; any
 * other file is stored as sent.
 * <p>
 * Clerk reads, writes and downloads in matter 998.1.
 */
public class StampingTest extends ApiTestCase {

	private User clerk;

	private Folder documents;

	private String token;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		clerk = fx.user("clerk");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId(), true);
		// The test context keeps two versions of a document; the Store and the matter keep all of them
		for (Folder quota : new Folder[] { FolderDAO.get().findById(store.getId(), true), workspace }) {
			quota.setVersionsQuota(100L);
			FolderDAO.get().store(quota, fx.folderHistory(fx.admin()));
		}
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		token = signIn(clerk);
	}

	@Test
	public void testFilingStampsItsOwnNumber() throws Exception {
		long next = DocNumberCounter.get().peekNext();

		JsonNode docx = filed("Lease.docx", StampFixtures.docx("#999 v9.9"));
		JsonNode odt = filed("Memo.odt", StampFixtures.odt("#999 v9.9"));

		assertEquals(next, docx.path("docNumber").asLong());
		assertEquals("1.0", docx.path("fileVersion").textValue());
		byte[] stored = content(next);
		assertEquals("#%d v1.0".formatted(next), StampFixtures.docxProperty(stored, PROPERTY));
		assertEquals(List.of("#%d v1.0".formatted(next), "#%d v1.0".formatted(next)),
				StampFixtures.docxFieldTexts(stored, PROPERTY));

		// The number came from the counter, once each: no number was taken twice or skipped
		assertEquals(next + 1, odt.path("docNumber").asLong());
		byte[] storedOdt = content(next + 1);
		assertEquals("#%d v1.0".formatted(next + 1), StampFixtures.odtProperty(storedOdt, PROPERTY));
		assertEquals(List.of("#%d v1.0".formatted(next + 1), "#%d v1.0".formatted(next + 1)),
				StampFixtures.odtFieldTexts(storedOdt));
		assertEquals(next + 2, DocNumberCounter.get().peekNext());
	}

	@Test
	public void testSaveStampsTheNewVersion() throws Exception {
		long n = filed("Lease.docx", StampFixtures.docx("#999 v9.9")).path("docNumber").asLong();
		// A change of properties moves version on, not the file: the next save counts from version
		ApiResponse patched = patch("/api/v1/documents/" + n + "/properties").token(token)
				.json(Map.of("values", Map.of("legacydocnum", "OLD-1"))).send();
		assertEquals(patched.toString(), 200, patched.status());
		JsonNode document = get("/api/v1/documents/" + n).token(token).send().json();
		assertEquals("1.1", document.path("version").textValue());
		assertEquals("1.0", document.path("fileVersion").textValue());

		JsonNode snapshot = saved(n, "snapshot", "Lease.docx", StampFixtures.docx("#%d v1.0".formatted(n)));
		assertEquals("1.2", snapshot.path("fileVersion").textValue());
		byte[] stored = content(n);
		assertEquals("#%d v1.2".formatted(n), StampFixtures.docxProperty(stored, PROPERTY));
		assertEquals(List.of("#%d v1.2".formatted(n), "#%d v1.2".formatted(n)),
				StampFixtures.docxFieldTexts(stored, PROPERTY));

		JsonNode version = saved(n, "version", "Lease.docx", StampFixtures.docx("#%d v1.2".formatted(n)));
		assertEquals("2.0", version.path("fileVersion").textValue());
		stored = content(n);
		assertEquals("#%d v2.0".formatted(n), StampFixtures.docxProperty(stored, PROPERTY));
		assertEquals(List.of("#%d v2.0".formatted(n), "#%d v2.0".formatted(n)),
				StampFixtures.docxFieldTexts(stored, PROPERTY));

		long m = filed("Memo.odt", StampFixtures.odt("#999 v9.9")).path("docNumber").asLong();
		saved(m, "snapshot", "Memo.odt", StampFixtures.odt("#%d v1.0".formatted(m)));
		byte[] storedOdt = content(m);
		assertEquals("#%d v1.1".formatted(m), StampFixtures.odtProperty(storedOdt, PROPERTY));
		assertEquals(List.of("#%d v1.1".formatted(m), "#%d v1.1".formatted(m)),
				StampFixtures.odtFieldTexts(storedOdt));
	}

	@Test
	public void testSaveAsNewDocument() throws Exception {
		long n = filed("Lease.docx", StampFixtures.docx("#999 v9.9")).path("docNumber").asLong();

		// Save as New Document files the content of #n, with #n's number in it
		long m = filed("Lease (2).docx", content(n)).path("docNumber").asLong();

		byte[] stored = content(m);
		assertEquals("#%d v1.0".formatted(m), StampFixtures.docxProperty(stored, PROPERTY));
		assertEquals(List.of("#%d v1.0".formatted(m), "#%d v1.0".formatted(m)),
				StampFixtures.docxFieldTexts(stored, PROPERTY));
		assertEquals("#%d v1.0".formatted(n), StampFixtures.docxProperty(content(n), PROPERTY));
	}

	@Test
	public void testNoPropertyByteForByte() throws Exception {
		byte[] docx = StampFixtures.docxWithoutProperty();
		byte[] odt = StampFixtures.odtWithoutProperty();

		long n = filed("Lease.docx", docx).path("docNumber").asLong();
		assertArrayEquals(docx, content(n));
		long m = filed("Memo.odt", odt).path("docNumber").asLong();
		assertArrayEquals(odt, content(m));

		// A save too
		byte[] other = StampFixtures.zip(StampFixtures.docxParts("#1 v1.0", false), Set.of("word/document.xml"));
		saved(n, "snapshot", "Lease.docx", other);
		assertArrayEquals(other, content(n));
	}

	@Test
	public void testRefusedFilingTakesNoNumber() throws Exception {
		Template profile = TemplateDAO.get().findById(ProfileSchema.get().template().getId(), true);
		Attribute court = new Attribute();
		court.setLabel("Court");
		court.setType(Attribute.TYPE_STRING);
		court.setMandatory(true);
		court.setPosition(13);
		profile.getTemplateAttributes().put("court", court);
		TemplateDAO.get().store(profile);
		long next = DocNumberCounter.get().peekNext();

		ApiResponse refused = file(document(Map.of()), "Lease.docx", StampFixtures.docx("#999 v9.9"));

		assertEquals(refused.toString(), 422, refused.status());
		assertEquals("court", refused.json().path("fields").path(0).path("field").textValue());
		assertEquals(next, DocNumberCounter.get().peekNext());
		JsonNode filed = file(document(Map.of("court", "High Court")), "Lease.docx", StampFixtures.docx("#999 v9.9"))
				.json();
		assertEquals(next, filed.path("docNumber").asLong());
		assertEquals("#%d v1.0".formatted(next), StampFixtures.docxProperty(content(next), PROPERTY));
	}

	/**
	 * The number is taken after the API's checks, just before CE's create
	 * (plan correction 2). A failure inside the create leaves a gap, as a
	 * deleted document does, and the number isn't handed to the next
	 * document filed on the same thread (task 7's ruling R8).
	 */
	@Test
	public void testFailedCreateLeavesAGap() throws Exception {
		long next = DocNumberCounter.get().peekNext();
		DocumentListener veto = new NewDocumentVeto("Refused.docx");
		// Before Healdan's own, so the doc # listener never sees it
		DocumentListenerManager.get().getListeners().add(0, veto);
		ApiResponse refused;
		try {
			refused = file(document(null), "Refused.docx", StampFixtures.docx("#999 v9.9"));
		} finally {
			DocumentListenerManager.get().getListeners().remove(veto);
		}
		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("refused", refused.code());

		JsonNode filed = filed("Lease.docx", StampFixtures.docx("#999 v9.9"));

		assertEquals(next + 1, filed.path("docNumber").asLong());
		assertEquals("#%d v1.0".formatted(next + 1), StampFixtures.docxProperty(content(next + 1), PROPERTY));
		assertEquals(0, jdbc().queryForObject("select count(*) from ld_document where ld_customid = ?",
				Integer.class, Long.toString(next)).intValue());
	}

	private JsonNode filed(String name, byte[] content) throws JsonProcessingException {
		ApiResponse response = file(document(null), name, content);
		assertEquals(response.toString(), 201, response.status());
		return response.json();
	}

	private ApiResponse file(String document, String name, byte[] content) {
		return post("/api/v1/documents").token(token).part("document", document).file("file", name, content).send();
	}

	private String document(Map<String, Object> properties) throws JsonProcessingException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("folderId", documents.getId());
		if (properties != null)
			body.put("properties", properties);
		return ApiJson.mapper().writeValueAsString(body);
	}

	private JsonNode saved(long docNumber, String kind, String name, byte[] content) throws Exception {
		ApiResponse response = post("/api/v1/documents/" + docNumber + "/versions").token(token)
				.part("version", ApiJson.mapper().writeValueAsString(Map.of("kind", kind)))
				.file("file", name, content).send();
		assertEquals(response.toString(), 201, response.status());
		return response.json();
	}

	private byte[] content(long docNumber) {
		ApiResponse response = get("/api/v1/documents/" + docNumber + "/content").token(token).send();
		assertEquals(response.toString(), 200, response.status());
		return response.body();
	}

	/** A rule that refuses one new document by its name, before CE writes anything */
	private static final class NewDocumentVeto implements DocumentListener {

		private final String fileName;

		NewDocumentVeto(String fileName) {
			this.fileName = fileName;
		}

		@Override
		public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
				throws PersistenceException {
			if (document.getId() == 0L && fileName.equals(document.getFileName()))
				throw new HealdanVetoException("Not this one.");
		}

		@Override
		public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
			// Nothing to refuse
		}
	}
}
