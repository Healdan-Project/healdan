// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;

import org.junit.Test;

import com.logicaldoc.healdan.email.MessageParts.Part;

import jakarta.mail.MessagingException;

/**
 * M6 spec §6.6: a filed email, an {@code eml} or an {@code msg}, read into one
 * message view.
 */
public class StoredMessageTest {

	private static StoredMessage open(String name, String extension) throws Exception {
		try (InputStream in = new FileInputStream("src/test/resources/email/" + name)) {
			return StoredMessage.open(in, extension);
		}
	}

	@Test
	public void testAnEmlGivesItsFieldsPartsAndBody() throws Exception {
		StoredMessage message = open("parts-attached.eml", "eml");

		assertEquals("Contract for review", message.fields().subject());
		assertTrue(message.fields().from().contains("ann@example.com"));
		assertFalse(message.parts().isEmpty());
		assertTrue(message.body(false).contains("The contract is attached."));
		Part pdf = message.parts().stream().filter(p -> "application/pdf".equals(p.type())).findFirst().orElseThrow();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		message.write(pdf.index(), out);
		assertEquals(pdf.size(), out.size());
	}

	@Test
	public void testAMsgGivesTheSameView() throws Exception {
		StoredMessage message = open("test.msg", "msg");

		assertNotNull(message.fields().subject());
		assertFalse(message.body(false).isBlank());
		for (Part part : message.parts()) {
			assertFalse(part.inline());
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			message.write(part.index(), out);
			assertEquals(part.size(), out.size());
		}
	}

	@Test
	public void testAPartThatIsNotThereIsRefused() throws Exception {
		StoredMessage message = open("test.msg", "msg");

		assertThrows(IllegalArgumentException.class,
				() -> message.write(message.parts().size(), new ByteArrayOutputStream()));
	}

	@Test
	public void testAFileThatIsNotAMsgIsRefused() {
		assertThrows(Exception.class, () -> open("corrupt.msg", "msg"));
	}

	@Test
	public void testAnEmlWithNoHeaderLinesIsRefused() {
		assertThrows(MessagingException.class, () -> open("corrupt.msg", "eml"));
	}

	@Test
	public void testPartsOfAMsgAreNamedAndTyped() throws Exception {
		StoredMessage message = open("test.msg", "msg");

		assertEquals(1, message.parts().size());
		assertEquals("Bestellu.pdf", message.parts().get(0).name());
		for (Part part : message.parts()) {
			assertFalse(part.name().isBlank());
			assertTrue(part.type().contains("/"));
		}
	}

	@Test
	public void testAnEmlReadFromAFileIsReadInPlaceAndGivesTheSameView() throws Exception {
		java.io.File file = new java.io.File("src/test/resources/email/parts-attached.eml");
		StoredMessage fromStream = open("parts-attached.eml", "eml");
		try (StoredMessage fromFile = StoredMessage.open(file, "eml", true)) {
			assertEquals(fromStream.parts(), fromFile.parts());
			assertEquals(fromStream.fields(), fromFile.fields());
			assertEquals(fromStream.body(false), fromFile.body(false));
			Part pdf = fromFile.parts().stream().filter(p -> "application/pdf".equals(p.type())).findFirst().orElseThrow();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			fromFile.write(pdf.index(), out);
			assertEquals(pdf.size(), out.size());
		}
	}

	@Test
	public void testAMsgOpenedWithoutContentListsItsPartsAndWritesNone() throws Exception {
		java.io.File file = new java.io.File("src/test/resources/email/test.msg");
		try (StoredMessage message = StoredMessage.open(file, "msg", false)) {
			assertEquals(open("test.msg", "msg").parts(), message.parts());
			assertThrows(IllegalStateException.class, () -> message.write(0, new ByteArrayOutputStream()));
		}
	}

	/** An msg as msgparser reads it, with the body parts given; null leaves one out */
	private static com.auxilii.msgparser.Message parsed(String messageClass, String html, String text) {
		com.auxilii.msgparser.Message message = new com.auxilii.msgparser.Message();
		message.setMessageClass(messageClass);
		if (html != null)
			message.setBodyHTML(html);
		if (text != null)
			message.setBodyText(text);
		return message;
	}

	@Test
	public void testAMsgsHtmlIsItsBodyWhateverItStartsWith() {
		// CE's reader takes HTML only when it starts with "<html", and falls back on the RTF
		StoredMessage.MsgBody body = StoredMessage.msgBody(parsed("IPM.Note", "<!DOCTYPE html><html><p>Hi</p></html>", "Hi"));

		assertEquals(new StoredMessage.MsgBody("<!DOCTYPE html><html><p>Hi</p></html>", true), body);
	}

	@Test
	public void testAMsgWithoutHtmlIsItsTextNeverItsRtf() {
		assertEquals(new StoredMessage.MsgBody("Just text", false),
				StoredMessage.msgBody(parsed("IPM.Note", null, "Just text")));
		assertEquals(new StoredMessage.MsgBody("", false), StoredMessage.msgBody(parsed("IPM.Note", null, null)));
	}

	@Test
	public void testASignedMsgKeepsTheBodyCeReadsFromWhatItWraps() {
		assertNull(StoredMessage.msgBody(parsed("IPM.Note.SMIME.MultipartSigned", "<p>outer</p>", null)));
	}

	@Test
	public void testAMsgsBodyIsTheSameReadFromAFileOrItsBytes() throws Exception {
		java.io.File file = new java.io.File("src/test/resources/email/test.msg");
		try (StoredMessage fromFile = StoredMessage.open(file, "msg", false)) {
			String body = fromFile.body(false);
			assertEquals(open("test.msg", "msg").body(false), body);
			assertFalse(body, body.contains("{\\rtf"));
		}
	}
}
