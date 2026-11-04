// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.Test;

import com.logicaldoc.core.communication.MailUtil;
import com.logicaldoc.healdan.email.MessageParts.Part;
import com.logicaldoc.healdan.support.LogCapture;

import jakarta.mail.internet.MimeMessage;

/**
 * Spec §6.6: the walk of an {@code eml}'s parts that the inspect call and the
 * filing call share, so that an index means the same part in both.
 */
public class MessagePartsTest {

	/** What parts-attached.eml and parts-signed.eml hold as their PDF */
	private static final byte[] PDF = "%PDF-1.4\n% Healdan test fixture\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

	private static byte[] bytes(String name) throws IOException {
		return Files.readAllBytes(Path.of("src/test/resources/email/" + name));
	}

	private static MimeMessage parse(byte[] eml) throws Exception {
		MimeMessage message = MailUtil.readMime(new ByteArrayInputStream(eml));
		assertNotNull(message);
		return message;
	}

	private static MimeMessage fixture(String name) throws Exception {
		return parse(bytes(name));
	}

	/** A message made of a header block and the given text */
	private static MimeMessage built(String contentType, String body) throws Exception {
		return parse(("From: ann@example.com\nTo: bob@example.com\nSubject: Built in a test\n"
				+ "Message-ID: <built@example.com>\nMIME-Version: 1.0\nContent-Type: " + contentType + "\n\n" + body)
				.getBytes(StandardCharsets.UTF_8));
	}

	/** A multipart/mixed message of the given parts, each a header block, a blank line and a body */
	private static MimeMessage mixed(String... parts) throws Exception {
		StringBuilder body = new StringBuilder();
		for (String part : parts)
			body.append("--b\n").append(part).append("\n");
		return built("multipart/mixed; boundary=\"b\"", body.append("--b--\n").toString());
	}

	private static String html(String markup) {
		return "Content-Type: text/html; charset=utf-8\n\n" + markup + "\n";
	}

	private static String file(String headers) {
		return headers + "\n\ndata\n";
	}

	private static List<String> names(MimeMessage message) {
		return MessageParts.list(message).stream().map(Part::name).toList();
	}

	@Test
	public void testAttachmentsInlineAndAttachedMessage() throws Exception {
		List<Part> parts = MessageParts.list(fixture("parts-attached.eml"));
		assertEquals(3, parts.size());

		// The logo the HTML names by cid:, whose index comes first as the walk is depth first
		Part logo = parts.get(0);
		assertEquals(0, logo.index());
		assertEquals("logo.png", logo.name());
		assertEquals("image/png", logo.type());
		assertEquals(70, logo.size());
		assertTrue(logo.inline());
		assertFalse(logo.attachedMessage());
		assertEquals(null, logo.messageId());

		// The PDF, whose size is its decoded length, not the base64 text's
		Part pdf = parts.get(1);
		assertEquals(1, pdf.index());
		assertEquals("Contract.pdf", pdf.name());
		assertEquals("application/pdf", pdf.type());
		assertEquals(PDF.length, pdf.size());
		assertFalse(pdf.inline());
		assertFalse(pdf.attachedMessage());

		// The forwarded message, named after its subject
		Part forwarded = parts.get(2);
		assertEquals(2, forwarded.index());
		assertEquals("Original request.eml", forwarded.name());
		assertEquals("message/rfc822", forwarded.type());
		assertEquals("forwarded-1@example.com", forwarded.messageId());
		assertFalse(forwarded.inline());
		assertTrue(forwarded.attachedMessage());
		assertTrue(forwarded.size() > 0);
	}

	@Test
	public void testUnreferencedImageIsAnAttachment() throws Exception {
		List<Part> parts = MessageParts.list(fixture("parts-unreferenced.eml"));
		assertEquals(2, parts.size());

		// An image with a Content-ID that no cid: names, and no inline disposition
		assertEquals(new Part(0, "banner.png", "image/png", 26, false, null), parts.get(0));
		// A part with a name and no disposition at all
		assertEquals(new Part(1, "Notes.txt", "application/octet-stream", 21, false, null), parts.get(1));
	}

	@Test
	public void testSignedListsItsContentNotTheSignature() throws Exception {
		List<Part> parts = MessageParts.list(fixture("parts-signed.eml"));
		assertEquals(List.of("Signed Contract.pdf"), parts.stream().map(Part::name).toList());
		assertEquals(PDF.length, parts.get(0).size());
	}

