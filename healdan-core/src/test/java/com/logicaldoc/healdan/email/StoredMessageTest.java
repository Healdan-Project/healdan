// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
}
