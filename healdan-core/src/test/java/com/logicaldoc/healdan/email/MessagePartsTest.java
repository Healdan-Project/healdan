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

	@Test
	public void testEncryptedListsNothing() throws Exception {
		// parts-encrypted.eml has a name and an attachment disposition, so it would be listed if it were walked
		assertEquals(List.of(), MessageParts.list(fixture("parts-encrypted.eml")));

		// The older S/MIME type name, named so that it would be listed if it were walked
		assertEquals(List.of(), MessageParts.list(built("application/x-pkcs7-mime; name=\"smime.p7m\"", "ZGF0YQ==\n")));
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
		MimeMessage message = mixed(
				file("Content-Type: application/x-microsoft-rpmsg-message\nContent-Disposition: attachment; filename=\"message.rpmsg\""),
				file("Content-Type: application/pdf\nContent-Disposition: attachment; filename=\"Plain.pdf\""));
		assertEquals(List.of("Plain.pdf"), names(message));
	}

	@Test
	public void testAnEncryptedPartEndsOnlyItsOwnBranch() throws Exception {
		// Of two siblings, the encrypted one lists nothing and the other still lists
		MimeMessage message = mixed(
				file("Content-Type: application/pkcs7-mime; name=\"smime.p7m\"\nContent-Disposition: attachment; filename=\"smime.p7m\""),
				file("Content-Type: application/pdf\nContent-Disposition: attachment; filename=\"Plain.pdf\""));
		assertEquals(List.of("Plain.pdf"), names(message));
	}

	@Test
	public void testUnreadableListsNothing() throws Exception {
		// Cut after the headers and a few lines of preamble: a multipart with no boundary at all
		String eml = new String(bytes("parts-attached.eml"), StandardCharsets.ISO_8859_1);
		String truncated = eml.substring(0, eml.indexOf("--=_mixed_1") - 1);
		MimeMessage message = parse(truncated.getBytes(StandardCharsets.ISO_8859_1));

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

	@Test
	public void testNamesAreSafe() throws Exception {
		// No path, and nothing a store or an operating system refuses
		assertEquals(List.of("x_y_.pdf"), names(mixed(
				file("Content-Type: application/pdf\nContent-Disposition: attachment; filename=\"../x:y?.pdf\""))));

		String[][] table = {
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
		for (String[] row : table)
			assertEquals(row[0], List.of(row[1]), names(mixed(file(row[0].startsWith("Content-Type") ? row[0]
					: "Content-Type: application/pdf\n" + row[0]))));

		// A long name is shortened, and keeps its extension
		List<String> longName = names(mixed(file("Content-Type: application/pdf\n"
				+ "Content-Disposition: attachment; filename=\"" + "a".repeat(300) + ".pdf\"")));
		assertEquals(255, longName.get(0).length());
		assertTrue(longName.get(0).endsWith("aaa.pdf"));
	}

	@Test
	public void testReferencesAreFoundHoweverTheyAreWritten() throws Exception {
		// Each part has a Content-ID and no disposition and no name, so only a reference makes it inline
		String[] markups = { "<img src=\"cid:one@example.com\">", "<IMG SRC='CID:ONE@EXAMPLE.COM'>",
				"<img src=cid:one@example.com>", "<img src=\"cid:one%40example.com\">",
				"<td style=\"background:url(cid:one@example.com);\">" };
		for (String markup : markups) {
			List<Part> parts = MessageParts.list(mixed(html(markup),
					file("Content-Type: image/png\nContent-ID: <one@example.com>")));
			assertEquals(markup, 1, parts.size());
			assertTrue(markup, parts.get(0).inline());
			// With no name of its own, it keeps one a person can read
			assertEquals(markup, "Inline part", parts.get(0).name());
		}

		// A plus sign in a Content-ID stays a plus sign
		assertEquals(1, MessageParts.list(mixed(html("<img src=\"cid:a+b@example.com\">"),
				file("Content-Type: image/png\nContent-ID: <a+b@example.com>"))).size());

		// Nothing names it: a part with a Content-ID and no name is a body, and isn't listed
		assertEquals(List.of(), MessageParts.list(mixed(html("<p>No images</p>"),
				file("Content-Type: image/png\nContent-ID: <one@example.com>"))));
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

	/** Checks that each part {@code list} gives is written with exactly the size it gave, and returns the count */
	private static void assertWritesWhatListSaid(String label, MimeMessage message, int expected) throws Exception {
		List<Part> parts = MessageParts.list(message);
		assertEquals(label, expected, parts.size());
		for (Part part : parts) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			MessageParts.write(message, part.index(), out);
			assertEquals(label + " #" + part.index(), part.size(), out.size());
		}
		// And no more: the next index isn't a part
		assertThrows(label, IllegalArgumentException.class,
				() -> MessageParts.write(message, expected, new ByteArrayOutputStream()));
	}

	@Test
	public void testWriteGivesTheSizeListSaidForEveryPart() throws Exception {
		assertWritesWhatListSaid("attached", fixture("parts-attached.eml"), 3);
		assertWritesWhatListSaid("unreferenced", fixture("parts-unreferenced.eml"), 2);
		assertWritesWhatListSaid("signed", fixture("parts-signed.eml"), 1);
		assertWritesWhatListSaid("encrypted", fixture("parts-encrypted.eml"), 0);
		assertWritesWhatListSaid("rights-protected", fixture("parts-rights-protected.eml"), 0);
		// The messages the other tests build: inline and named parts, attached messages, a referenced image
		assertWritesWhatListSaid("inline and named", inlineAndNamed(), 3);
		assertWritesWhatListSaid("attached messages", attachedMessages(), 3);
		assertWritesWhatListSaid("referenced", mixed(html("<img src=\"cid:one@example.com\">"),
				file("Content-Type: image/png\nContent-ID: <one@example.com>")), 1);
		assertWritesWhatListSaid("pgp", pgp(), 0);
	}
}
