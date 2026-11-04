// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

/**
 * Healdan's own walk of an {@code eml}'s MIME parts (M5 spec §6.6). CE's
 * {@code MailUtil} keeps no dispositions and no Content-IDs, so it can't tell
 * an inline logo from an attachment. The API uses the walk twice, once to list
 * a message's attachments for the Save window and once to file the ones
 * ticked, by index, so the same bytes must always give the same list.
 * <p>
 * The walk goes depth first from the message. {@code multipart/signed} is
 * walked into its first part only, which is the content; the signature isn't
 * an attachment. A part that is encrypted ({@code multipart/encrypted},
 * {@code application/pkcs7-mime}, {@code application/x-pkcs7-mime}) or
 * rights-protected (Outlook's {@code application/x-microsoft-rpmsg-message},
 * usually named {@code message.rpmsg}) has nothing readable and contributes
 * nothing, so a message that is one lists nothing. A {@code message/rfc822}
 * part is one attached message and isn't walked into. Any other multipart is
 * walked in order.
 * <p>
 * A leaf is <em>inline</em> when its disposition is {@code inline} and it has
 * a Content-ID, or when an HTML part of the message names its Content-ID as a
 * {@code cid:} reference. Otherwise it is an <em>attachment</em> if it has a
 * file name, and a body, which isn't listed, if it hasn't.
 * <p>
 * Nothing logged here names a subject, an address or a file name.
 */
public final class MessageParts {

	private static final Logger log = LoggerFactory.getLogger(MessageParts.class);

	/** A real message nests a handful of multiparts; the walk recurses, so it stops here */
	static final int MAX_DEPTH = 32;

	private static final String MESSAGE = "message/rfc822";

	/** Parts whose content is encrypted or rights-protected: nothing in them can be read, so nothing is listed */
	private static final List<String> UNREADABLE = List.of("multipart/encrypted", "application/pkcs7-mime",
			"application/x-pkcs7-mime", "application/x-microsoft-rpmsg-message");

	/** What a part with no file name of its own is called, and an attached message with no subject */
	private static final String INLINE_NAME = "Inline part";

	private static final String ATTACHMENT_NAME = "Attachment";

	private static final String ATTACHED_MESSAGE_NAME = "Attached message.eml";

	/** A {@code cid:} reference in HTML, in a src, a background, a url() or an unquoted value */
	private static final Pattern CID = Pattern.compile("(?i)\\bcid:<?([^\"'\\s<>()]+)");

	private MessageParts() {
	}

	/**
	 * An attachment or an inline part.
	 *
	 * @param index the part's place among the attachments and inline parts of
	 *        its message, which are all that count, in a depth first walk
	 * @param name its file name, safe for a store and an operating system
	 *        ({@link FileNames}); an attached message without one is named
	 *        after its subject
	 * @param type its media type in lower case, such as {@code application/pdf}
	 * @param size the length of its decoded content in bytes
	 * @param inline true for an inline part, which isn't offered as an
	 *        attachment
	 * @param messageId the attached message's Message-ID
	 *        ({@link MessageIds#normalize}); null for any other part, and for
	 *        an attached message without one
	 */
	public record Part(int index, String name, String type, long size, boolean inline, String messageId) {

		/**
		 * @return true for an attached message ({@code message/rfc822}), which
		 *         is filed as an {@code eml}
		 */
		public boolean attachedMessage() {
			return MESSAGE.equals(type);
		}
	}

	/** Takes each part's content, as {@link MessageParts#write(MimeMessage, Collection, Writer)} reads it */
	@FunctionalInterface
	public interface Writer {

		/**
		 * @param index the part's index
		 * @param content its decoded content, which the walk closes
		 * @throws IOException if it can't be written
		 */
		void write(int index, InputStream content) throws IOException;
	}

	/** A part the walk found, with what {@link #write} needs to read it again */
	private record Found(String name, String type, boolean inline, String messageId, jakarta.mail.Part source) {
	}