	/** The older S/MIME type name, named so that it would be listed if it were walked */
	private static MimeMessage pkcs7() throws Exception {
		return built("application/x-pkcs7-mime; name=\"smime.p7m\"", "ZGF0YQ==\n");
	}

	@Test
	public void testEncryptedListsNothing() throws Exception {
		// parts-encrypted.eml has a name and an attachment disposition, so it would be listed if it were walked
		assertEquals(List.of(), MessageParts.list(fixture("parts-encrypted.eml")));
		assertEquals(List.of(), MessageParts.list(pkcs7()));
		// PGP/MIME, whose second part is named
		assertEquals(List.of(), MessageParts.list(pgp()));
	}

	private static MimeMessage pgp() throws Exception {
		return built("multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=\"b\"",
				"--b\nContent-Type: application/pgp-encrypted\n\nVersion: 1\n\n"
						+ "--b\nContent-Type: application/octet-stream; name=\"encrypted.asc\"\n"
						+ "Content-Disposition: inline; filename=\"encrypted.asc\"\n\ndata\n--b--\n");
	}

	@Test
	public void testRightsProtectedListsNothing() throws Exception {
		// Outlook's rights-protected message: a note, then message.rpmsg, which is named so that it would be listed
		assertEquals(List.of(), MessageParts.list(fixture("parts-rights-protected.eml")));

		// Only the protected part is unreadable: a PDF beside it is still listed
		assertEquals(List.of("Plain.pdf"), names(rpmsgAndPdf()));
	}

	private static MimeMessage rpmsgAndPdf() throws Exception {
		return mixed(
				file("Content-Type: application/x-microsoft-rpmsg-message\nContent-Disposition: attachment; filename=\"message.rpmsg\""),
				file("Content-Type: application/pdf\nContent-Disposition: attachment; filename=\"Plain.pdf\""));
	}

	private static MimeMessage pkcs7AndPdf() throws Exception {
		return mixed(
				file("Content-Type: application/pkcs7-mime; name=\"smime.p7m\"\nContent-Disposition: attachment; filename=\"smime.p7m\""),
				file("Content-Type: application/pdf\nContent-Disposition: attachment; filename=\"Plain.pdf\""));
	}

	@Test
	public void testAnEncryptedPartEndsOnlyItsOwnBranch() throws Exception {
		// Of two siblings, the encrypted one lists nothing and the other still lists
		assertEquals(List.of("Plain.pdf"), names(pkcs7AndPdf()));
	}

	/** Cut after the headers and a few lines of preamble: a multipart with no boundary at all */
	private static MimeMessage truncated() throws Exception {
		String eml = new String(bytes("parts-attached.eml"), StandardCharsets.ISO_8859_1);
		return parse(eml.substring(0, eml.indexOf("--=_mixed_1") - 1).getBytes(StandardCharsets.ISO_8859_1));
	}

	@Test
	public void testUnreadableListsNothing() throws Exception {
		MimeMessage message = truncated();

		try (LogCapture log = LogCapture.of(MessageParts.class)) {
			assertEquals(List.of(), MessageParts.list(message));
			// The class of the failure, and nothing of the email
			assertEquals(1, log.lines().size());
			assertTrue(log.lines().get(0), log.lines().get(0).startsWith("WARN Cannot read an email's parts: jakarta.mail."));
			for (String line : log.lines())
				assertFalse(line, line.contains("Contract") || line.contains("example.com"));
		}
		assertThrows(Exception.class, () -> MessageParts.write(message, 0, new ByteArrayOutputStream()));

		assertEquals(List.of(), MessageParts.list(null));
	}

	/** multipart/mixed inside multipart/mixed, depth times, round a PDF */
	private static MimeMessage nested(int depth) throws Exception {
		StringBuilder eml = new StringBuilder();
		for (int i = 0; i < depth; i++)
			eml.append("--b").append(i).append("\nContent-Type: multipart/mixed; boundary=\"b").append(i + 1).append("\"\n\n");
		eml.append("--b").append(depth).append("\n")
				.append(file("Content-Type: application/pdf\nContent-Disposition: attachment; filename=\"Deep.pdf\"")).append("\n");
		return built("multipart/mixed; boundary=\"b0\"", eml.toString());
	}

