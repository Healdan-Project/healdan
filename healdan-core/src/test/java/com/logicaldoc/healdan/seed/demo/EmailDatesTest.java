package com.logicaldoc.healdan.seed.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Properties;

import org.junit.Test;

import jakarta.mail.Session;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.MimeMessage;

public class EmailDatesTest {

	private static final String EML = """
			From: Ines Moreau <ines.moreau@saltmarsh-shipping.example>\r
			To: Tom Reeve <tom.reeve@ashcombe-reeve.example>\r
			Subject: Laytime\r
			Date: Mon, 1 Jan 2024 10:00:00 +0000\r
			Received: from relay.example by mx.example; Mon, 1 Jan 2024 10:01:00 +0000\r
			MIME-Version: 1.0\r
			Content-Type: text/plain; charset=UTF-8\r
			\r
			Date: this line is the body, and stays.\r
			""";

	private static final Date FILED = Timestamp.valueOf("2026-10-14 11:00:00");

	private static MimeMessage parse(byte[] eml) throws Exception {
		return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml));
	}

	@Test
	public void testSentAndReceivedComeBeforeTheFiling() throws Exception {
		MimeMessage message = parse(EmailDates.restamp(EML.getBytes(StandardCharsets.US_ASCII), FILED));

		assertEquals(FILED.getTime() - EmailDates.SENT_BEFORE.toMillis(), message.getSentDate().getTime());
		String[] received = message.getHeader("Received");
		assertEquals(1, received.length);
		Date when = new MailDateFormat().parse(received[0].substring(received[0].lastIndexOf(';') + 1).trim());
		assertEquals(FILED.getTime() - EmailDates.RECEIVED_BEFORE.toMillis(), when.getTime());
	}

	@Test
	public void testTheBodyIsUntouched() throws Exception {
		String out = new String(EmailDates.restamp(EML.getBytes(StandardCharsets.US_ASCII), FILED),
				StandardCharsets.US_ASCII);
		assertTrue(out.endsWith("\r\n\r\nDate: this line is the body, and stays.\r\n"));
		assertEquals("Laytime", parse(out.getBytes(StandardCharsets.US_ASCII)).getSubject());
	}
}