	/**
	 * @param message the message, may be null
	 * @return its attachments and inline parts in order, numbered from 0. Never
	 *         throws: an unreadable or encrypted message lists nothing, never
	 *         part of its list, so the answer for one set of bytes doesn't vary
	 */
	public static List<Part> list(MimeMessage message) {
		if (message == null)
			return List.of();
		try {
			List<Found> found = scan(message);
			List<Part> parts = new ArrayList<>(found.size());
			for (int i = 0; i < found.size(); i++) {
				Found f = found.get(i);
				parts.add(new Part(i, f.name(), f.type(), size(f.source()), f.inline(), f.messageId()));
			}
			return List.copyOf(parts);
		} catch (Exception e) {
			// The class only: a parser's message can hold addresses, and so can a file name
			log.warn("Cannot read an email's parts: {}", e.getClass().getName());
			log.debug("Cannot read an email's parts", e);
			return List.of();
		}
	}

	/**
	 * Writes one part's content.
	 *
	 * @param message the message
	 * @param index the part's index, as {@link #list} gave it
	 * @param out where to write the part's decoded content; an attached message
	 *        as its {@code eml} bytes. The caller closes it.
	 * @throws IllegalArgumentException if the message has no such part
	 * @throws IOException if the part can't be read or written
	 * @throws MessagingException if the message can't be walked
	 */
	public static void write(MimeMessage message, int index, OutputStream out) throws IOException, MessagingException {
		write(message, List.of(index), (i, content) -> content.transferTo(out));
	}

	/**
	 * Writes several parts' content, walking the message once.
	 *
	 * @param message the message
	 * @param indexes the parts' indexes, as {@link #list} gave them, in the
	 *        order to write them
	 * @param writer given each part's decoded content in turn; an attached
	 *        message as its {@code eml} bytes
	 * @throws IllegalArgumentException if the message has no such part; then
	 *         nothing is written
	 * @throws IOException if a part can't be read or written
	 * @throws MessagingException if the message can't be walked
	 */
	public static void write(MimeMessage message, Collection<Integer> indexes, Writer writer)
			throws IOException, MessagingException {
		List<Found> found = scan(Objects.requireNonNull(message));
		for (int index : indexes)
			if (index < 0 || index >= found.size())
				throw new IllegalArgumentException("The message has no part " + index);
		for (int index : indexes)
			try (InputStream in = found.get(index).source().getInputStream()) {
				writer.write(index, in);
			}
	}

	/** The decoded length, read once */
	private static long size(jakarta.mail.Part part) throws IOException, MessagingException {
		try (InputStream in = part.getInputStream()) {
			return in.transferTo(OutputStream.nullOutputStream());
		}
	}

	private static List<Found> scan(MimeMessage message) throws MessagingException, IOException {
		// The first pass collects the leaves in order and what the HTML ones refer to by cid:, which the second
		// needs for every leaf, so no leaf is classified before all the HTML is read
		Walk walk = new Walk();
		walk.visit(message, 0);

		List<Found> found = new ArrayList<>();
		for (jakarta.mail.Part leaf : walk.leaves) {
			if (leaf.isMimeType(MESSAGE)) {
				found.add(attachedMessage(leaf));
				continue;
			}
			String contentId = contentId(leaf);
			String name = name(leaf);
			boolean inline = contentId != null
					&& (jakarta.mail.Part.INLINE.equalsIgnoreCase(disposition(leaf)) || walk.referenced.contains(contentId));
			if (inline)
				found.add(new Found(name != null ? name : INLINE_NAME, type(leaf), true, null, leaf));
			else if (name != null)
				found.add(new Found(name, type(leaf), false, null, leaf));
		}
		return found;
	}

	/** The leaves of the walk in order, and the Content-IDs the HTML in them refers to */
	private static final class Walk {

		final List<jakarta.mail.Part> leaves = new ArrayList<>();

		/** In lower case, without brackets and percent escapes */
		final Set<String> referenced = new HashSet<>();

