// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentLink;
import com.logicaldoc.core.document.DocumentLinkDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.email.EmailBody;

/**
 * {@code /v1/documents/{n}/message} and what is under it (M6 spec §6.6, §9.1, §9.4): a filed email read as a message.
 * <p>
 * Clerk reads, previews, downloads and writes in matter 998.1; Preview-only previews alone; Reader may only read;
 * Outsider has no grant.
 */
public class MessageResourceTest extends ApiTestCase {

	private static final String PDF = "%PDF-1.4\n% Healdan test fixture\n%%EOF\n";

	private User clerk;

	private User previewer;

	private User reader;

	private User outsider;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		clerk = fx.user("clerk");
		previewer = fx.user("previewer");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD,
				Permission.PREVIEW);
		fx.grant(store, previewer.getUserGroup(), Permission.READ, Permission.PREVIEW);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private Document file(String fixture) throws Exception {
		return file(fixture, fixture);
	}

	private Document file(String name, String fixture) throws Exception {
		Document d = fx.upload(documents, name, fixture(fixture), fx.admin());
		awaitAsyncWork();
		return DocumentDAO.get().findById(d.getId(), true);
	}

	private static byte[] fixture(String name) throws IOException {
		try (var in = MessageResourceTest.class.getResourceAsStream("/email/" + name)) {
			if (in == null)
				throw new IOException("No fixture " + name);
			return in.readAllBytes();
		}
	}

	private static String path(Document d, String rest) {
		return "/api/v1/documents/" + d.getCustomId() + "/message" + rest;
	}

	private int downloads() {
		return jdbc().queryForObject("select count(*) from ld_history where ld_event = 'event.downloaded'",
				Integer.class).intValue();
	}

	@Test
	public void testMessageListsHeadersAndAttachments() throws Exception {
		Document email = file("parts-attached.eml");

		ApiResponse response = get(path(email, "")).token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode message = response.json();
		assertEquals("Contract for review", message.path("subject").textValue());
		assertEquals("Ann Smith <ann@example.com>", message.path("from").textValue());
		assertEquals("Bob Jones <bob@example.com>", message.path("to").textValue());
		assertTrue(message.path("cc").isNull());
		assertEquals("2026-11-03T10:00:00Z", message.path("sent").textValue());
		assertEquals(path(email, "/body"), message.path("body").textValue());

		// The logo the body shows isn't an attachment
		JsonNode attachments = message.path("attachments");
		assertEquals(2, attachments.size());
		assertEquals(1, attachments.get(0).path("index").asInt());
		assertEquals("Contract.pdf", attachments.get(0).path("name").textValue());
		assertEquals("application/pdf", attachments.get(0).path("type").textValue());
		assertEquals(PDF.length(), attachments.get(0).path("size").asInt());
		assertFalse(attachments.get(0).path("message").booleanValue());
		assertTrue(attachments.get(0).path("docNumber").isNull());
		assertEquals(2, attachments.get(1).path("index").asInt());
		assertTrue(attachments.get(1).path("message").booleanValue());
	}

	@Test
	public void testAMsgIsShownAsTheSameMessage() throws Exception {
		Document email = file("test.msg");
		String token = signIn(clerk);

		JsonNode message = get(path(email, "")).token(token).send().json();
		ApiResponse body = get(path(email, "/body")).token(token).send();
		ApiResponse part = get(path(email, "/parts/0")).token(token).send();

		assertFalse(message.path("subject").isNull());
		assertEquals("Bestellu.pdf", message.path("attachments").get(0).path("name").textValue());
		assertEquals(200, body.status());
		assertTrue(body.text().startsWith("<!DOCTYPE html>"));
		assertEquals(200, part.status());
		assertEquals(message.path("attachments").get(0).path("size").asLong(), part.body().length);
		assertEquals("%PDF", new String(part.body(), 0, 4, StandardCharsets.US_ASCII));
	}

	@Test
	public void testAttachmentAlreadyFiledShowsItsNumber() throws Exception {
		Document email = file("parts-attached.eml");
		String token = signIn(clerk);
		ApiResponse filed = post(path(email, "/attachments/1/file")).token(token).send();
		assertEquals(filed.toString(), 201, filed.status());

		JsonNode attachments = get(path(email, "")).token(token).send().json().path("attachments");

		assertEquals(filed.json().path("docNumber").asLong(), attachments.get(0).path("docNumber").asLong());
		assertTrue(attachments.get(1).path("docNumber").isNull());
	}

	@Test
	public void testBodyIsACleanedPageWithItsOwnPolicy() throws Exception {
		Document email = file("body-hostile.eml");
		String token = signIn(clerk);

		ApiResponse without = get(path(email, "/body")).token(token).send();
		ApiResponse with = get(path(email, "/body?pictures=true")).token(token).send();

		assertEquals(without.toString(), 200, without.status());
		assertEquals("text/html;charset=utf-8", without.header("Content-Type").replace(" ", ""));
		assertEquals(EmailBody.policy(false), without.header("Content-Security-Policy"));
		assertEquals("nosniff", without.header("X-Content-Type-Options"));
		assertEquals(EmailBody.policy(true), with.header("Content-Security-Policy"));
		assertFalse(without.text().contains("<script"));
		assertFalse(without.text().contains("tracker.invalid"));
		assertTrue(without.text().contains("data:image/png;base64,"));
		assertTrue(with.text().contains("tracker.invalid"));
		assertEquals(Integer.toString(without.body().length), without.header("Content-Length"));
	}

	@Test
	public void testBodyOnTheWebSessionRoute() throws Exception {
		Document email = file("body-hostile.eml");

		ApiResponse response = get(path(email, "/body")).webSession(signInWeb(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(EmailBody.policy(false), response.header("Content-Security-Policy"));
	}

	@Test
	public void testPartDownloadsAsAnAttachmentAndIsRecorded() throws Exception {
		Document email = file("parts-attached.eml");

		ApiResponse response = get(path(email, "/parts/1")).token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertTrue(response.header("Content-Disposition"), response.header("Content-Disposition")
				.startsWith("attachment; filename=\"Contract.pdf\""));
		assertEquals("application/pdf", response.header("Content-Type"));
		assertEquals("nosniff", response.header("X-Content-Type-Options"));
		assertArrayEquals(PDF.getBytes(StandardCharsets.US_ASCII), response.body());
		assertEquals(Integer.toString(PDF.length()), response.header("Content-Length"));
		assertEquals(1, downloads());
	}

	@Test
	public void testPartNeedsDownload() throws Exception {
		Document email = file("parts-attached.eml");

		ApiResponse response = get(path(email, "/parts/1")).token(signIn(previewer)).send();

		assertEquals(response.toString(), 403, response.status());
		assertEquals("forbidden", response.code());
		assertEquals(0, downloads());
	}

	@Test
	public void testPdfAndPictureShowInPlaceUnderPreviewWithNoHistory() throws Exception {
		Document email = file("parts-attached.eml");
		Document pictured = fileBytes("pictured.eml", emailWithPicture());
		String token = signIn(previewer);

		ApiResponse pdf = get(path(email, "/parts/1?inline=true")).token(token).send();
		ApiResponse picture = get(path(pictured, "/parts/0?inline=true")).token(token).send();

		assertEquals(pdf.toString(), 200, pdf.status());
		assertEquals("application/pdf", pdf.header("Content-Type"));
		assertEquals("inline", pdf.header("Content-Disposition"));
		assertEquals("sandbox", pdf.header("Content-Security-Policy"));
		assertEquals("nosniff", pdf.header("X-Content-Type-Options"));
		assertArrayEquals(PDF.getBytes(StandardCharsets.US_ASCII), pdf.body());
		assertEquals(picture.toString(), 200, picture.status());
		assertEquals("image/png", picture.header("Content-Type"));
		assertEquals("sandbox", picture.header("Content-Security-Policy"));
		assertEquals(0, downloads());
	}

	/** An email with a PNG attached (not one its body shows) */
	private static byte[] emailWithPicture() {
		return ("From: Ann <ann@example.com>\r\nSubject: A picture\r\nMessage-ID: <picture@example.com>\r\n"
				+ "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n--b\r\n"
				+ "Content-Type: text/plain\r\n\r\nHello\r\n--b\r\nContent-Type: image/png\r\n"
				+ "Content-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"dot.png\"\r\n\r\n"
				+ "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==\r\n"
				+ "--b--\r\n").getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void testAnythingElseIsNotShownInPlace() throws Exception {
		Document email = file("parts-attached.eml");

		ApiResponse response = get(path(email, "/parts/2?inline=true")).token(signIn(clerk)).send();

		assertEquals(response.toString(), 404, response.status());
		assertEquals("no-preview", response.code());
	}

	@Test
	public void testInlinePartNeedsPreview() throws Exception {
		Document email = file("parts-attached.eml");

		ApiResponse response = get(path(email, "/parts/1?inline=true")).token(signIn(reader)).send();

		assertEquals(response.toString(), 403, response.status());
	}

	@Test
	public void testUnknownPartIs404() throws Exception {
		Document email = file("parts-attached.eml");

		ApiResponse response = get(path(email, "/parts/9")).token(signIn(clerk)).send();

		assertEquals(response.toString(), 404, response.status());
		assertEquals("not-found", response.code());
	}

	@Test
	public void testSaveToMatterFilesTheAttachmentNextToTheEmailLinked() throws Exception {
		Document email = file("parts-attached.eml");
		String token = signIn(clerk);

		ApiResponse response = post(path(email, "/attachments/1/file")).token(token).send();

		assertEquals(response.toString(), 201, response.status());
		JsonNode filed = response.json();
		assertEquals("Contract.pdf", filed.path("name").textValue());
		assertEquals("/api/v1/documents/" + filed.path("docNumber").asLong(), response.header("Location"));
		Document saved = DocumentDAO.get().findByCustomId(filed.path("docNumber").asText(), Tenant.DEFAULT_ID);
		assertEquals(documents.getId(), saved.getFolder().getId());
		List<DocumentLink> links = DocumentLinkDAO.get().findByDocId(email.getId(), "attachment");
		assertEquals(1, links.size());
		assertEquals(email.getId(), links.get(0).getDocument1().getId());
		assertEquals(saved.getId(), links.get(0).getDocument2().getId());
		assertArrayEquals(PDF.getBytes(StandardCharsets.US_ASCII),
				get("/api/v1/documents/" + filed.path("docNumber").asLong() + "/content").token(token).send().body());
	}

	@Test
	public void testSavingAgainFilesAnotherCopy() throws Exception {
		Document email = file("parts-attached.eml");
		String token = signIn(clerk);

		ApiResponse first = post(path(email, "/attachments/1/file")).token(token).send();
		ApiResponse second = post(path(email, "/attachments/1/file")).token(token).send();

		assertEquals(201, first.status());
		assertEquals(second.toString(), 201, second.status());
		assertNotEquals(first.json().path("docNumber").asLong(), second.json().path("docNumber").asLong());
	}

	@Test
	public void testAnAttachedMessageTheMatterHoldsIsAlreadyFiled() throws Exception {
		Document email = file("parts-attached.eml");
		String token = signIn(clerk);

		ApiResponse first = post(path(email, "/attachments/2/file")).token(token).send();
		ApiResponse second = post(path(email, "/attachments/2/file")).token(token).send();

		assertEquals(first.toString(), 201, first.status());
		assertEquals("Original request.eml", first.json().path("name").textValue());
		assertEquals(second.toString(), 409, second.status());
		assertEquals("already-filed", second.code());
		assertEquals(first.json().path("docNumber").asLong(), second.json().path("document").path("docNumber").asLong());
	}

	@Test
	public void testSaveToMatterNeedsWriteOnTheFolder() throws Exception {
		Document email = file("parts-attached.eml");
		Folder store = FolderDAO.get().findById(documents.getParentId());
		User downloader = fx.user("downloader");
		fx.grant(store, downloader.getUserGroup(), Permission.READ, Permission.DOWNLOAD);

		ApiResponse response = post(path(email, "/attachments/1/file")).token(signIn(downloader)).send();

		assertEquals(response.toString(), 403, response.status());
	}

	@Test
	public void testSaveToMatterTakesProperties() throws Exception {
		Document email = file("parts-attached.eml");

		ApiResponse response = post(path(email, "/attachments/1/file")).token(signIn(clerk))
				.json("{\"properties\":{\"nosuchproperty\":\"x\"}}").send();

		assertEquals(response.toString(), 422, response.status());
	}

	@Test
	public void testADocumentThatIsNotAnEmailIs404() throws Exception {
		Document pdf = fx.upload(documents, "a.pdf", PDF.getBytes(StandardCharsets.US_ASCII), fx.admin());
		String token = signIn(clerk);

		for (String rest : new String[] { "", "/body", "/parts/0" }) {
			ApiResponse response = get(path(pdf, rest)).token(token).send();
			assertEquals(response.toString(), 404, response.status());
			assertEquals("not-an-email", response.code());
		}
		ApiResponse saved = post(path(pdf, "/attachments/0/file")).token(token).send();
		assertEquals(saved.toString(), 404, saved.status());
		assertEquals("not-an-email", saved.code());
	}

	@Test
	public void testAnEmailThatIsNotReadableIs404NotForbidden() throws Exception {
		Document email = file("parts-attached.eml");
		String token = signIn(outsider);

		for (String rest : new String[] { "", "/body", "/parts/1", "/parts/1?inline=true" }) {
			ApiResponse response = get(path(email, rest)).token(token).send();
			assertEquals(response.toString(), 404, response.status());
			assertEquals("not-found", response.code());
		}
		assertEquals(404, post(path(email, "/attachments/1/file")).token(token).send().status());
	}

	@Test
	public void testAFileThatIsNotAMessageIs404() throws Exception {
		Document odd = file("broken.eml", "corrupt.msg");
		odd = DocumentDAO.get().findById(odd.getId());

		ApiResponse response = get(path(odd, "")).token(signIn(clerk)).send();

		assertEquals(response.toString(), 404, response.status());
		assertEquals("not-an-email", response.code());
	}

	// ---- Fix round 1 ----

	/** An email with these attachments, each a text file named and filled as given */
	private static byte[] emailWith(String... nameAndContent) {
		StringBuilder eml = new StringBuilder("From: Ann <ann@example.com>\r\nTo: Bob <bob@example.com>\r\n"
				+ "Subject: Attachments\r\nMessage-ID: <fix1-").append(System.nanoTime()).append("@example.com>\r\n"
						+ "Date: Tue, 03 Nov 2026 10:00:00 +0000\r\nMIME-Version: 1.0\r\n"
						+ "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n--b\r\n"
						+ "Content-Type: text/plain\r\n\r\nHello\r\n");
		for (int i = 0; i < nameAndContent.length; i += 2)
			eml.append("--b\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=\"")
					.append(nameAndContent[i]).append("\"\r\n\r\n").append(nameAndContent[i + 1]).append("\r\n");
		return eml.append("--b--\r\n").toString().getBytes(StandardCharsets.UTF_8);
	}

	private Document fileBytes(String name, byte[] content) throws Exception {
		Document d = fx.upload(documents, name, content, fx.admin());
		awaitAsyncWork();
		return DocumentDAO.get().findById(d.getId(), true);
	}

	/** The email filed through the API by the clerk with a Comments property, as a person files it */
	private Document fileWithComments(String comments) throws Exception {
		ApiResponse filed = post("/api/v1/documents").token(signIn(clerk))
				.part("document", "{\"folderId\":" + documents.getId() + ",\"properties\":{\"comments\":\"" + comments
						+ "\"},\"checkOut\":false}")
				.file("file", "carried.eml", emailWith("Agreement.txt", "the agreement")).send();
		assertEquals(filed.toString(), 201, filed.status());
		awaitAsyncWork();
		return DocumentDAO.get().findByCustomId(filed.json().path("docNumber").asText(), Tenant.DEFAULT_ID);
	}

	private String propertyOf(long docNumber, String name, String token) {
		for (JsonNode field : get("/api/v1/documents/" + docNumber + "/properties").token(token).send().json().path("items"))
			if (name.equals(field.path("name").textValue()))
				return field.path("value").isNull() ? null : field.path("value").asText();
		return "no such property";
	}

	@Test
	public void testSaveToMatterCarriesThePropertiesTheEmailWasFiledWith() throws Exception {
		Document email = fileWithComments("From the email");
		String token = signIn(clerk);

		ApiResponse saved = post(path(email, "/attachments/0/file")).token(token).send();

		assertEquals(saved.toString(), 201, saved.status());
		assertEquals("From the email", propertyOf(saved.json().path("docNumber").asLong(), "comments", token));
		// The email's own fields stay the email's
		assertNull(propertyOf(saved.json().path("docNumber").asLong(), "emailsubject", token));
	}

	@Test
	public void testPropertiesSentWinOverTheEmails() throws Exception {
		Document email = fileWithComments("From the email");
		String token = signIn(clerk);

		ApiResponse saved = post(path(email, "/attachments/0/file")).token(token)
				.json("{\"properties\":{\"comments\":\"Sent\"}}").send();

		assertEquals(saved.toString(), 201, saved.status());
		assertEquals("Sent", propertyOf(saved.json().path("docNumber").asLong(), "comments", token));
	}

	@Test
	public void testTwoAttachmentsOfOneNameAndDifferentSizesAreTold() throws Exception {
		Document email = fileBytes("same.eml", emailWith("Agreement.txt", "short", "Agreement.txt", "a much longer one"));
		String token = signIn(clerk);

		ApiResponse second = post(path(email, "/attachments/1/file")).token(token).send();

		assertEquals(second.toString(), 201, second.status());
		JsonNode attachments = get(path(email, "")).token(token).send().json().path("attachments");
		assertEquals(2, attachments.size());
		assertTrue(attachments.get(0).path("docNumber").isNull());
		assertEquals(second.json().path("docNumber").asLong(), attachments.get(1).path("docNumber").asLong());
		// The other can still be saved, and then both show their own #
		ApiResponse first = post(path(email, "/attachments/0/file")).token(token).send();
		assertEquals(first.toString(), 201, first.status());
		attachments = get(path(email, "")).token(token).send().json().path("attachments");
		assertEquals(first.json().path("docNumber").asLong(), attachments.get(0).path("docNumber").asLong());
		assertEquals(second.json().path("docNumber").asLong(), attachments.get(1).path("docNumber").asLong());
	}

	@Test
	public void testTwoIdenticalAttachmentsAreToldByOccurrence() throws Exception {
		Document email = fileBytes("twins.eml", emailWith("Agreement.txt", "same", "Agreement.txt", "same"));
		String token = signIn(clerk);

		ApiResponse one = post(path(email, "/attachments/0/file")).token(token).send();

		assertEquals(one.toString(), 201, one.status());
		JsonNode attachments = get(path(email, "")).token(token).send().json().path("attachments");
		int filed = 0;
		for (JsonNode attachment : attachments)
			if (!attachment.path("docNumber").isNull())
				filed++;
		assertEquals(1, filed);
		ApiResponse other = post(path(email, "/attachments/1/file")).token(token).send();
		assertEquals(other.toString(), 201, other.status());
		attachments = get(path(email, "")).token(token).send().json().path("attachments");
		assertFalse(attachments.get(0).path("docNumber").isNull());
		assertFalse(attachments.get(1).path("docNumber").isNull());
		assertNotEquals(attachments.get(0).path("docNumber").asLong(), attachments.get(1).path("docNumber").asLong());
	}

	@Test
	public void testTheBodyAsksForNoReferrer() throws Exception {
		Document email = file("body-hostile.eml");

		ApiResponse response = get(path(email, "/body?pictures=true")).token(signIn(clerk)).send();

		assertEquals("no-referrer", response.header("Referrer-Policy"));
	}

	@Test
	public void testAnInlinePartIsNotAnAttachmentForAnyRoute() throws Exception {
		Document email = file("parts-attached.eml");
		String token = signIn(clerk);

		// Part 0 is the logo the body shows: /message doesn't list it, so nothing else serves it
		assertEquals(404, get(path(email, "/parts/0")).token(token).send().status());
		assertEquals(404, get(path(email, "/parts/0?inline=true")).token(token).send().status());
		assertEquals(404, post(path(email, "/attachments/0/file")).token(token).send().status());
	}

	@Test
	public void testAReadOnlyUserMayNotReadTheMessageOrItsBody() throws Exception {
		Document email = file("parts-attached.eml");
		String token = signIn(reader);

		assertEquals(403, get(path(email, "")).token(token).send().status());
		assertEquals(403, get(path(email, "/body")).token(token).send().status());
	}

	@Test
	public void testAPasswordProtectedEmailIsRefused() throws Exception {
		Document email = file("parts-attached.eml");
		com.logicaldoc.core.document.DocumentHistory history = fx.documentHistory(fx.admin());
		history.setEvent(com.logicaldoc.core.document.DocumentEvent.CHANGED);
		DocumentDAO.get().setPassword(email.getId(), "S3cret-pw!", history);
		String token = signIn(clerk);

		assertEquals(403, get(path(email, "")).token(token).send().status());
		assertEquals(403, get(path(email, "/body")).token(token).send().status());
		assertEquals(403, get(path(email, "/parts/1")).token(token).send().status());
	}

	@Test
	public void testHeadOnAPartRecordsNothingAndSendsNoBody() throws Exception {
		Document email = file("parts-attached.eml");

		ApiResponse response = head(path(email, "/parts/1")).token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(Integer.toString(PDF.length()), response.header("Content-Length"));
		assertEquals(0, response.body().length);
		assertEquals(0, downloads());
	}

	@Test
	public void testAPartLeavesNoTemporaryFileBehind() throws Exception {
		Document email = file("parts-attached.eml");
		String token = signIn(clerk);
		java.util.List<String> before = uploadFiles();

		assertEquals(200, get(path(email, "/parts/1")).token(token).send().status());
		assertEquals(201, post(path(email, "/attachments/1/file")).token(token).send().status());
		assertEquals(200, head(path(email, "/parts/1")).token(token).send().status());

		assertEquals(before, uploadFiles());
	}

	@Test
	public void testSaveToMatterOfAnAttachmentOfAMsg() throws Exception {
		Document email = file("test.msg");

		ApiResponse response = post(path(email, "/attachments/0/file")).token(signIn(clerk)).send();

		assertEquals(response.toString(), 201, response.status());
		assertEquals("Bestellu.pdf", response.json().path("name").textValue());
	}

	@Test
	public void testAFailureAfterTheLinkTakesTheFilingBack() throws Exception {
		Document email = file("parts-attached.eml");
		String token = signIn(clerk);
		MessageResource.afterLinked = copy -> {
			throw new IllegalStateException("The answer couldn't be made");
		};
		try {
			ApiResponse response = post(path(email, "/attachments/1/file")).token(token).send();
			assertEquals(response.toString(), 500, response.status());
		} finally {
			MessageResource.afterLinked = null;
		}

		assertEquals(0, DocumentLinkDAO.get().findByDocId(email.getId(), "attachment").size());
		JsonNode attachments = get(path(email, "")).token(token).send().json().path("attachments");
		assertTrue(attachments.get(0).path("docNumber").isNull());
		assertEquals(0, jdbc().queryForObject(
				"select count(*) from ld_document where ld_filename = 'Contract.pdf' and ld_deleted = 0", Integer.class)
				.intValue());
	}
}
