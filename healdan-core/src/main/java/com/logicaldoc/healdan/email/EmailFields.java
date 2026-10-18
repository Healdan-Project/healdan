package com.logicaldoc.healdan.email;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.ParseException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.communication.MailUtil;
import com.logicaldoc.core.communication.Recipient;
import com.logicaldoc.healdan.util.BasicPlane;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.MimeMessage;

/**
 * What the profile keeps of an email (spec §6.8). Every field is read on its own,
 * so a damaged header costs only that field; an unknown field is null, never
 * "now". A character beyond the Basic Multilingual Plane becomes U+FFFD
 * ({@link BasicPlane}). Nothing here throws.
 * 
 * @param from the sender, as {@code Name <addr>}
 * @param to the To recipients, joined with {@code ", "}
 * @param cc the CC recipients, joined with {@code ", "}
 * @param subject the subject
 * @param sent when it was sent
 * @param received when it was received
 */
public record EmailFields(String from, String to, String cc, String subject, Date sent, Date received) {

	private static final Logger log = LoggerFactory.getLogger(EmailFields.class);

	/** The attribute values are stored in a varchar(4000) */
	private static final int MAX_TEXT = 4000;

	/**
	 * @param file the email
	 * @param extension {@code eml} or {@code msg}
	 * @return the fields, empty when the file can't be parsed
	 */
	public static Optional<EmailFields> extract(File file, String extension) {
		try {
			if ("msg".equalsIgnoreCase(extension))
				return Optional.of(fromMsg(file));
			return fromEml(file);
		} catch (Throwable t) {
			log.warn("Cannot read the email {}: {}", file != null ? file.getName() : null, t.toString());
			return Optional.empty();
		}
	}

	private static Optional<EmailFields> fromEml(File file) throws Exception {
		MimeMessage message;
		try (InputStream is = new FileInputStream(file)) {
			message = MailUtil.readMime(is);
		}
		if (message == null) {
			log.warn("Cannot read the email {}: not a MIME message", file.getName());
			return Optional.empty();
		}

		String subject = safe("subject", message::getSubject);
		Date sent = safe("sent date", message::getSentDate);
		Date received = safe("received date", () -> receivedDate(message));
		String from = safe("from", () -> join(message.getFrom() == null ? null : List.of(message.getFrom())));
		String to = safe("to", () -> join(recipients(message, Message.RecipientType.TO)));
		String cc = safe("cc", () -> join(recipients(message, Message.RecipientType.CC)));
		return Optional.of(of(from, to, cc, subject, sent, received));
	}

	private static Collection<Address> recipients(MimeMessage message, Message.RecipientType type) throws Exception {
		Address[] all = message.getRecipients(type);
		return all == null ? null : List.of(all);
	}

	/** The date of the most recent hop, then the Delivery-Date header */
	private static Date receivedDate(MimeMessage message) throws Exception {
		String[] received = message.getHeader("Received");
		if (received != null && received.length > 0) {
			Date date = dateAfterSemicolon(received[0]);
			if (date != null)
				return date;
		}
		String[] delivery = message.getHeader("Delivery-Date");
		if (delivery != null && delivery.length > 0 && StringUtils.isNotBlank(delivery[0]))
			return parse(delivery[0]);
		return null;
	}

	private static Date dateAfterSemicolon(String header) {
		int semicolon = header.lastIndexOf(';');
		if (semicolon < 0)
			return null;
		return parse(header.substring(semicolon + 1));
	}

	private static Date parse(String value) {
		try {
			return new MailDateFormat().parse(value.replaceAll("\\s+", " ").trim());
		} catch (ParseException e) {
			return null;
		}
	}

	private static EmailFields fromMsg(File file) throws Exception {
		// CE's msg reader keeps one date: the sent time, which it reports as the received time too
		EMail mail = MailUtil.msgToMail(file, false);
		String subject = MailUtil.NO_SUBJECT.equals(mail.getSubject()) ? null : mail.getSubject();
		Date sent = mail.getSentDate();
		Date received = mail.getReceivedDate() != null ? mail.getReceivedDate() : sent;
		return of(safe("from", () -> join(mail.getFrom() == null ? null : List.of(mail.getFrom()))),
				safe("to", () -> joinRecipients(mail.getRecipients())),
				safe("cc", () -> joinRecipients(mail.getRecipientsCC())), subject, sent, received);
	}

	private static String joinRecipients(Collection<Recipient> recipients) {
		StringJoiner joiner = new StringJoiner(", ");
		for (Recipient r : recipients)
			addEntry(joiner, r.getName(), r.getAddress());
		return joiner.length() == 0 ? null : joiner.toString();
	}

	private static String join(Collection<? extends Object> addresses) {
		if (addresses == null)
			return null;
		StringJoiner joiner = new StringJoiner(", ");
		for (Object address : addresses) {
			if (address instanceof InternetAddress ia)
				addEntry(joiner, ia.getPersonal(), ia.getAddress());
			else if (address instanceof Recipient r)
				addEntry(joiner, r.getName(), r.getAddress());
			else if (address != null)
				addEntry(joiner, null, address.toString());
		}
		return joiner.length() == 0 ? null : joiner.toString();
	}

	private static void addEntry(StringJoiner joiner, String name, String address) {
		boolean hasName = StringUtils.isNotBlank(name);
		boolean hasAddress = StringUtils.isNotBlank(address);
		if (hasName && hasAddress && !name.trim().equals(address.trim()))
			joiner.add(name.trim() + " <" + address.trim() + ">");
		else if (hasAddress)
			joiner.add(address.trim());
		else if (hasName)
			joiner.add(name.trim());
	}

	private static EmailFields of(String from, String to, String cc, String subject, Date sent, Date received) {
		return new EmailFields(text(from), text(to), text(cc), text(subject), sent, received);
	}

	/*
	 * A character beyond the Basic Multilingual Plane, such as an emoji, would
	 * fail the insert on MariaDB (R33): it becomes U+FFFD, before the cut, which
	 * then splits no surrogate pair
	 */
	private static String text(String value) {
		if (StringUtils.isBlank(value))
			return null;
		String clean = BasicPlane.replaceOthers(value).replace('\u0000', ' ').trim();
		return clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT) : clean;
	}

	private interface Reader<T> {
		T get() throws Exception;
	}

	private static <T> T safe(String what, Reader<T> reader) {
		try {
			return reader.get();
		} catch (Throwable t) {
			log.warn("Cannot read the {} of the email: {}", what, t.toString());
			return null;
		}
	}
}