	@Test
	public void testNestedTooDeepListsNothing() throws Exception {
		// The walk recurses, so a message nested past any real one lists nothing instead of overflowing the stack
		assertEquals(List.of("Deep.pdf"), names(nested(MessageParts.MAX_DEPTH - 1)));
		assertEquals(List.of(), MessageParts.list(nested(200)));
	}

	@Test
	public void testIndexesAreStable() throws Exception {
		for (String name : List.of("parts-attached.eml", "parts-unreferenced.eml", "parts-signed.eml")) {
			MimeMessage message = fixture(name);
			List<Part> first = MessageParts.list(message);
			assertFalse(name, first.isEmpty());
			assertEquals(name, first, MessageParts.list(message));
			// Parsed afresh from the same bytes, as the filing call does after the inspect call
			assertEquals(name, first, MessageParts.list(fixture(name)));
			for (int i = 0; i < first.size(); i++)
				assertEquals(i, first.get(i).index());
		}
	}

	@Test
	public void testWrite() throws Exception {
		MimeMessage message = fixture("parts-attached.eml");
		List<Part> parts = MessageParts.list(message);

		ByteArrayOutputStream pdf = new ByteArrayOutputStream();
		MessageParts.write(message, 1, pdf);
		assertTrue(Arrays.equals(PDF, pdf.toByteArray()));

		// An inline part too, decoded
		ByteArrayOutputStream logo = new ByteArrayOutputStream();
		MessageParts.write(message, 0, logo);
		assertEquals(parts.get(0).size(), logo.size());
		assertEquals((byte) 0x89, logo.toByteArray()[0]);
		assertEquals("PNG", new String(logo.toByteArray(), 1, 3, StandardCharsets.US_ASCII));

		// The attached message as an .eml: as long as its size says, and a message of its own
		ByteArrayOutputStream forwarded = new ByteArrayOutputStream();
		MessageParts.write(message, 2, forwarded);
		assertEquals(parts.get(2).size(), forwarded.size());
		MimeMessage inner = parse(forwarded.toByteArray());
		assertEquals("<forwarded-1@example.com>", inner.getMessageID());
		assertEquals("Original request", inner.getSubject());

		assertThrows(IllegalArgumentException.class, () -> MessageParts.write(message, 3, new ByteArrayOutputStream()));
		assertThrows(IllegalArgumentException.class, () -> MessageParts.write(message, -1, new ByteArrayOutputStream()));
		// An encrypted message has no parts to write
		assertThrows(IllegalArgumentException.class,
				() -> MessageParts.write(fixture("parts-encrypted.eml"), 0, new ByteArrayOutputStream()));
	}

	/** The headers of a part and the name it should be given */
	private static final String[][] NAME_TABLE = {
				{ "Content-Disposition: attachment; filename=\"C:\\\\Users\\\\ann\\\\Report.docx\"", "Report.docx" },
				{ "Content-Disposition: attachment; filename=\"/etc/passwd\"", "passwd" },
				{ "Content-Disposition: attachment; filename=\"=?utf-8?Q?Caf=C3=A9_menu.pdf?=\"", "Caf\u00e9 menu.pdf" },
				{ "Content-Disposition: attachment; filename*=utf-8''Caf%C3%A9.pdf", "Caf\u00e9.pdf" },
				// Beyond the Basic Multilingual Plane, which MariaDB's tables refuse (R33)
				{ "Content-Disposition: attachment; filename*=utf-8''Party%20%F0%9F%8E%89.pdf", "Party \ufffd.pdf" },
				{ "Content-Disposition: attachment; filename=\"...\"", "Attachment" },
				{ "Content-Disposition: attachment; filename=\"a<b>|c.pdf.\"", "a_b__c.pdf" },
				// The Content-Type's name when the disposition has none
				{ "Content-Type: application/pdf; name=\"Only In Type.pdf\"", "Only In Type.pdf" } };

	private static MimeMessage named(String headers) throws Exception {
		return mixed(file(headers.startsWith("Content-Type") ? headers : "Content-Type: application/pdf\n" + headers));
	}

	private static MimeMessage pathName() throws Exception {
		return named("Content-Disposition: attachment; filename=\"../x:y?.pdf\"");
	}

	private static MimeMessage longName() throws Exception {
		return named("Content-Disposition: attachment; filename=\"" + "a".repeat(300) + ".pdf\"");
	}

