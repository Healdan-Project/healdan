// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.Multipart;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.communication.MailUtil;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.EmailCopies;
import com.logicaldoc.healdan.api.model.EmailCopy;
import com.logicaldoc.healdan.api.model.EmailInspection;
import com.logicaldoc.healdan.api.model.EmailPart;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.email.EmailFields;
import com.logicaldoc.healdan.email.EmailIndex;
import com.logicaldoc.healdan.email.MessageIds;
import com.logicaldoc.healdan.email.MessageParts;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.web.PublicAddress;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

/**
 * Email: what is in an {@code eml} before it is filed, and where a message's
 * copies are (M5 spec §6.7, §6.8). Both calls count only the documents the
 * caller can read, so neither reveals one that is hidden (spec §11).
 */
@Component
@Path("/v1/emails")
@Produces(MediaType.APPLICATION_JSON)
public class EmailResource {

	/** The most Message-IDs one lookup takes */
	static final int MAX_MESSAGE_IDS = 100;

	/** The field the {@code eml} comes in, and the one a refusal names */
	static final String FILE = "file";

	/** The code of the {@code 422} for a file that isn't an email */
	static final String NOT_AN_EMAIL = "not-an-email";

	/**
	 * A header line: RFC 5322's field name, kept to the characters of an HTTP
	 * token, which every real one uses, then the colon. JavaMail takes any text
	 * for headers, so this is what tells a message from a file that isn't one.
	 */
	private static final Pattern HEADER = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+:");

	/** Where the web page opens a document */
	private static final String DOCUMENT_PAGE = "/frontend.jsp?docId=";

	/**
	 * Says what is in an email without storing it (spec §6.8): its Message-ID,
	 * subject, sender and sent date, its attachments and inline parts as
	 * {@link MessageParts} lists them, and the copies of the message the
	 * caller can read. The upload limits apply as for
	 * {@code POST /v1/documents}, so this can't be used to put files on the
	 * server: the file is read from a temporary file that is deleted before
	 * the answer.
	 *
	 * @param file the {@code file} part, an {@code eml}
	 * @return the inspection
	 * @throws ApiProblem {@code 400} without a {@code file} part, {@code 413}
	 *         over the upload limit, and {@code 422} when the file isn't an
	 *         email, with field {@code file} and code {@code not-an-email}
	 */
	@POST
	@Path("inspect")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public EmailInspection inspect(@Multipart(value = FILE, required = false) Attachment file) {
		if (file == null)
			throw ApiProblem.badRequest("Send the email in a part named file.");
		User user = Caller.current().user();
		File upload = null;
		try {
			upload = Uploads.toTempFile(file);
			MimeMessage message = read(upload);

			EmailFields fields = EmailFields.fromEml(message);
			List<EmailPart> attachments = new ArrayList<>();
			for (MessageParts.Part part : MessageParts.list(message))
				attachments.add(new EmailPart(part.index(), part.name(), part.type(), part.size(), part.inline(),
						part.attachedMessage()));

			List<DocumentView> copies = new ArrayList<>();
			if (fields.messageId() != null)
				for (Document copy : readable(EmailIndex.get().copies(fields.messageId()), user))
					copies.add(ApiViews.document(copy, user));
			return new EmailInspection(fields.messageId(), fields.subject(), fields.from(), fields.sent(),
					List.copyOf(attachments), List.copyOf(copies));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't inspect an email", e);
		} catch (IOException e) {
			throw new IllegalStateException("Can't read the uploaded file", e);
		} finally {
			FileUtils.deleteQuietly(upload);
		}
	}

	/**
	 * Reads an uploaded {@code eml}, as the calls that take one do, so that
	 * they agree on what is an email.
	 *
	 * @param upload the file, in the upload limit's bounds already or not
	 * @return the message
	 * @throws ApiProblem {@code 413} over the upload limit; {@code 422} when
	 *         the file isn't an email: empty, not readable as MIME, or with no
	 *         header line at all
	 */
	static MimeMessage read(File upload) throws IOException {
		if (upload.length() > Uploads.maxBytes())
			throw Uploads.tooLarge();
		MimeMessage message;
		try (InputStream in = new FileInputStream(upload)) {
			message = MailUtil.readMime(in);
		} catch (MessagingException e) {
			message = null;
		}
		if (message == null || !hasHeaders(message))
			throw ApiProblem.validation(List.of(new FieldError(FILE, NOT_AN_EMAIL,
					"The file isn't an email message. Healdan reads emails saved as .eml.")));
		return message;
	}

