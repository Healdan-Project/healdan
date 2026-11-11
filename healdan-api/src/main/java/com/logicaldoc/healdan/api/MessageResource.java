// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.io.File;
import java.io.FileInputStream;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.healdan.api.model.ProfileFieldView;
import com.logicaldoc.healdan.email.MediaTypes;
import com.logicaldoc.healdan.profile.ProfileView;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentLink;
import com.logicaldoc.core.document.DocumentLinkDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.MessageView;
import com.logicaldoc.healdan.api.model.NewAttachment;
import com.logicaldoc.healdan.email.EmailBody;
import com.logicaldoc.healdan.email.EmailFields;
import com.logicaldoc.healdan.email.MessageParts.Part;
import com.logicaldoc.healdan.email.StoredMessage;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.util.MimeType;

import jakarta.mail.MessagingException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/**
 * A filed email read as a message (M6 spec §6.6, §9.1, §9.4): its headers and
 * attachments, its body as a page of its own, an attachment's bytes, and
 * Save to Matter. {@code eml} and {@code msg} alike ({@link StoredMessage}).
 * A document the caller can't read is {@code 404 not-found}, as everywhere; one
 * that isn't an email is {@code 404 not-an-email}. The body and an attachment
 * shown in place need the Preview permission, an attachment downloaded the
 * Download permission, as for the document itself.
 * <p>
 * Nothing logged here names a subject, an address or a file name.
 */
@Component
@Path("/v1/documents/{docNumber: [0-9]+}/message")
@Produces(MediaType.APPLICATION_JSON)
public class MessageResource {

	/** The code of the {@code 404} for a document that isn't an email, or whose file can't be read as one */
	static final String NOT_AN_EMAIL = "not-an-email";

	private static final Logger log = LoggerFactory.getLogger(MessageResource.class);

	/** What the API's temporary files start with, which the tests look for */
	private static final String TEMP_PREFIX = "healdan-upload-";

	/** The type of the CE link between an email and a document filed from its attachment */
	private static final String LINK_TYPE = EmailFiling.LINK_TYPE;

	private static final String PDF = "application/pdf";

	/** For tests only: told of the saved attachment once it is linked, before the answer is made; null otherwise */
	static volatile Consumer<Document> afterLinked;

	/**
	 * @param docNumber the email's doc #
	 * @param uri the request's addresses
	 * @return its headers, its attachments and the address of its body
	 * @throws ApiProblem {@code 404 not-found}, {@code 404 not-an-email}, {@code 403} without the Preview permission
	 */
	@GET
	public MessageView message(@PathParam("docNumber") long docNumber, @Context UriInfo uri) {
		Caller caller = Caller.current();
		try {
			Document document = emailDocument(docNumber, caller, Permission.PREVIEW);
			try (Opened opened = read(document, docNumber, false)) {
				EmailFields fields = opened.message.fields();
				List<Part> listed = opened.message.parts().stream().filter(p -> !p.inline()).toList();
				Map<Integer, Long> filed = pair(listed, copies(document, caller.user()));
				List<MessageView.MessageAttachment> attachments = new ArrayList<>();
				for (Part part : listed)
					attachments.add(new MessageView.MessageAttachment(part.index(), part.name(), part.type(),
							part.size(), part.attachedMessage(), filed.get(part.index())));
				String body = UriBuilder.fromPath(uri.getBaseUri().getRawPath())
						.path("v1/documents/{docNumber}/message/body").build(docNumber).toString();
				return new MessageView(fields.subject(), fields.from(), fields.to(), fields.cc(), fields.sent(),
						fields.received(), List.copyOf(attachments), body);
			}
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the message of document #" + docNumber, e);
		}
	}

	/**
	 * The body as a page of its own, cleaned ({@link EmailBody}), with its own
	 * policy, which lets the pictures from the internet in only when asked.
	 *
	 * @param docNumber the email's doc #
	 * @param pictures true to let pictures from the internet show
	 * @return the page
	 */
	@GET
	@Path("body")
	@Produces("text/html;charset=utf-8")
	public Response body(@PathParam("docNumber") long docNumber,
			@QueryParam("pictures") @DefaultValue("false") boolean pictures) {
		Caller caller = Caller.current();
		try {
			Document document = emailDocument(docNumber, caller, Permission.PREVIEW);
			byte[] page;
			try (Opened opened = read(document, docNumber, false)) {
				page = opened.message.body(pictures).getBytes(StandardCharsets.UTF_8);
			}
			// No referrer: a picture loaded with Show Pictures doesn't say which server the message is on
			return Response.ok(page, "text/html;charset=utf-8").header(HttpHeaders.CONTENT_LENGTH, page.length)
					.header("Content-Security-Policy", EmailBody.policy(pictures))
					.header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer")
					.header(HttpHeaders.CACHE_CONTROL, "no-store").build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the body of document #" + docNumber, e);
		}
	}

