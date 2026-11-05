// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.Multipart;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.communication.MailUtil;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
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
import com.logicaldoc.healdan.api.model.NewEmail;
import com.logicaldoc.healdan.docnumber.DocNumberCounter;
import com.logicaldoc.healdan.email.MessageParts;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * {@code POST /v1/emails}: filing an email whole, and each ticked attachment
 * as a document of its own in the same folder, linked to it, all or nothing
 * (M5 spec §6.5, §6.8).
 * <p>
 * Clerk reads and writes in matter 998.1; Reader may read only; Outsider has
 * no grant.
 */
public class EmailFilingTest extends ApiTestCase {

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String MAX_SIZE = Tenant.DEFAULT_NAME + ".upload.maxsize";

	private static final String DISALLOW = Tenant.DEFAULT_NAME + ".upload.disallow";

	/** parts-attached.eml: 0 is an inline logo, 1 Contract.pdf, 2 a forwarded message */
	private static final String FIXTURE = "parts-attached.eml";

	/** The decoded content of Contract.pdf in the fixture */
	private static final byte[] CONTRACT = Base64.getDecoder()
			.decode("JVBERi0xLjQKJSBIZWFsZGFuIHRlc3QgZml4dHVyZQolJUVPRgo=");

	private final Map<String, String> savedSettings = new LinkedHashMap<>();

	/** Serves the route with a filer that fails when told to (testAllOrNothing) */
	private final DoubledEmails doubled = new DoubledEmails();

	private User clerk;

	private User reader;

	private User outsider;

	private User boss;

	private Folder store;

	private Folder workspace;

	private Folder documents;

	private Matter matter;