	private static boolean hasHeaders(MimeMessage message) {
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

	/**
	 * Says where messages are filed: for each Message-ID asked for, the copies
	 * the caller can read (spec §6.7). A Message-ID may be sent with or without
	 * its angle brackets; the answer gives it without. Each is answered once, in
	 * the order first asked. One that isn't a Message-ID (blank, or over 998
	 * characters) is answered as it was sent, with no copies.
	 *
	 * @param messageIds the {@code messageId} parameter, 1 to
	 *        {@value #MAX_MESSAGE_IDS} times
	 * @param request the request, for the address the web page is at
	 * @return one entry for each Message-ID
	 * @throws ApiProblem {@code 400} for none, or too many
	 */
	@GET
	public EmailCopies copies(@QueryParam("messageId") List<String> messageIds, @Context HttpServletRequest request) {
		if (messageIds == null || messageIds.isEmpty() || messageIds.size() > MAX_MESSAGE_IDS)
			throw ApiProblem.badRequest("Say which messages to look for with messageId, from 1 to %d times."
					.formatted(MAX_MESSAGE_IDS));
		User user = Caller.current().user();
		try {
			// The index normalises what it is given, so it is given what was sent
			Map<String, List<Long>> found = EmailIndex.get().copies(messageIds);

			// One answer for each Message-ID, in the order first asked; a value that isn't one is answered as sent
			String base = base(request);
			Set<String> answered = new HashSet<>();
			Set<String> unreadable = new HashSet<>();
			List<EmailCopy> items = new ArrayList<>();
			for (String sent : messageIds) {
				String id = MessageIds.normalize(sent);
				if (id == null) {
					if (unreadable.add(sent))
						items.add(new EmailCopy(sent, List.of()));
					continue;
				}
				if (!answered.add(id))
					continue;
				List<EmailCopy.Copy> copies = new ArrayList<>();
				for (Document copy : readable(found.getOrDefault(id, Collections.emptyList()), user))
					copies.add(new EmailCopy.Copy(ApiViews.document(copy, user), base + DOCUMENT_PAGE + copy.getId()));
				items.add(new EmailCopy(id, List.copyOf(copies)));
			}
			return new EmailCopies(List.copyOf(items));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't find the copies of an email", e);
		}
	}

	/**
	 * @param ids documents the index named
	 * @param user the user, with its groups
	 * @return those the user can read and the API shows, in the order given
	 */
	private static List<Document> readable(List<Long> ids, User user) throws PersistenceException {
		List<Document> readable = new ArrayList<>();
		for (long id : ids) {
			Optional<Document> copy = AccessChecks.readableDocument(DocumentDAO.get().findById(id), user);
			if (copy.isPresent() && ApiViews.shows(copy.get()))
				readable.add(copy.get());
		}
		return readable;
	}

	/**
	 * @return the address the web page is at: the public address, or, when
	 *         there is none Healdan can use, the one this request was made to
	 */
	private static String base(HttpServletRequest request) {
		return PublicAddress.of(request).map(PublicAddress.Address::base).orElseGet(() -> requested(request));
	}

	/**
	 * @return the address the request was made to, with the install's path and
	 *         no slash at the end
	 */
	private static String requested(HttpServletRequest request) {
		String host = request.getServerName();
		// A bare IPv6 address needs its brackets in a URL
		if (host != null && host.indexOf(':') >= 0 && !host.startsWith("["))
			host = "[" + host + "]";
		String scheme = request.getScheme();
		int port = request.getServerPort();
		boolean usual = port <= 0 || ("http".equalsIgnoreCase(scheme) && port == 80)
				|| ("https".equalsIgnoreCase(scheme) && port == 443);
		return scheme + "://" + host + (usual ? "" : ":" + port) + request.getContextPath();
	}
}