	/**
	 * An attachment: downloaded (Download permission, recorded as CE records a
	 * download of the email), or with {@code inline=true} a PDF or a picture
	 * to show in place (Preview permission, recorded as nothing).
	 *
	 * @param docNumber the email's doc #
	 * @param id the attachment's index
	 * @param inline true to show it in place
	 * @param request the request
	 * @return the attachment's bytes
	 * @throws ApiProblem {@code 404} for an index the email has, and {@code 404 no-preview} for {@code inline} and an
	 *         attachment that is neither a PDF nor a picture
	 */
	@GET
	@Path("parts/{id: [0-9]+}")
	@Produces(MediaType.WILDCARD)
	public Response part(@PathParam("docNumber") long docNumber, @PathParam("id") int id,
			@QueryParam("inline") @DefaultValue("false") boolean inline, @Context Request request) {
		boolean head = HttpMethod.HEAD.equals(request.getMethod());
		Caller caller = Caller.current();
		File content = null;
		try {
			Document document = emailDocument(docNumber, caller, inline ? Permission.PREVIEW : Permission.DOWNLOAD);
			Part part;
			try (Opened opened = read(document, docNumber, true)) {
				// An inline part isn't listed by /message, so no route serves it
				part = opened.message.parts().stream().filter(p -> p.index() == id && !p.inline()).findFirst()
						.orElseThrow(() -> ApiProblem.notFound(
								"Document #%d has no attachment %d.".formatted(docNumber, id)));
				String type = MediaTypes.base(part.type());
				if (inline && !(PDF.equals(type) || MediaTypes.isImage(type)))
					throw new ApiProblem(404, "no-preview",
							"Attachment %d of document #%d can't be shown here.".formatted(id, docNumber));
				if (!head) {
					// On disk, not in memory: the answer streams it
					content = File.createTempFile(TEMP_PREFIX, ".tmp");
					try (var out = FileUtils.openOutputStream(content)) {
						opened.message.write(id, out);
					}
				}
			}
			long size = head ? part.size() : content.length();
			if (!head && !inline)
				DocumentResource.recordDownload(document, caller.user(), caller.session());

			File sent = content;
			StreamingOutput body = head ? out -> {
				// No body: the headers alone
			} : out -> {
				try (InputStream in = new FileInputStream(sent)) {
					in.transferTo(out);
				} finally {
					FileUtils.deleteQuietly(sent);
				}
			};
			String type = MediaTypes.base(part.type());
			Response.ResponseBuilder response = Response.ok(body, inline ? type : MimeType.getByFilename(part.name()))
					.header(HttpHeaders.CONTENT_LENGTH, size)
					.header(HttpHeaders.CONTENT_DISPOSITION, inline ? "inline" : DocumentResource.disposition(part.name()))
					.header("X-Content-Type-Options", "nosniff");
			if (inline)
				InlineHeaders.apply(response);
			// Handed to the answer, which deletes it
			content = null;
			return response.build();
		} catch (PersistenceException | IOException | MessagingException e) {
			throw new IllegalStateException("Can't read an attachment of document #" + docNumber, e);
		} finally {
			FileUtils.deleteQuietly(content);
		}
	}

