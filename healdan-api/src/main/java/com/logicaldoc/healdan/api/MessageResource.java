// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

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

	/** The type of the CE link between an email and a document filed from its attachment */
	private static final String LINK_TYPE = EmailFiling.LINK_TYPE;

	/** What a page served inline carries, so that going to it directly runs no script */
	static final String SANDBOX = "sandbox";

	/** What an attachment may be shown as, in place: a PDF or a picture */
	private static final Pattern PICTURE = Pattern.compile("image/[a-z0-9][a-z0-9.+-]*");

	private static final String PDF = "application/pdf";

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
			StoredMessage message = read(document, docNumber);
			EmailFields fields = message.fields();
			Map<String, Long> filed = filed(document, caller.user());
			List<MessageView.MessageAttachment> attachments = new ArrayList<>();
			for (Part part : message.parts())
				if (!part.inline())
					attachments.add(new MessageView.MessageAttachment(part.index(), part.name(), part.type(),
							part.size(), part.attachedMessage(), filed.get(EmailFiling.filingName(part))));
			String body = UriBuilder.fromPath(uri.getBaseUri().getRawPath())
					.path("v1/documents/{docNumber}/message/body").build(docNumber).toString();
			return new MessageView(fields.subject(), fields.from(), fields.to(), fields.cc(), fields.sent(),
					fields.received(), List.copyOf(attachments), body);
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
			byte[] page = read(document, docNumber).body(pictures).getBytes(StandardCharsets.UTF_8);
			return Response.ok(page, "text/html;charset=utf-8").header(HttpHeaders.CONTENT_LENGTH, page.length)
					.header("Content-Security-Policy", EmailBody.policy(pictures))
					.header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").build();
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
		try {
			Document document = emailDocument(docNumber, caller, inline ? Permission.PREVIEW : Permission.DOWNLOAD);
			StoredMessage message = read(document, docNumber);
			Part part = message.parts().stream().filter(p -> p.index() == id).findFirst()
					.orElseThrow(() -> ApiProblem.notFound("Document #%d has no attachment %d.".formatted(docNumber, id)));
			String type = shown(part.type());
			if (inline && !(PDF.equals(type) || PICTURE.matcher(type).matches()))
				throw new ApiProblem(404, "no-preview",
						"Attachment %d of document #%d can't be shown here.".formatted(id, docNumber));

			ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(part.size(), 1 << 20));
			message.write(id, bytes);
			if (!head && !inline)
				DocumentResource.recordDownload(document, caller.user(), caller.session());

			StreamingOutput body = head ? out -> {
				// No body: the headers alone
			} : out -> bytes.writeTo(out);
			Response.ResponseBuilder response = Response.ok(body, inline ? type : MimeType.getByFilename(part.name()))
					.header(HttpHeaders.CONTENT_LENGTH, bytes.size())
					.header(HttpHeaders.CONTENT_DISPOSITION, inline ? "inline" : DocumentResource.disposition(part.name()))
					.header("X-Content-Type-Options", "nosniff");
			if (inline)
				// A top-level visit to an SVG, say, must run no script
				response.header("Content-Security-Policy", SANDBOX);
			return response.build();
		} catch (PersistenceException | IOException | MessagingException e) {
			throw new IllegalStateException("Can't read an attachment of document #" + docNumber, e);
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

			StoredMessage message = read(email, docNumber);
			Part part = message.parts().stream().filter(p -> p.index() == id && !p.inline()).findFirst().orElseThrow(
					() -> ApiProblem.notFound("Document #%d has no attachment %d.".formatted(docNumber, id)));
			upload = File.createTempFile("healdan-upload-", ".tmp");
			try (var out = FileUtils.openOutputStream(upload)) {
				message.write(id, out);
			}

			Document filed = Filer.get().file(folder, EmailFiling.filingName(part),
					request != null ? request.properties() : null, upload, caller);
			try {
				EmailFiling.link(DocumentDAO.get().findById(email.getId()), filed);
			} catch (PersistenceException | RuntimeException e) {
				throw EmailFiling.undo(e, List.of(filed), List.of(), caller);
			}
			DocumentView view = ApiViews.document(DocumentDAO.get().findById(filed.getId()), user);
			String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/documents/{docNumber}")
					.build(view.docNumber()).toString();
			return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location).entity(view).build();
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

	/** Reads the document's current file as a message, before any answer starts */
	private static StoredMessage read(Document document, long docNumber) {
		StoreResource resource = StoreResource.builder().docId(document.getId()).fileVersion(document.getFileVersion())
				.build();
		if (!Store.get().exists(resource))
			throw new ApiProblem(500, "server-error", "The document's file is missing from storage.");
		try (InputStream in = DocumentResource.openStream(resource)) {
			return StoredMessage.open(in, FilenameUtils.getExtension(document.getFileName()));
		} catch (MessagingException e) {
			throw new ApiProblem(404, NOT_AN_EMAIL, "Document #%d can't be read as an email message.".formatted(docNumber));
		} catch (IOException e) {
			throw new IllegalStateException("Can't read the file of document #" + docNumber, e);
		}
	}

	/** The media type, parameters and case taken off */
	private static String shown(String type) {
		int semicolon = type.indexOf(';');
		return (semicolon < 0 ? type : type.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
	}

	/**
	 * @return the doc # of each document filed from the email's attachments that the caller can read, by the name it
	 *         was filed under; the lowest where a name was filed more than once
	 */
	private static Map<String, Long> filed(Document email, User user) throws PersistenceException {
		Map<String, Long> filed = new HashMap<>();
		for (DocumentLink link : DocumentLinkDAO.get().findByDocId(email.getId(), LINK_TYPE)) {
			if (link.getDocument1() == null || link.getDocument1().getId() != email.getId()
					|| link.getDocument2() == null)
				continue;
			Optional<Document> copy = AccessChecks.readableDocument(
					DocumentDAO.get().findById(link.getDocument2().getId()), user);
			if (copy.isEmpty() || copy.get().getDeleted() != 0 || !ApiViews.shows(copy.get()))
				continue;
			filed.merge(copy.get().getFileName(), Long.parseLong(copy.get().getCustomId()), Math::min);
		}
		return filed;
	}
}