	@Test
	public void testNamesAreSafe() throws Exception {
		// No path, and nothing a store or an operating system refuses
		assertEquals(List.of("x_y_.pdf"), names(pathName()));

		for (String[] row : NAME_TABLE)
			assertEquals(row[0], List.of(row[1]), names(named(row[0])));

		// A long name is shortened, and keeps its extension
		List<String> longName = names(longName());
		assertEquals(255, longName.get(0).length());
		assertTrue(longName.get(0).endsWith("aaa.pdf"));
	}

	private static final String[] MARKUPS = { "<img src=\"cid:one@example.com\">", "<IMG SRC='CID:ONE@EXAMPLE.COM'>",
			"<img src=cid:one@example.com>", "<img src=\"cid:one%40example.com\">",
			"<td style=\"background:url(cid:one@example.com);\">" };

	/** An HTML part with the markup, and an image with a Content-ID, a disposition or a name of its own */
	private static MimeMessage referencing(String markup) throws Exception {
		return mixed(html(markup), file("Content-Type: image/png\nContent-ID: <one@example.com>"));
	}

	private static MimeMessage plusSign() throws Exception {
		return mixed(html("<img src=\"cid:a+b@example.com\">"), file("Content-Type: image/png\nContent-ID: <a+b@example.com>"));
	}

	private static MimeMessage noImages() throws Exception {
		return mixed(html("<p>No images</p>"), file("Content-Type: image/png\nContent-ID: <one@example.com>"));
	}

	@Test
	public void testReferencesAreFoundHoweverTheyAreWritten() throws Exception {
		// Each part has a Content-ID and no disposition and no name, so only a reference makes it inline
		for (String markup : MARKUPS) {
			List<Part> parts = MessageParts.list(referencing(markup));
			assertEquals(markup, 1, parts.size());
			assertTrue(markup, parts.get(0).inline());
			// With no name of its own, it keeps one a person can read
			assertEquals(markup, "Inline part", parts.get(0).name());
		}

		// A plus sign in a Content-ID stays a plus sign
		assertEquals(1, MessageParts.list(plusSign()).size());

		// Nothing names it: a part with a Content-ID and no name is a body, and isn't listed
		assertEquals(List.of(), MessageParts.list(noImages()));
	}

	private static MimeMessage inlineAndNamed() throws Exception {
		return mixed(
				// Inline with a Content-ID that nothing references: still inline (a signature's logo)
				file("Content-Type: image/png\nContent-Disposition: inline; filename=\"sig.png\"\nContent-ID: <sig@example.com>"),
				// Inline with a name and no Content-ID, as Apple Mail sends a PDF: an attachment
				file("Content-Type: application/pdf\nContent-Disposition: inline; filename=\"Mac.pdf\""),
				// Referenced, and named
				html("<img src=\"cid:ref@example.com\">"),
				file("Content-Type: image/jpeg; name=\"ref.jpg\"\nContent-ID: <ref@example.com>"));
	}

	@Test
	public void testInlineDispositionAndNames() throws Exception {
		List<Part> parts = MessageParts.list(inlineAndNamed());
		assertEquals(List.of("sig.png", "Mac.pdf", "ref.jpg"), parts.stream().map(Part::name).toList());
		assertEquals(List.of(true, false, true), parts.stream().map(Part::inline).toList());
		assertEquals(List.of(0, 1, 2), parts.stream().map(Part::index).toList());
	}

	private static MimeMessage attachedMessages() throws Exception {
		String inner = "From: cara@example.com\nSubject: =?utf-8?Q?Caf=C3=A9?=\nMessage-ID: <inner@example.com>\n"
				+ "MIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=\"i\"\n\n--i\nContent-Type: text/plain\n\nText\n"
				+ "--i\nContent-Type: application/pdf\nContent-Disposition: attachment; filename=\"Inside.pdf\"\n\ndata\n--i--\n";
		return mixed("Content-Type: message/rfc822\n\n" + inner,
				// The same, with a name of its own, and with no Message-ID
				"Content-Type: message/rfc822; name=\"Named.eml\"\n\nFrom: x@example.com\nSubject: No id\n\nBody\n",
				// Without a subject or a name
				"Content-Type: message/rfc822\n\nFrom: x@example.com\n\nBody\n");
	}

