package com.logicaldoc.healdan.seed.demo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves an email's dates to just before the demo files it, so its Sent and
 * Received properties agree with the timeline (plan call 5).
 * {@code EmailFields} reads Sent from {@code Date:} and Received from the
 * first {@code Received:} header.
 */
final class EmailDates {

	static final Duration SENT_BEFORE = Duration.ofMinutes(25);

	static final Duration RECEIVED_BEFORE = Duration.ofMinutes(5);

	private static final Pattern HEADERS_END = Pattern.compile("\\r?\\n\\r?\\n");

	/** A header line and its folded continuation lines */
	private static final Pattern RECEIVED = Pattern.compile("(?mi)^Received:.*(?:\\r?\\n[ \\t].*)*\\r?\\n");

	private static final Pattern DATE = Pattern.compile("(?mi)^Date:.*$");

	private EmailDates() {
	}

	static byte[] restamp(byte[] eml, Date filed) {
		// Headers are ASCII; ISO-8859-1 keeps every other byte as it was
		String text = new String(eml, StandardCharsets.ISO_8859_1);
		Matcher end = HEADERS_END.matcher(text);
		if (!end.find())
			throw new IllegalArgumentException("The email has no blank line after its headers");
		String newline = text.contains("\r\n") ? "\r\n" : "\n";
		String headers = text.substring(0, end.start()) + newline;
		String rest = text.substring(end.start() + newline.length());

		headers = RECEIVED.matcher(headers).replaceAll("");
		String sent = rfc1123(filed, SENT_BEFORE);
		Matcher date = DATE.matcher(headers);
		headers = date.find() ? date.replaceFirst(Matcher.quoteReplacement("Date: " + sent))
				: "Date: " + sent + newline + headers;
		headers = "Received: by mail.demo.example; " + rfc1123(filed, RECEIVED_BEFORE) + newline + headers;
		return (headers + rest).getBytes(StandardCharsets.ISO_8859_1);
	}

	private static String rfc1123(Date filed, Duration before) {
		return DateTimeFormatter.RFC_1123_DATE_TIME
				.format(ZonedDateTime.ofInstant(filed.toInstant().minus(before), ZoneId.systemDefault()));
	}
}