	@Override
	protected List<Object> extraBeans() {
		return List.of(doubled);
	}

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
		boss = fx.user("boss", "admin");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		matter = fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents");
		workspace = FolderDAO.get().findById(matter.getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID,
				true);
		AttributeOptionDAO.get().store(new AttributeOption(set.getId(), CLASS, "Letters"));
		AttributeOptionDAO.get().store(new AttributeOption(set.getId(), CLASS, "Memos"));
	}

	@Override
	public void tearDown() throws IOException {
		EmailFiling.afterEmailStored = null;
		MessageLocks.waitMillis = MessageLocks.WAIT_MILLIS;
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
	public void testFilesTheEmailOnly() throws Exception {
		String token = signIn(clerk);
		long next = DocNumberCounter.get().peekNext();
		byte[] eml = eml(FIXTURE);

		ApiResponse response = fileEmail(token, request(documents, null, null), eml);

		assertEquals(response.toString(), 201, response.status());
		JsonNode filed = response.json();
		JsonNode email = filed.path("email");
		assertEquals(next, email.path("docNumber").asLong());
		assertEquals("/api/v1/documents/" + next, response.header("Location"));
		// Named after its subject, in the folder asked for
		assertEquals("Contract for review.eml", email.path("name").textValue());
		assertEquals(documents.getId(), email.path("folder").path("id").asLong());
		assertEquals("998.1 \u2013 Demo", email.path("matter").path("display").textValue());
		assertEquals(0, filed.path("attachments").size());
		assertEquals(0, filed.path("skipped").size());

		JsonNode properties = properties(token, next);
		assertEquals("Contract for review", value(properties, HealdanConstants.ATTR_EMAILSUBJECT).textValue());
		assertEquals("Ann Smith <ann@example.com>", value(properties, HealdanConstants.ATTR_EMAILFROM).textValue());
		assertEquals("attached-1@example.com", value(properties, HealdanConstants.ATTR_EMAILMESSAGEID).textValue());
		// The email is filed whole, as received
		assertArrayEquals(eml, get("/api/v1/documents/" + next + "/content").token(token).send().body());
		assertEquals(1, liveDocuments(documents));
		assertEquals(List.of(), liveLinks());
		assertEquals(List.of(), uploadFiles());

		// An empty list of attachments is none, too
		Folder second = matterDocuments(store, 2, "Second");
		ApiResponse again = fileEmail(token, request(second, null, List.of()), eml);
		assertEquals(again.toString(), 201, again.status());
		assertEquals(0, again.json().path("attachments").size());
	}

	@Test
	public void testFilesTickedAttachments() throws Exception {
		setFolderValue(documents, CLASS, "Memos");
		String token = signIn(clerk);
		long next = DocNumberCounter.get().peekNext();

		ApiResponse response = fileEmail(token,
				request(documents, Map.of(CLASS, "Letters", HealdanConstants.ATTR_COMMENTS, "Reviewed"), List.of(1)),
				eml(FIXTURE));

		assertEquals(response.toString(), 201, response.status());
		JsonNode filed = response.json();
		assertEquals(next, filed.path("email").path("docNumber").asLong());
		assertEquals(0, filed.path("skipped").size());
		JsonNode attachments = filed.path("attachments");
		assertEquals(1, attachments.size());
		JsonNode contract = attachments.get(0);
		// A document of its own, with its own doc #, in the same folder
		assertEquals(next + 1, contract.path("docNumber").asLong());
		assertEquals("Contract.pdf", contract.path("name").textValue());
		assertEquals(documents.getId(), contract.path("folder").path("id").asLong());
		assertEquals(CONTRACT.length, contract.path("size").asLong());
		assertArrayEquals(CONTRACT, get("/api/v1/documents/" + (next + 1) + "/content").token(token).send().body());

		// Linked to the email, the email first
		assertEquals(List.of("attachment " + id(next) + " " + id(next + 1)), liveLinks());
		assertEquals(1, jdbc().queryForObject("select ld_links from ld_document where ld_id = ?", Integer.class,
				id(next)).intValue());

		// The properties sent apply to both, the folder's to what isn't sent; the email fields only to the email
		for (long docNumber : List.of(next, next + 1)) {
			JsonNode properties = properties(token, docNumber);
			assertEquals("Letters", value(properties, CLASS).textValue());
			assertEquals("local", field(properties, CLASS).path("source").textValue());
			assertEquals("Reviewed", value(properties, HealdanConstants.ATTR_COMMENTS).textValue());
			assertEquals(clerk.getId(), value(properties, HealdanConstants.ATTR_AUTHOR).path("id").asLong());
		}
		assertTrue(value(properties(token, next + 1), HealdanConstants.ATTR_EMAILSUBJECT).isNull());
		assertEquals(2, liveDocuments(documents));

		// Into another matter, with no Class sent: each inherits the folder's
		Folder second = matterDocuments(store, 2, "Second");
		setFolderValue(second, CLASS, "Memos");
		ApiResponse inherited = fileEmail(token, request(second, null, List.of(1)), eml(FIXTURE));
		assertEquals(inherited.toString(), 201, inherited.status());
		for (JsonNode document : List.of(inherited.json().path("email"),
				inherited.json().path("attachments").get(0))) {
			JsonNode docclass = field(properties(token, document.path("docNumber").asLong()), CLASS);
			assertEquals("Memos", docclass.path("value").textValue());
			assertEquals("inherited", docclass.path("source").textValue());
		}
		assertEquals(List.of(), uploadFiles());
	}

	@Test
	public void testAttachedMessage() throws Exception {
		String token = signIn(clerk);
		byte[] eml = eml(FIXTURE);

		ApiResponse response = fileEmail(token, request(documents, null, List.of(2)), eml);

		assertEquals(response.toString(), 201, response.status());
		JsonNode forwarded = response.json().path("attachments").get(0);
		assertEquals("Original request.eml", forwarded.path("name").textValue());
		long forwardedNumber = forwarded.path("docNumber").asLong();
		JsonNode properties = properties(token, forwardedNumber);
		assertEquals("Original request", value(properties, HealdanConstants.ATTR_EMAILSUBJECT).textValue());
		assertEquals("Cara Diaz <cara@example.com>", value(properties, HealdanConstants.ATTR_EMAILFROM).textValue());
		assertEquals("forwarded-1@example.com", value(properties, HealdanConstants.ATTR_EMAILMESSAGEID).textValue());
		assertEquals(0, response.json().path("skipped").size());
		long emailNumber = response.json().path("email").path("docNumber").asLong();
		assertEquals(List.of("attachment " + id(emailNumber) + " " + id(forwardedNumber)), liveLinks());
		// The lookup finds it as a copy of its own message
		JsonNode copies = get("/api/v1/emails?messageId=forwarded-1%40example.com").token(token).send().json()
				.path("items").get(0).path("copies");
		assertEquals(1, copies.size());
		assertEquals(forwardedNumber, copies.get(0).path("document").path("docNumber").asLong());

		// When the matter already holds it, it is skipped with its copy, and the email still files
		Folder second = matterDocuments(store, 2, "Second");
		Folder letters = fx.folder(second, "Letters");
		Document copy = fx.upload(letters, "forwarded.eml", part(eml, 2), boss);
		awaitAsyncWork();
		ApiResponse again = fileEmail(token, request(second, null, List.of(1, 2)), eml);

		assertEquals(again.toString(), 201, again.status());
		JsonNode filed = again.json();
		assertEquals(1, filed.path("attachments").size());
		assertEquals("Contract.pdf", filed.path("attachments").get(0).path("name").textValue());
		JsonNode skipped = filed.path("skipped");
		assertEquals(1, skipped.size());
		assertEquals(2, skipped.get(0).path("index").asInt());
		assertEquals("already-filed", skipped.get(0).path("code").textValue());
		assertEquals(get("/api/v1/documents/" + copy.getCustomId()).token(token).send().json(),
				skipped.get(0).path("document"));
		assertEquals(2, liveDocuments(second));

		// An attached message whose name has no .eml is filed as one, so it gets its email fields
		String named = """
				From: Ann Smith <ann@example.com>
				Subject: Forward
				Message-ID: <outer-2@example.com>
				MIME-Version: 1.0
				Content-Type: multipart/mixed; boundary="b"

				--b
				Content-Type: text/plain

				See below.
				--b
				Content-Type: message/rfc822
				Content-Disposition: attachment; filename="Forwarded"

				From: Cara Diaz <cara@example.com>
				Subject: Inner note
				Message-ID: <inner-2@example.com>
				Content-Type: text/plain

				Inner.
				--b--
				""".replace("\n", "\r\n");
		ApiResponse renamed = fileEmail(token, request(documents, null, List.of(0)),
				named.getBytes(StandardCharsets.UTF_8));
		assertEquals(renamed.toString(), 201, renamed.status());
		JsonNode inner = renamed.json().path("attachments").get(0);
		assertEquals("Inner note.eml", inner.path("name").textValue());
		assertEquals("inner-2@example.com",
				value(properties(token, inner.path("docNumber").asLong()), HealdanConstants.ATTR_EMAILMESSAGEID)
						.textValue());
	}

	@Test
	public void testOneMessageAttachedTwice() throws Exception {
		String token = signIn(clerk);
		String inner = "From: Cara Diaz <cara@example.com>\r\nSubject: Twin note\r\nMessage-ID: <twin-1@example.com>\r\n"
				+ "Content-Type: text/plain\r\n\r\nTwin.\r\n";
		String encoded = Base64.getMimeEncoder().encodeToString(inner.getBytes(StandardCharsets.UTF_8));

		// The message attached, and the same message as an .eml file: the rule refuses the second as it's stored
		ApiResponse response = fileEmail(token, request(documents, null, List.of(0, 1)),
				outer("outer-3@example.com", inner, encoded).getBytes(StandardCharsets.UTF_8));

		assertEquals(response.toString(), 201, response.status());
		JsonNode filed = response.json();
		assertEquals(1, filed.path("attachments").size());
		JsonNode twin = filed.path("attachments").get(0);
		assertEquals("Twin note.eml", twin.path("name").textValue());
		JsonNode skipped = filed.path("skipped");
		assertEquals(1, skipped.size());
		assertEquals(1, skipped.get(0).path("index").asInt());
		assertEquals("already-filed", skipped.get(0).path("code").textValue());
		assertEquals(twin.path("docNumber").asLong(), skipped.get(0).path("document").path("docNumber").asLong());
		assertEquals(2, liveDocuments(documents));
		assertEquals(1, liveLinks().size());

		// Another email with the .eml file alone: skipped before anything is stored
		ApiResponse again = fileEmail(token, request(documents, null, List.of(1)),
				outer("outer-4@example.com", inner, encoded).getBytes(StandardCharsets.UTF_8));
		assertEquals(again.toString(), 201, again.status());
		assertEquals(0, again.json().path("attachments").size());
		assertEquals(twin.path("docNumber").asLong(),
				again.json().path("skipped").get(0).path("document").path("docNumber").asLong());
		assertEquals(3, liveDocuments(documents));
		assertEquals(List.of(), uploadFiles());
	}

	/** An email with a message attached twice: as itself (index 0), and as an .eml file (index 1) */
	private static String outer(String messageId, String inner, String encoded) {
		return ("From: Ann Smith <ann@example.com>\r\nSubject: Twice\r\nMessage-ID: <%s>\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n--b\r\nContent-Type: text/plain\r\n\r\nTwice.\r\n"
				+ "--b\r\nContent-Type: message/rfc822\r\nContent-Disposition: attachment\r\n\r\n%s"
				+ "--b\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"copy.eml\"\r\n"
				+ "Content-Transfer-Encoding: base64\r\n\r\n%s\r\n--b--\r\n").formatted(messageId, inner, encoded);
	}

	@Test
	public void testARepeatWaitsForAFilingThatFails() throws Exception {
		Held held = holdTheFirstFiling(true);
		String repeatToken = signIn(clerk);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			Future<ApiResponse> first = threads.submit(() -> fileEmail(held.token, request(documents, null,
					List.of(1, 2)), eml(FIXTURE)));
			assertTrue("The first filing didn't store its email", held.reached.await(60, TimeUnit.SECONDS));
			Future<ApiResponse> repeat = threads.submit(() -> fileEmail(repeatToken, request(documents, null,
					List.of(1, 2)), eml(FIXTURE)));
			awaitWaiting();
			held.go.countDown();

			ApiResponse refused = first.get(120, TimeUnit.SECONDS);
			assertEquals(refused.toString(), 409, refused.status());
			assertEquals("Refused by the test.", refused.json().path("detail").textValue());
			// The repeat found nothing of the first, which was undone, so it filed
			ApiResponse filed = repeat.get(120, TimeUnit.SECONDS);
			assertEquals(filed.toString(), 201, filed.status());
			assertEquals(2, filed.json().path("attachments").size());
		} finally {
			held.go.countDown();
			threads.shutdownNow();
		}
		assertEquals(3, liveDocuments(documents));
		assertEquals(0, MessageLocks.inUse());
	}

	@Test
	public void testARepeatWaitsForAFilingThatSucceeds() throws Exception {
		Held held = holdTheFirstFiling(false);
		String repeatToken = signIn(clerk);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			Future<ApiResponse> first = threads.submit(() -> fileEmail(held.token, request(documents, null,
					List.of(1, 2)), eml(FIXTURE)));
			assertTrue("The first filing didn't store its email", held.reached.await(60, TimeUnit.SECONDS));
			Future<ApiResponse> repeat = threads.submit(() -> fileEmail(repeatToken, request(documents, null,
					List.of(1, 2)), eml(FIXTURE)));
			awaitWaiting();
			held.go.countDown();

			ApiResponse filed = first.get(120, TimeUnit.SECONDS);
			assertEquals(filed.toString(), 201, filed.status());
			long docNumber = filed.json().path("email").path("docNumber").asLong();
			// The repeat waited for the first to end, then found its email
			ApiResponse again = repeat.get(120, TimeUnit.SECONDS);
			assertEquals(again.toString(), 409, again.status());
			assertEquals("already-filed", again.code());
			assertEquals(docNumber, again.json().path("document").path("docNumber").asLong());
		} finally {
			held.go.countDown();
			threads.shutdownNow();
		}
		assertEquals(3, liveDocuments(documents));
		assertEquals(0, MessageLocks.inUse());
	}

	@Test
	public void testARepeatWaitsOnlySoLong() throws Exception {
		Held held = holdTheFirstFiling(false);
		String repeatToken = signIn(clerk);
		MessageLocks.waitMillis = 500L;
		ExecutorService threads = Executors.newSingleThreadExecutor();
		try {
			Future<ApiResponse> first = threads.submit(() -> fileEmail(held.token, request(documents, null,
					List.of(1, 2)), eml(FIXTURE)));
			assertTrue("The first filing didn't store its email", held.reached.await(60, TimeUnit.SECONDS));

			ApiResponse busy = fileEmail(repeatToken, request(documents, null, List.of(1, 2)), eml(FIXTURE));
			assertEquals(busy.toString(), 409, busy.status());
			assertEquals(MessageLocks.IN_PROGRESS, busy.code());

			held.go.countDown();
			ApiResponse filed = first.get(120, TimeUnit.SECONDS);
			assertEquals(filed.toString(), 201, filed.status());
		} finally {
			held.go.countDown();
			threads.shutdownNow();
		}
		assertEquals(3, liveDocuments(documents));
		assertEquals(0, MessageLocks.inUse());
	}

	@Test
	public void testAnUploadWaitsForAFiling() throws Exception {
		Held held = holdTheFirstFiling(true);
		String uploadToken = signIn(clerk);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			Future<ApiResponse> first = threads.submit(() -> fileEmail(held.token, request(documents, null,
					List.of(1, 2)), eml(FIXTURE)));
			assertTrue("The first filing didn't store its email", held.reached.await(60, TimeUnit.SECONDS));
			Future<ApiResponse> upload = threads.submit(() -> post("/api/v1/documents").token(uploadToken)
					.part("document", "{\"folderId\": %d}".formatted(documents.getId()))
					.file("file", "message.eml", eml(FIXTURE)).send());
			awaitWaiting();
			held.go.countDown();

			ApiResponse refused = first.get(120, TimeUnit.SECONDS);
			assertEquals(refused.toString(), 409, refused.status());
			// The upload waited for the filing, which was undone, so it filed rather than meet already-filed
			ApiResponse filed = upload.get(120, TimeUnit.SECONDS);
			assertEquals(filed.toString(), 201, filed.status());
		} finally {
			held.go.countDown();
			threads.shutdownNow();
		}
		assertEquals(1, liveDocuments(documents));
		assertEquals(0, MessageLocks.inUse());
	}

	/** Holds the first filing once its email is stored, until {@code go}; then it fails, or goes on */
	private record Held(String token, CountDownLatch reached, CountDownLatch go) {
	}

	private Held holdTheFirstFiling(boolean fail) {
		Held held = new Held(signIn(clerk), new CountDownLatch(1), new CountDownLatch(1));
		AtomicBoolean first = new AtomicBoolean(true);
		EmailFiling.afterEmailStored = email -> {
			if (!first.getAndSet(false))
				return;
			held.reached().countDown();
			try {
				if (!held.go().await(60, TimeUnit.SECONDS))
					throw new IllegalStateException("The test didn't let the first filing go on");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
			if (fail)
				throw new IllegalStateException("Can't file a document", new HealdanVetoException("Refused by the test."));
		};
		return held;
	}

	/** Until the repeat waits for the first filing's lock */
	private void awaitWaiting() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 60000L;
		while (!MessageLocks.waitedFor(matter.getId(), "attached-1@example.com")) {
			if (System.currentTimeMillis() > deadline)
				fail("The repeat didn't wait for the first filing");
			Thread.sleep(20L);
		}
	}

	@Test
	public void testAttachmentSizes() throws Exception {
		String token = signIn(clerk);
		String eml = ("From: Ann Smith <ann@example.com>\r\nSubject: Empty\r\nMessage-ID: <empty-1@example.com>\r\n"
				+ "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n--b\r\n"
				+ "Content-Type: text/plain\r\n\r\nNothing attached, nearly.\r\n--b\r\nContent-Type: text/plain\r\n"
				+ "Content-Disposition: attachment; filename=\"empty.txt\"\r\n\r\n\r\n--b--\r\n");
		DbSnapshot before = snapshot();

		ApiResponse empty = fileEmail(token, request(documents, null, List.of(0)), eml.getBytes(StandardCharsets.UTF_8));

		assertEquals(List.of("attachments[0] empty"), fields(empty)); // named by its index, for the client to show by it
		assertEquals("Attachment 0 (empty.txt): The file is empty.",
				empty.json().path("fields").get(0).path("detail").textValue());
		before.assertUnchanged();

		// An attachment is never larger than the email it came in, which the limit has passed: the rule is the same
		Context.get().getConfig().setProperty(MAX_SIZE, "1");
		List<com.logicaldoc.healdan.api.model.FieldError> errors = new ArrayList<>();
		EmailFiling.checkAttachment(new MessageParts.Part(3, "Big.pdf", "application/pdf", 1024L * 1024L + 1L, false,
				null), errors);
		assertEquals(1, errors.size());
		assertEquals("attachments[3]", errors.get(0).field());
		assertEquals("too-large", errors.get(0).code());
		assertEquals("Attachment 3 (Big.pdf): The file is larger than the upload limit of 1 MB.",
				errors.get(0).detail());
		errors.clear();
		EmailFiling.checkAttachment(new MessageParts.Part(3, "Big.pdf", "application/pdf", 1024L * 1024L, false, null),
				errors);
		assertEquals(List.of(), errors);
	}

	@Test
	public void testAlreadyFiled() throws Exception {
		String token = signIn(clerk);
		ApiResponse first = fileEmail(token, request(documents, null, List.of(1)), eml(FIXTURE));
		assertEquals(first.toString(), 201, first.status());
		long docNumber = first.json().path("email").path("docNumber").asLong();
		Folder letters = fx.folder(documents, "Letters");
		DbSnapshot before = snapshot();

		// Into another folder of the matter, and into the same one
		for (Folder folder : List.of(letters, documents)) {
			ApiResponse again = fileEmail(token, request(folder, null, List.of(1)), eml(FIXTURE));

			assertEquals(again.toString(), 409, again.status());
			assertEquals("already-filed", again.code());
			assertEquals("This message is already in 998.1 as #" + docNumber + ".",
					again.json().path("detail").textValue());
			assertEquals(get("/api/v1/documents/" + docNumber).token(token).send().json(),
					again.json().path("document"));
		}

		// Nothing more is stored, and no doc # is taken
		before.assertUnchanged();
		assertEquals(List.of(), uploadFiles());
	}

	@Test
	public void testRefusalsStoreNothing() throws Exception {
		String token = signIn(clerk);
		String readerToken = signIn(reader);
		String outsiderToken = signIn(outsider);
		byte[] eml = eml(FIXTURE);
		DbSnapshot before = snapshot();

		ApiResponse rule = fileEmail(token, request(workspace, null, List.of(1)), eml);
		assertEquals(rule.toString(), 409, rule.status());
		assertEquals("filing-rule", rule.code());

		ApiResponse forbidden = fileEmail(readerToken, request(documents, null, List.of(1)), eml);
		assertEquals(forbidden.toString(), 403, forbidden.status());
		assertEquals("forbidden", forbidden.code());

		ApiResponse unreadable = fileEmail(outsiderToken, request(documents, null, List.of(1)), eml);
		assertEquals(unreadable.toString(), 404, unreadable.status());

		// The inline logo, a part the message hasn't got, and both at once
		assertEquals(List.of("attachments[0] not-an-attachment"),
				fields(fileEmail(token, request(documents, null, List.of(1, 0)), eml)));
		assertEquals(List.of("attachments[7] not-an-attachment"),
				fields(fileEmail(token, request(documents, null, List.of(7)), eml)));
		ApiResponse both = fileEmail(token, request(documents, null, List.of(0, 7, -1)), eml);
		assertEquals(List.of("attachments[0] not-an-attachment", "attachments[7] not-an-attachment",
				"attachments[-1] not-an-attachment"), fields(both));
		assertTrue(both.text(), both.json().path("fields").get(1).path("detail").textValue().contains("7"));

		// A ticked attachment whose extension upload.disallow lists, named with its index
		Context.get().getConfig().setProperty(DISALLOW, "exe, PDF");
		ApiResponse disallowed = fileEmail(token, request(documents, null, List.of(1)), eml);
		assertEquals(List.of("attachments[1] disallowed"), fields(disallowed));
		String detail = disallowed.json().path("fields").get(0).path("detail").textValue();
		assertTrue(detail, detail.contains("1") && detail.contains("Contract.pdf") && detail.contains(".pdf"));
		// and the email itself
		Context.get().getConfig().setProperty(DISALLOW, "eml");
		assertEquals(List.of("file disallowed"), fields(fileEmail(token, request(documents, null, null), eml)));
		Context.get().getConfig().setProperty(DISALLOW, "");

		// A required property with no value, and one that isn't known
		Template profile = TemplateDAO.get().findById(ProfileSchema.get().template().getId(), true);
		Attribute court = new Attribute();
		court.setLabel("Court");
		court.setType(Attribute.Type.STRING);
		court.setMandatory(true);
		court.setPosition(13);
		profile.getTemplateAttributes().put("court", court);
		TemplateDAO.get().store(profile);
		assertEquals(List.of("court required"), fields(fileEmail(token, request(documents, null, List.of(1)), eml)));
		assertEquals(List.of("colour unknown"),
				fields(fileEmail(token, request(documents, Map.of("court", "High Court", "colour", "red"), List.of(1)),
						eml)));

		before.assertUnchanged();
		assertEquals(List.of(), uploadFiles());
	}

	@Test
	public void testTheFilingChecksTheFolder() throws Exception {
		// EmailFiling checks the folder itself, not only the route that calls it
		File eml = Files.createTempFile("email-filing-", ".eml").toFile();
		DbSnapshot before = snapshot();
		try {
			FileUtils.writeByteArrayToFile(eml, eml(FIXTURE));
			assertProblem(eml, workspace, clerk, 409, "filing-rule");
			assertProblem(eml, documents, reader, 403, "forbidden");
			assertProblem(eml, documents, outsider, 404, "not-found");
		} finally {
			FileUtils.deleteQuietly(eml);
		}
		before.assertUnchanged();
	}

	@Test
	public void testAllOrNothing() throws Exception {
		String token = signIn(clerk);
		byte[] eml = eml(FIXTURE);

		// The second document is refused as it is stored
		doubled.filer.mode = FailingFiler.Mode.VETO;
		ApiResponse refused = post("/api/v1/test/emails").token(token)
				.part("email", request(documents, null, List.of(1, 2))).file("file", "message.eml", eml).send();

		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("refused", refused.code());
		assertEquals("Refused by the test.", refused.json().path("detail").textValue());
		assertEquals(2, doubled.filer.calls);
		assertUndone(1);

		// The second document is stored, but its version isn't recorded
		doubled.filer.mode = FailingFiler.Mode.UNRECORDED;
		doubled.filer.calls = 0;
		ApiResponse unrecorded = post("/api/v1/test/emails").token(token)
				.part("email", request(documents, null, List.of(1, 2))).file("file", "message.eml", eml).send();

		assertEquals(unrecorded.toString(), 500, unrecorded.status());
		assertEquals("server-error", unrecorded.code());
		assertEquals("The email wasn't filed: one of its documents wasn't stored completely, so nothing of the "
				+ "filing was kept. File it again.", unrecorded.json().path("detail").textValue());
		assertUndone(3);

		// The second document is stored, then CE's create fails
		doubled.filer.mode = FailingFiler.Mode.AFTER_COMMIT;
		doubled.filer.calls = 0;
		ApiResponse afterCommit = post("/api/v1/test/emails").token(token)
				.part("email", request(documents, null, List.of(1, 2))).file("file", "message.eml", eml).send();

		assertEquals(afterCommit.toString(), 500, afterCommit.status());
		assertEquals(EmailFiling.INCOMPLETE, afterCommit.json().path("detail").textValue());
		assertUndone(5);

		// Everything is stored, links included, then reading it back fails
		doubled.filer.mode = FailingFiler.Mode.NONE;
		List<String> linksSeen = new ArrayList<>();
		doubled.filing = new EmailFiling(doubled.filer) {
			@Override
			com.logicaldoc.healdan.api.model.DocumentView view(Document document, User user)
					throws PersistenceException {
				linksSeen.addAll(liveLinks());
				throw new IllegalStateException("Can't read a document back, in the test");
			}
		};
		ApiResponse unread = post("/api/v1/test/emails").token(token)
				.part("email", request(documents, null, List.of(1, 2))).file("file", "message.eml", eml).send();

		assertEquals(unread.toString(), 500, unread.status());
		assertEquals("server-error", unread.code());
		assertEquals(2, linksSeen.size());
		assertUndone(8);
		assertEquals(2, jdbc().queryForObject("select count(*) from ld_link where ld_deleted <> 0", Integer.class)
				.intValue());

		// Nothing undone counts as a copy: the email files afterwards
		ApiResponse filed = fileEmail(token, request(documents, null, List.of(1, 2)), eml);
		assertEquals(filed.toString(), 201, filed.status());
		assertEquals(2, filed.json().path("attachments").size());
		assertEquals(3, liveDocuments(documents));
		assertEquals(2, liveLinks().size());
	}

	@Test
	public void testAnUndoThatCantDeleteSaysSo() throws Exception {
		String token = signIn(clerk);
		EmailFiling.afterEmailStored = email -> {
			// CE's delete leaves an immutable document as it is, saying nothing, for anyone but an admin
			jdbc().update("update ld_document set ld_immutable = 1 where ld_id = ?", email.getId());
			DocumentDAO.get().evict(Document.class, email.getId());
			throw new IllegalStateException("Can't go on, in the test");
		};

		ApiResponse response = fileEmail(token, request(documents, null, List.of(1, 2)), eml(FIXTURE));

		assertEquals(response.toString(), 500, response.status());
		String left = jdbc().queryForObject(
				"select ld_customid from ld_document where ld_folderid = ? and ld_deleted = 0", String.class,
				documents.getId());
		assertEquals("The email was filed only in part, and documents #" + left
				+ " couldn't be taken back. Delete them, then file the email again.",
				response.json().path("detail").textValue());
	}

	@Test
	public void testACopyWithoutADocNumberIsSkipped() throws Exception {
		String token = signIn(clerk);
		byte[] eml = eml(FIXTURE);
		// As an email filed before Healdan, with no doc #
		Document copy = fx.upload(documents, "forwarded.eml", part(eml, 2), boss);
		awaitAsyncWork();
		jdbc().update("update ld_document set ld_customid = 'ABC' where ld_id = ?", copy.getId());
		DocumentDAO.get().evict(Document.class, copy.getId());

		ApiResponse filed = fileEmail(token, request(documents, null, List.of(1, 2)), eml);

		assertEquals(filed.toString(), 201, filed.status());
		assertEquals(1, filed.json().path("attachments").size());
		JsonNode skipped = filed.json().path("skipped");
		assertEquals(1, skipped.size());
		assertEquals(2, skipped.get(0).path("index").asInt());
		assertTrue(skipped.toString(), skipped.get(0).path("document").isNull());
	}

	@Test
	public void testNotAnEmail() throws Exception {
		String token = signIn(clerk);
		DbSnapshot before = snapshot();

		ApiResponse response = fileEmail(token, request(documents, null, null), docx());

		assertEquals(List.of("file not-an-email"), fields(response));
		before.assertUnchanged();
		assertEquals(List.of(), uploadFiles());
	}

	@Test
	public void testBadRequests() throws Exception {
		String token = signIn(clerk);
		byte[] eml = eml(FIXTURE);
		DbSnapshot before = snapshot();

		ApiResponse noEmail = post("/api/v1/emails").token(token).file("file", "message.eml", eml).send();
		assertBadRequest(noEmail);
		assertEquals("Send the filing's details as JSON in a part named email.",
				noEmail.json().path("detail").textValue());
		ApiResponse noFile = post("/api/v1/emails").token(token).part("email", request(documents, null, null)).send();
		assertBadRequest(noFile);
		assertEquals("Send the email in a part named file.", noFile.json().path("detail").textValue());
		assertBadRequest(post("/api/v1/emails").token(token).part("email", "{}").file("file", "message.eml", eml)
				.send());
		assertBadRequest(post("/api/v1/emails").token(token).part("email", "null").file("file", "message.eml", eml)
				.send());
		ApiResponse unknown = post("/api/v1/emails").token(token)
				.part("email", "{\"folderId\": %d, \"name\": \"x.eml\"}".formatted(documents.getId()))
				.file("file", "message.eml", eml).send();
		assertBadRequest(unknown);
		assertEquals("The request has a field the server doesn't know: name.", unknown.json().path("detail").textValue());

		ApiResponse noToken = post("/api/v1/emails").part("email", request(documents, null, null))
				.file("file", "message.eml", eml).send();
		assertEquals(noToken.toString(), 401, noToken.status());

		before.assertUnchanged();
	}

	/** Every document filed into the Documents folder is in the trash, as is each link, and nothing is left over */
	private void assertUndone(int filings) throws Exception {
		assertEquals(0, liveDocuments(documents));
		assertEquals(List.of(), liveLinks());
		List<Long> trashed = jdbc().queryForList(
				"select ld_id from ld_document where ld_folderid = ? and ld_deleted <> 0 order by ld_id", Long.class,
				documents.getId());
		assertEquals(filings, trashed.size());
		for (long id : trashed)
			assertEquals(1, jdbc().queryForObject(
					"select count(*) from ld_history where ld_docid = ? and ld_event = 'event.deleted' and ld_comment = 'Filing undone'",
					Integer.class, id).intValue());
		assertEquals(List.of(), uploadFiles());
	}

	private void assertProblem(File eml, Folder folder, User as, int status, String code) {
		try {
			EmailFiling.get().file(eml, new NewEmail(folder.getId(), null, List.of(1)), new Caller(as, null, null));
			fail("Filed into folder " + folder.getId() + " as " + as.getUsername());
		} catch (ApiProblem problem) {
			assertEquals(problem.detail(), status, problem.status());
			assertEquals(code, problem.code());
		}
	}

	private ApiResponse fileEmail(String token, String request, byte[] eml) {
		return post("/api/v1/emails").token(token).part("email", request).file("file", "message.eml", eml).send();
	}

	private static String request(Folder folder, Map<String, Object> properties, List<Integer> attachments)
			throws JsonProcessingException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("folderId", folder.getId());
		if (properties != null)
			body.put("properties", properties);
		if (attachments != null)
			body.put("attachments", attachments);
		return ApiJson.mapper().writeValueAsString(body);
	}

	private JsonNode properties(String token, long docNumber) {
		ApiResponse response = get("/api/v1/documents/" + docNumber + "/properties").token(token).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json().path("items");
	}

	private static JsonNode value(JsonNode items, String name) {
		return field(items, name).path("value");
	}

	private static JsonNode field(JsonNode items, String name) {
		for (JsonNode field : items)
			if (name.equals(field.path("name").textValue()))
				return field;
		throw new AssertionError("No field " + name + " in " + items);
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

	private long id(long docNumber) {
		return jdbc().queryForObject("select ld_id from ld_document where ld_customid = ?", Long.class,
				Long.toString(docNumber));
	}

	private int liveDocuments(Folder folder) {
		return jdbc().queryForObject("select count(*) from ld_document where ld_folderid = ? and ld_deleted = 0",
				Integer.class, folder.getId());
	}

	/** The links not deleted, as type, first document's id and second's */
	private List<String> liveLinks() {
		return jdbc().query("select ld_type, ld_docid1, ld_docid2 from ld_link where ld_deleted = 0 order by ld_id",
				(rs, row) -> rs.getString(1) + " " + rs.getLong(2) + " " + rs.getLong(3));
	}

	/** The usual tables, and the links */
	private DbSnapshot snapshot() {
		return DbSnapshot.take(jdbc(), List.of("ld_link"));
	}

	/** The Documents folder of a new matter of client 998 in a Store */
	private Folder matterDocuments(Folder inStore, long number, String name) throws Exception {
		Folder matter = FolderDAO.get().findById(
				fx.matter(ClientDAO.get().findByNumber(998), number, name, inStore, "Documents").getFolderId());
		return FolderDAO.get().findByName(matter, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	/** As the old UI's folder edit does: the listener records the local value and passes it down */
	private void setFolderValue(Folder folder, String name, String value) throws Exception {
		Folder current = FolderDAO.get().findById(folder.getId(), true);
		current.setValue(name, value);
		FolderHistory transaction = fx.folderHistory(fx.admin());
		transaction.setEvent(FolderEvent.CHANGED);
		FolderDAO.get().store(current, transaction);
	}

	private static byte[] eml(String fixture) throws IOException {
		try (var in = EmailFilingTest.class.getResourceAsStream("/email/" + fixture)) {
			if (in == null)
				throw new IOException("No fixture " + fixture);
			return in.readAllBytes();
		}
	}

	/** One part of an email, as MessageParts writes it */
	private static byte[] part(byte[] eml, int index) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		MessageParts.write(MailUtil.readMime(new ByteArrayInputStream(eml)), index, out);
		return out.toByteArray();
	}

	/** A small Word file, which isn't an email */
	private static byte[] docx() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
			zip.write("<?xml version=\"1.0\"?><Types/>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("word/document.xml"));
			zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	private static List<String> uploadFiles() {
		String[] names = new File(System.getProperty("java.io.tmpdir")).list();
		Set<String> uploads = new TreeSet<>();
		for (String name : names == null ? new String[0] : names)
			if (name.startsWith("healdan-upload"))
				uploads.add(name);
		return List.copyOf(uploads);
	}

	/**
	 * A filer that fails on its second document: refused as it's stored, as by
	 * a rule; stored, with its version not recorded, as CE leaves a document
	 * whose version record couldn't be written; or stored, then CE's create
	 * failing, as it can after it commits the record
	 */
	static final class FailingFiler extends Filer {

		enum Mode {
			NONE, VETO, UNRECORDED, AFTER_COMMIT
		}

		Mode mode = Mode.NONE;

		int calls;

		@Override
		public Document file(Folder folder, String name, Map<String, JsonNode> properties, File upload, Caller caller,
				Consumer<Document> stored) {
			if (++calls != 2 || mode == Mode.NONE)
				return super.file(folder, name, properties, upload, caller, stored);
			if (mode == Mode.VETO)
				throw new IllegalStateException("Can't file a document", new HealdanVetoException("Refused by the test."));
			if (mode == Mode.AFTER_COMMIT)
				return super.file(folder, name, properties, upload, caller, stored);
			// CE writes the version record after it stores the document, and only logs a failure (as in FilingTest)
			var jdbc = new org.springframework.jdbc.core.JdbcTemplate(Context.get(javax.sql.DataSource.class));
			jdbc.execute("alter table ld_version add constraint test_unrecorded check (ld_filename <> '"
					+ name.replace("'", "''") + "')");
			try {
				return super.file(folder, name, properties, upload, caller, stored);
			} finally {
				jdbc.execute("alter table ld_version drop constraint test_unrecorded");
			}
		}

		@Override
		Document create(File upload, Document vo, DocumentHistory transaction) throws PersistenceException {
			Document created = super.create(upload, vo, transaction);
			if (mode == Mode.AFTER_COMMIT && calls == 2)
				throw new PersistenceException("Failed after the commit, in the test");
			return created;
		}
	}

	/** {@code POST /v1/emails}, filed with a {@link FailingFiler} */
	@Path("/v1/test/emails")
	@Produces(MediaType.APPLICATION_JSON)
	public static class DoubledEmails {

		final FailingFiler filer = new FailingFiler();

		/** What files, which a test can replace */
		EmailFiling filing = new EmailFiling(filer);

		@POST
		@Consumes(MediaType.MULTIPART_FORM_DATA)
		public Response file(@Multipart(value = "email", required = false) String email,
				@Multipart(value = "file", required = false) Attachment file,
				@jakarta.ws.rs.core.Context UriInfo uri) {
			return EmailResource.fileWith(email, file, uri, filing);
		}
	}
}