	@Test
	public void testAnAttachedMessageIsNotWalkedInto() throws Exception {
		List<Part> parts = MessageParts.list(attachedMessages());
		assertEquals(List.of("Caf\u00e9.eml", "Named.eml", "Attached message.eml"),
				parts.stream().map(Part::name).toList());
		assertEquals("inner@example.com", parts.get(0).messageId());
		assertEquals(null, parts.get(1).messageId());
		assertTrue(parts.stream().allMatch(Part::attachedMessage));
	}

	/**
	 * Checks that each part {@code list} gives is written with exactly the size it gave, and that the next index
	 * isn't a part.
	 * 
	 * @return how many parts there are
	 */
	private static int assertWritesWhatListSaid(String label, MimeMessage message) throws Exception {
		List<Part> parts = MessageParts.list(message);
		for (int i = 0; i < parts.size(); i++) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			MessageParts.write(message, i, out);
			assertEquals(label + " #" + i, parts.get(i).size(), out.size());
			assertEquals(label + " #" + i, i, parts.get(i).index());
		}
		int next = parts.size();
		// An unreadable message throws something else, which the tests of unreadable messages pin
		Class<? extends Exception> refusal = parts.isEmpty() ? Exception.class : IllegalArgumentException.class;
		assertThrows(label, refusal, () -> MessageParts.write(message, next, new ByteArrayOutputStream()));
		return parts.size();
	}

	@Test
	public void testWriteGivesTheSizeListSaidForEveryPart() throws Exception {
		// Every eml in the folder, found by listing it so that a fixture added later is covered too
		List<Path> emls;
		try (Stream<Path> files = Files.list(Path.of("src/test/resources/email"))) {
			emls = files.filter(f -> f.getFileName().toString().endsWith(".eml")).sorted().toList();
		}
		Map<String, Integer> counts = new TreeMap<>();
		for (Path eml : emls)
			counts.put(eml.getFileName().toString(), assertWritesWhatListSaid(eml.getFileName().toString(), parse(Files.readAllBytes(eml))));
		// The ones with parts, and one that is a real message: if the folder were wrong this would be empty
		assertEquals(3, (int) counts.get("parts-attached.eml"));
		assertEquals(2, (int) counts.get("parts-unreferenced.eml"));
		assertEquals(1, (int) counts.get("parts-signed.eml"));
		assertEquals(0, (int) counts.get("parts-encrypted.eml"));
		assertEquals(0, (int) counts.get("parts-rights-protected.eml"));
		assertTrue(counts.toString(), counts.get("New email with attachments.eml") > 0);
		assertTrue(counts.toString(), counts.size() >= 9);

		// The messages the other tests build
		assertEquals(0, assertWritesWhatListSaid("pgp", pgp()));
		assertEquals(0, assertWritesWhatListSaid("pkcs7", pkcs7()));
		assertEquals(1, assertWritesWhatListSaid("rpmsg and pdf", rpmsgAndPdf()));
		assertEquals(1, assertWritesWhatListSaid("pkcs7 and pdf", pkcs7AndPdf()));
		assertEquals(3, assertWritesWhatListSaid("inline and named", inlineAndNamed()));
		assertEquals(3, assertWritesWhatListSaid("attached messages", attachedMessages()));
		assertEquals(1, assertWritesWhatListSaid("path name", pathName()));
		assertEquals(1, assertWritesWhatListSaid("long name", longName()));
		for (String[] row : NAME_TABLE)
			assertEquals(row[0], 1, assertWritesWhatListSaid(row[0], named(row[0])));
		for (String markup : MARKUPS)
			assertEquals(markup, 1, assertWritesWhatListSaid(markup, referencing(markup)));
		assertEquals(1, assertWritesWhatListSaid("plus sign", plusSign()));
		assertEquals(0, assertWritesWhatListSaid("no images", noImages()));
		// Nested as deep as the walk goes
		assertEquals(1, assertWritesWhatListSaid("nested", nested(MessageParts.MAX_DEPTH - 1)));

		// The unreadable ones list nothing by design, and write nothing
		for (MimeMessage unreadable : List.of(truncated(), nested(200))) {
			assertEquals(List.of(), MessageParts.list(unreadable));
			assertThrows(Exception.class, () -> MessageParts.write(unreadable, 0, new ByteArrayOutputStream()));
		}
	}
}
