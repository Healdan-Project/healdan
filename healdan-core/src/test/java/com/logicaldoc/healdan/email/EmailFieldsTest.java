// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.junit.Test;

import com.auxilii.msgparser.MsgParser;
import com.logicaldoc.healdan.support.LogCapture;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * Spec \u00a76.1: the Message-ID is read from an {@code eml}'s header and from a
 * {@code msg}'s properties, and an unreadable file costs nothing else.
 */
public class EmailFieldsTest {

	private static File resource(String name) {
		return new File("src/test/resources/email/" + name);
	}

	@Test
	public void testEmlMessageId() {
		Optional<EmailFields> fields = EmailFields.extract(resource("healdan-cc.eml"), "eml");
		assertTrue(fields.isPresent());
		assertEquals("1@example.com", fields.get().messageId());
		assertEquals("Ann Smith <ann@example.com>", fields.get().from());
	}

	/** PostgreSQL spec section 7.5: an emoji is kept, a NUL is a space, and the cut to the column keeps pairs whole */
	@Test
	public void testTextHealdanKeeps() throws Exception {
		String party = new String(Character.toChars(0x1F389));
		assertEquals("Re " + party + " Sale", subject("Re\u0000" + party + " Sale"));
		assertEquals("x".repeat(3999), subject("x".repeat(3999) + party));
		assertEquals("x".repeat(3998) + party, subject("x".repeat(3998) + party + "y"));
	}

	/** The subject a message with this one, encoded as an emoji is, comes out with */
	private static String subject(String subject) throws Exception {
		String eml = "From: ann@example.com\r\nSubject: =?UTF-8?B?"
				+ Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))
				+ "?=\r\nMIME-Version: 1.0\r\nContent-Type: text/plain\r\n\r\nHello.\r\n";
		return EmailFields.fromEml(new MimeMessage(Session.getInstance(new Properties()),
				new ByteArrayInputStream(eml.getBytes(StandardCharsets.US_ASCII)))).subject();
	}

	@Test
	public void testEmlWithoutAMessageId() throws Exception {
		File eml = File.createTempFile("nomsgid", ".eml");
		try {
			Files.writeString(eml.toPath(),
					"From: ann@example.com\r\nTo: bob@example.com\r\nSubject: Nothing\r\nMIME-Version: 1.0\r\n"
							+ "Content-Type: text/plain\r\n\r\nNo Message-ID.\r\n",
					StandardCharsets.US_ASCII);
			Optional<EmailFields> fields = EmailFields.extract(eml, "eml");
			assertTrue(fields.isPresent());
			assertNull(fields.get().messageId());
			assertEquals("Nothing", fields.get().subject());
		} finally {
			eml.delete();
		}
	}

	@Test
	public void testMsgMessageId() throws Exception {
		File msg = resource("test.msg");
		String expected = MessageIds.normalize(new MsgParser().parseMsg(msg).getMessageId());

		Optional<EmailFields> fields = EmailFields.extract(msg, "msg");

		assertTrue(fields.isPresent());
		assertEquals(expected, fields.get().messageId());
		assertEquals("18293115.12305.1420447910189.JavaMail.open-xchange@srvmaox.olig.ch", fields.get().messageId());
		assertNotNull(fields.get().subject());
	}

	@Test
	public void testAMsgAlreadyReadHasNoMessageId() throws Exception {
		assertNull(EmailFields.fromMsg(com.logicaldoc.core.communication.MailUtil.msgToMail(resource("test.msg"), false))
				.messageId());
	}

	@Test
	public void testCorruptMsgGivesNothingAndNoException() {
		// Today's behaviour: no fields at all, so the listener files it blank, under its own name
		assertFalse(EmailFields.extract(resource("corrupt.msg"), "msg").isPresent());
	}

	@Test
	public void testWarningsNameTheExceptionClassOnly() throws Exception {
		// The file is named after a subject with an address; a parser's message can hold addresses too
		File dir = Files.createTempDirectory("emailfields").toFile();
		File named = new File(dir, "bob@example.com secret subject.msg");
		Files.copy(resource("corrupt.msg").toPath(), named.toPath());
		try (LogCapture log = LogCapture.of(EmailFields.class)) {
			assertFalse(EmailFields.extract(named, "msg").isPresent());

			List<String> warnings = log.lines().stream().filter(l -> l.startsWith("WARN")).toList();
			assertFalse(warnings.isEmpty());
			for (String line : warnings) {
				assertTrue(line, line.matches("WARN Cannot read (an email|the [A-Za-z -]+ of an email): [\\w.$]+"));
				assertFalse(line, line.contains("secret") || line.contains("example.com"));
			}
		} finally {
			named.delete();
			dir.delete();
		}
	}
}
