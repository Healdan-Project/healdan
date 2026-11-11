// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.communication.EMailAttachment;
import com.logicaldoc.core.communication.MailUtil;
import com.logicaldoc.healdan.email.MessageParts.Part;
import com.logicaldoc.util.MimeType;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.SharedFileInputStream;

/**
 * A filed email read for showing (M6 spec §6.6): an {@code eml} through
 * Jakarta Mail, which {@link MessageParts} walks, and an {@code msg} through
 * CE's {@code MailUtil.msgToMail}, so both give the same fields, attachments,
 * body and attachment content. The bytes are read whole, here, so the caller
 * may close its stream at once.
 * <p>
 * CE's {@code msg} reader keeps no Content-IDs and no dispositions, so every
 * file it finds is an attachment, a logo the HTML shows included, and a
 * {@code cid:} picture in an {@code msg}'s HTML is a placeholder.
 */
public final class StoredMessage implements AutoCloseable {

	private final MimeMessage eml;

	private final EMail msg;

	private final List<Part> parts;

	/** What an {@code eml} read from a file reads from as it is used; null otherwise */
	private final InputStream source;

	private StoredMessage(MimeMessage eml, EMail msg, List<Part> parts, InputStream source) {
		this.eml = eml;
		this.msg = msg;
		this.parts = parts;
		this.source = source;
	}

	/**
	 * Reads a message from a file, so that an {@code eml} stays on disk and is read in place as it is used (Jakarta
	 * Mail's shared stream) instead of being held whole in memory. The file must stay until {@link #close}.
	 *
	 * @param file the email's file
	 * @param extension {@code eml} or {@code msg}
	 * @param content true when the attachments' content is needed ({@link #write}); an {@code msg} is then read with
	 *        its attachments, which it holds in memory
	 * @return the message, to close when done
	 * @throws IOException if the file can't be read
	 * @throws MessagingException if it isn't an email
	 */
	public static StoredMessage open(File file, String extension, boolean content) throws IOException, MessagingException {
		if ("msg".equalsIgnoreCase(extension)) {
			try (InputStream in = new FileInputStream(file)) {
				return msg(in, content);
			}
		}
		SharedFileInputStream shared = new SharedFileInputStream(file);
		try {
			MimeMessage message = MailUtil.readMime(shared);
			if (message == null || !hasHeaders(message))
				throw new MessagingException("Not a MIME message");
			return new StoredMessage(message, null, MessageParts.list(message), shared);
		} catch (MessagingException | RuntimeException e) {
			shared.close();
			throw e;
		}
	}

	@Override
	public void close() throws IOException {
		if (source != null)
			source.close();
	}

	/**
	 * @param content the email's bytes, read whole and not closed
	 * @param extension {@code eml} or {@code msg}
	 * @return the message
	 * @throws IOException if the bytes can't be read
	 * @throws MessagingException if they aren't an email
	 */
	public static StoredMessage open(InputStream content, String extension) throws IOException, MessagingException {
		if ("msg".equalsIgnoreCase(extension))
			return msg(content, true);
		MimeMessage message = MailUtil.readMime(content);
		if (message == null || !hasHeaders(message))
			throw new MessagingException("Not a MIME message");
		return new StoredMessage(message, null, MessageParts.list(message), null);
	}

	private static StoredMessage msg(InputStream content, boolean withContent) throws IOException, MessagingException {
		EMail mail;
		try {
			mail = MailUtil.msgToMail(content, withContent);
		} catch (IOException | MessagingException e) {
			throw e;
		} catch (Exception e) {
			// CE's reader declares CMSException, and a parser may throw anything for a file that isn't one
			throw new MessagingException("Not an msg", e);
		}
		return new StoredMessage(null, mail, msgParts(mail), null);
	}

	/**
	 * A header line: RFC 5322's field name, kept to the characters of an HTTP
	 * token, which every real one uses, then the colon. JavaMail takes any text
	 * for headers, so this is what tells a message from a file that isn't one.
	 */
	private static final Pattern HEADER = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+:");

	/**
	 * @param message a message JavaMail read
	 * @return true when it has at least one header line, which a file that isn't an email hasn't
	 */
	public static boolean hasHeaders(MimeMessage message) {
		try {
			Enumeration<String> lines = message.getAllHeaderLines();
			while (lines.hasMoreElements())
				if (HEADER.matcher(lines.nextElement()).find())
					return true;
		} catch (MessagingException e) {
			// A message whose headers can't be read isn't one
		}
		return false;
	}

	private static List<Part> msgParts(EMail mail) {
		List<Part> parts = new ArrayList<>();
		// By CE's key, so the same file always gives the same numbers
		for (Map.Entry<Integer, EMailAttachment> entry : new TreeMap<>(mail.getAttachments()).entrySet()) {
			EMailAttachment attachment = entry.getValue();
			String name = FileNames.fromFileName(StringUtils.defaultString(attachment.getFileName()), "Attachment");
			long size = attachment.getData() != null ? attachment.getData().length : attachment.getSize();
			parts.add(new Part(parts.size(), name, MimeType.getByFilename(name).toLowerCase(Locale.ROOT), size, false,
					null));
		}
		return List.copyOf(parts);
	}

	/** @return the headers Healdan keeps of the message */
	public EmailFields fields() {
		return eml != null ? EmailFields.fromEml(eml) : EmailFields.fromMsg(msg);
	}

	/** @return its attachments and inline parts, numbered as {@link MessageParts#list} does */
	public List<Part> parts() {
		return parts;
	}

	/**
	 * @param pictures true to keep pictures from the internet
	 * @return the body as a page of its own ({@link EmailBody})
	 */
	public String body(boolean pictures) {
		return eml != null ? EmailBody.clean(eml, pictures) : EmailBody.clean(msg, pictures);
	}

	/**
	 * Writes one part's content.
	 *
	 * @param index the part's index
	 * @param out where to write it; the caller closes it
	 * @throws IllegalArgumentException if the message has no such part
	 */
	public void write(int index, OutputStream out) throws IOException, MessagingException {
		if (index < 0 || index >= parts.size())
			throw new IllegalArgumentException("The message has no part " + index);
		if (eml != null) {
			MessageParts.write(eml, index, out);
			return;
		}
		EMailAttachment attachment = new TreeMap<>(msg.getAttachments()).values().stream().skip(index).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("The message has no part " + index));
		if (attachment.getData() == null && attachment.getSize() > 0)
			throw new IllegalStateException("The message was opened without its attachments' content");
		if (attachment.getData() != null)
			new ByteArrayInputStream(attachment.getData()).transferTo(out);
	}
}