	/**
	 * Save to Matter: files the attachment as a document of its own in the
	 * email's folder, with the properties sent, linked to the email as
	 * {@code attachment} ({@link EmailFiling}'s link). Filing it again files
	 * another copy, as any upload does; an attached message the matter already
	 * holds is {@code 409 already-filed} with the copy.
	 *
	 * @param docNumber the email's doc #
	 * @param id the attachment's index
	 * @param body the optional {@link NewAttachment}
	 * @param uri the request's addresses
	 * @return {@code 201} with the new document
	 */
	@POST
	@Path("attachments/{id: [0-9]+}/file")
	@Consumes(MediaType.WILDCARD)
	public Response file(@PathParam("docNumber") long docNumber, @PathParam("id") int id, String body,
			@Context UriInfo uri) {
		Caller caller = Caller.current();
		User user = caller.user();
		File upload = null;
		try {
			Document email = emailDocument(docNumber, caller, Permission.DOWNLOAD);
			NewAttachment request = body == null || body.isBlank() ? null
					: ApiJson.part(body, NewAttachment.class, "body", "the properties");
			Folder folder = FormResource.fileable(email.getFolder().getId(), user);
			if (!AccessChecks.allowed(user, Permission.WRITE, folder))
				throw ApiProblem.forbidden("You may not file documents into folder %d.".formatted(folder.getId()));

			Part part;
			upload = File.createTempFile(TEMP_PREFIX, ".tmp");
			try (Opened opened = read(email, docNumber, true)) {
				part = opened.message.parts().stream().filter(p -> p.index() == id && !p.inline()).findFirst()
						.orElseThrow(() -> ApiProblem.notFound(
								"Document #%d has no attachment %d.".formatted(docNumber, id)));
				try (var out = FileUtils.openOutputStream(upload)) {
					opened.message.write(id, out);
				}
			}

			// What the email was filed with applies to the attachment too, and what the request sends wins
			Map<String, JsonNode> properties = new LinkedHashMap<>(emailProperties(email));
			if (request != null && request.properties() != null)
				properties.putAll(request.properties());
			Document filed = Filer.get().file(folder, EmailFiling.filingName(part), properties, upload, caller);
			DocumentLink link;
			try {
				link = EmailFiling.link(DocumentDAO.get().findById(email.getId()), filed);
			} catch (PersistenceException | RuntimeException e) {
				throw EmailFiling.undo(e, List.of(filed), List.of(), caller);
			}
			try {
				Consumer<Document> hook = afterLinked;
				if (hook != null)
					hook.accept(filed);
				DocumentView view = ApiViews.document(DocumentDAO.get().findById(filed.getId()), user);
				String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/documents/{docNumber}")
						.build(view.docNumber()).toString();
				return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location).entity(view)
						.build();
			} catch (PersistenceException | RuntimeException e) {
				// Filed, linked, and no answer to give: take it back, as a failed filing of an email does
				throw EmailFiling.undo(e, List.of(filed), List.of(link), caller);
			}
		} catch (PersistenceException | MessagingException | IOException e) {
			throw new IllegalStateException("Can't save an attachment of document #" + docNumber, e);
		} finally {
			FileUtils.deleteQuietly(upload);
		}
	}

	/**
	 * @return the email, if the caller may read it, holds the permission and the document is an email
	 * @throws ApiProblem {@code 404 not-found} (checked first), {@code 404 not-an-email}, {@code 403} without the
	 *         permission or for a document protected by a password
	 */
	private static Document emailDocument(long docNumber, Caller caller, Permission permission)
			throws PersistenceException {
		Document document = DocumentResource.readable(docNumber, caller.user());
		String extension = FilenameUtils.getExtension(document.getFileName()).toLowerCase(Locale.ROOT);
		if (!HealdanConstants.EMAIL_EXTENSIONS.contains(extension))
			throw new ApiProblem(404, NOT_AN_EMAIL, "Document #%d is not an email.".formatted(docNumber));
		if (!AccessChecks.allowed(caller.user(), permission, document))
			throw ApiProblem.forbidden("You may not %s document #%d.".formatted(
					permission == Permission.DOWNLOAD ? "download" : "preview", docNumber));
		DocumentResource.refuseProtected(document, caller.session(), docNumber);
		return document;
	}

	/** A message read from a copy of the stored file, which is deleted with it */
	private static final class Opened implements AutoCloseable {

		final StoredMessage message;

		private final File copy;

		Opened(StoredMessage message, File copy) {
			this.message = message;
			this.copy = copy;
		}

		@Override
		public void close() {
			try {
				message.close();
			} catch (IOException e) {
				log.debug("Can't close a message", e);
			}
			FileUtils.deleteQuietly(copy);
		}
	}

	/**
	 * Reads the document's current file as a message, before any answer starts. The file is copied to a temporary
	 * file and an {@code eml} is read in place from it, so no more than its parts is held in memory.
	 *
	 * @param content true when an attachment's bytes are needed
	 */
	private static Opened read(Document document, long docNumber, boolean content) {
		StoreResource resource = StoreResource.builder().docId(document.getId()).fileVersion(document.getFileVersion())
				.build();
		if (!Store.get().exists(resource))
			throw new ApiProblem(500, "server-error", "The document's file is missing from storage.");
		File copy = null;
		try {
			copy = File.createTempFile(TEMP_PREFIX, ".tmp");
			try (InputStream in = DocumentResource.openStream(resource); var out = FileUtils.openOutputStream(copy)) {
				in.transferTo(out);
			}
			return new Opened(StoredMessage.open(copy, FilenameUtils.getExtension(document.getFileName()), content),
					copy);
		} catch (MessagingException e) {
			FileUtils.deleteQuietly(copy);
			throw new ApiProblem(404, NOT_AN_EMAIL, "Document #%d can't be read as an email message.".formatted(docNumber));
		} catch (IOException | RuntimeException e) {
			FileUtils.deleteQuietly(copy);
			if (e instanceof RuntimeException runtime)
				throw runtime;
			throw new IllegalStateException("Can't read the file of document #" + docNumber, e);
		}
	}

	/** A document filed from one of the email's attachments, as pairing needs it */
	private record Copy(long docNumber, String name, long size) {
	}

	/**
	 * @return the documents filed from the email's attachments that the caller can read, lowest doc # first
	 */
	private static List<Copy> copies(Document email, User user) throws PersistenceException {
		List<Copy> copies = new ArrayList<>();
		for (DocumentLink link : DocumentLinkDAO.get().findByDocId(email.getId(), LINK_TYPE)) {
			if (link.getDocument1() == null || link.getDocument1().getId() != email.getId()
					|| link.getDocument2() == null)
				continue;
			Optional<Document> copy = AccessChecks.readableDocument(
					DocumentDAO.get().findById(link.getDocument2().getId()), user);
			if (copy.isEmpty() || copy.get().getDeleted() != 0 || !ApiViews.shows(copy.get()))
				continue;
			copies.add(new Copy(Long.parseLong(copy.get().getCustomId()), copy.get().getFileName(),
					copy.get().getFileSize()));
		}
		copies.sort(Comparator.comparingLong(Copy::docNumber));
		return copies;
	}

	/**
	 * Pairs each part with the document filed from it (M6 R61): by the name it was filed under and its size, then,
	 * for parts the same in both, the k-th part with the k-th copy by doc #. What is left pairs by name alone, in
	 * order, since a Word or LibreOffice file's size changes when it is stamped with its doc #. A part with no copy
	 * has none.
	 *
	 * @param parts the attachments, in order
	 * @return each paired part's index and the doc # of its copy
	 */
	static Map<Integer, Long> pair(List<Part> parts, List<Copy> copies) {
		Map<Integer, Long> paired = new HashMap<>();
		Set<Copy> used = new HashSet<>();
		for (boolean sizeToo : new boolean[] { true, false })
			for (Part part : parts) {
				if (paired.containsKey(part.index()))
					continue;
				String name = withoutNumber(EmailFiling.filingName(part));
				for (Copy copy : copies)
					if (!used.contains(copy) && withoutNumber(copy.name()).equals(name) && (!sizeToo || copy.size() == part.size())) {
						used.add(copy);
						paired.put(part.index(), copy.docNumber());
						break;
					}
			}
		return paired;
	}

	/** CE names a second file of one name in a folder {@code Name(1).ext}, and so on */
	private static final Pattern NUMBERED = Pattern.compile("^(.*)\\(\\d+\\)(\\.[^.]*)?$");

	/** @return the name as it was before CE numbered it */
	private static String withoutNumber(String name) {
		Matcher numbered = NUMBERED.matcher(name);
		return numbered.matches() ? numbered.group(1) + (numbered.group(2) == null ? "" : numbered.group(2)) : name;
	}

	/**
	 * @return the properties the email was filed with, to apply to its attachments: its own values of the properties
	 *         that can be changed, leaving out the email fields (which the attachment, not being an email, has none
	 *         of, and which can't be sent) and the legacy doc #
	 */
	private static Map<String, JsonNode> emailProperties(Document email) throws PersistenceException {
		Map<String, JsonNode> properties = new LinkedHashMap<>();
		Document current = DocumentDAO.get().findById(email.getId(), true);
		for (ProfileFieldView field : PropertyValues.views(ProfileView.get().forDocument(current)))
			if (field.editable() && "local".equals(field.source()) && field.value() != null
					&& !HealdanConstants.ATTR_LEGACYDOCNUM.equals(field.name()))
				properties.put(field.name(), ApiJson.mapper().valueToTree(field.value()));
		return properties;
	}
}