		void visit(jakarta.mail.Part part, int depth) throws MessagingException, IOException {
			if (depth > MAX_DEPTH)
				throw new MessagingException("Nested too deeply");
			for (String unreadable : UNREADABLE)
				if (part.isMimeType(unreadable))
					return;

			if (part.isMimeType("multipart/signed")) {
				// The content is the first part, and the signature the second
				Multipart signed = multipart(part);
				if (signed.getCount() > 0)
					visit(signed.getBodyPart(0), depth + 1);
			} else if (part.isMimeType("multipart/*")) {
				Multipart multipart = multipart(part);
				for (int i = 0; i < multipart.getCount(); i++)
					visit(multipart.getBodyPart(i), depth + 1);
			} else {
				leaves.add(part);
				if (part.isMimeType("text/html")) {
					Matcher cid = CID.matcher(text(part));
					while (cid.find())
						referenced.add(decode(cid.group(1)).strip().toLowerCase(Locale.ROOT));
				}
			}
		}
	}

	private static Multipart multipart(jakarta.mail.Part part) throws MessagingException, IOException {
		if (part.getContent() instanceof Multipart multipart)
			return multipart;
		throw new MessagingException("Not a multipart");
	}

	/** An HTML part's text; its bytes read as Latin-1 when the JVM lacks its charset, as references are ASCII */
	private static String text(jakarta.mail.Part html) throws MessagingException, IOException {
		try {
			if (html.getContent() instanceof String text)
				return text;
		} catch (UnsupportedEncodingException e) {
			// Falls through to the bytes
		}
		try (InputStream in = html.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}

	/** A reference's percent escapes undone; a plus sign stays a plus sign, as in a URL's path */
	private static String decode(String reference) {
		try {
			return URLDecoder.decode(reference.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return reference;
		}
	}

	private static Found attachedMessage(jakarta.mail.Part part) {
		String subject = null;
		String messageId = null;
		try {
			if (part.getContent() instanceof MimeMessage attached) {
				subject = attached.getSubject();
				messageId = MessageIds.normalize(attached.getMessageID());
			}
		} catch (MessagingException | IOException | RuntimeException e) {
			// The bytes are still its .eml; only the name and the Message-ID are lost
			log.debug("Cannot read an attached message", e);
		}
		String name = name(part);
		return new Found(name != null ? name : FileNames.fromSubject(subject, "eml", ATTACHED_MESSAGE_NAME), MESSAGE,
				false, messageId, part);
	}

	/** The Content-ID in lower case without its brackets, null if there is none */
	private static String contentId(jakarta.mail.Part part) throws MessagingException {
		String[] header = part.getHeader("Content-ID");
		if (header == null || header.length == 0 || header[0] == null)
			return null;
		String id = header[0].strip();
		if (id.startsWith("<") && id.endsWith(">"))
			id = id.substring(1, id.length() - 1).strip();
		return id.isEmpty() ? null : id.toLowerCase(Locale.ROOT);
	}

	/** The disposition, null if there is none or it can't be read: a damaged header costs only that */
	private static String disposition(jakarta.mail.Part part) {
		try {
			return part.getDisposition();
		} catch (MessagingException e) {
			return null;
		}
	}

	/** The file name made safe, null if the part has none */
	private static String name(jakarta.mail.Part part) {
		String raw;
		try {
			raw = part.getFileName();
		} catch (MessagingException e) {
			return null;
		}
		if (raw == null || raw.isBlank())
			return null;
		String decoded;
		try {
			decoded = MimeUtility.decodeText(raw);
		} catch (UnsupportedEncodingException e) {
			decoded = raw;
		}
		// The last segment of a path, with either separator
		String segment = decoded.substring(Math.max(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\')) + 1);
		return segment.isBlank() ? null : FileNames.fromFileName(segment, ATTACHMENT_NAME);
	}

	private static String type(jakarta.mail.Part part) {
		try {
			return new ContentType(part.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
		} catch (MessagingException | RuntimeException e) {
			return "application/octet-stream";
		}
	}
}
