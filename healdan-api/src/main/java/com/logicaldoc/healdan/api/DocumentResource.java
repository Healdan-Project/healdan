// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.Multipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.MoveRequest;
import com.logicaldoc.healdan.api.model.NewDocument;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.RenameRequest;
import com.logicaldoc.healdan.docnumber.LegacyDocNumbers;
import com.logicaldoc.healdan.email.MediaTypes;
import com.logicaldoc.healdan.listing.DocumentAuthors.Author;
import com.logicaldoc.healdan.listing.FileKinds;
import com.logicaldoc.healdan.navigation.RecentDocuments;
import com.logicaldoc.healdan.navigation.RecentDocuments.RecentDocument;
import com.logicaldoc.healdan.preview.PreviewState;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.token.DeviceHolds;
import com.logicaldoc.healdan.util.StorageUnreachableException;
import com.logicaldoc.util.MimeType;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.PATCH;
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
 * Documents: filing a new one with {@code POST /v1/documents} (spec section
 * 6.5), and reading one, {@code GET /v1/documents/{docNumber}} and its content
 * (section 6.4). A document the caller can't read is {@code 404}, the same
 * answer as one that doesn't exist (section 7).
 */
@Component
@Path("/v1/documents")
@Produces(MediaType.APPLICATION_JSON)
public class DocumentResource {

	private static final Logger log = LoggerFactory.getLogger(DocumentResource.class);

	/** The media type of a thumbnail, as CE's {@code ThumbnailManager} makes it */
	private static final String PNG = "image/png";

	/** The media type of a preview */
	private static final String PDF = "application/pdf";

	/** How long a client waits before asking again for a preview that's still being made */
	private static final int PREVIEW_RETRY_SECONDS = 5;

	/** As in {@code ServletUtil.saveHistory}: a download within this many seconds of the last is not recorded again */
	private static final int DEDUPLICATE_SECONDS = 30;

	private final DocumentChanges changes;

	@Autowired
	public DocumentResource(DocumentLocks locks) {
		this.changes = new DocumentChanges(locks);
	}

	/**
	 * @param legacy the legacy doc # a document carries from the system it came
	 *        from, matched exactly
	 * @return the documents with it that the caller can read, lowest doc # first
	 */
	@GET
	public Page<DocumentView> byLegacy(@QueryParam("legacyDocNumber") String legacy) {
		if (StringUtils.isBlank(legacy))
			throw ApiProblem.badRequest("Say which legacy doc # to look for with legacyDocNumber.");
		User caller = Caller.current().user();
		try {
			List<Document> found = new ArrayList<>();
			for (long id : LegacyDocNumbers.find(legacy, caller.getTenantId())) {
				Optional<Document> document = AccessChecks.readableDocument(DocumentDAO.get().findById(id), caller);
				if (document.isPresent() && ApiViews.shows(document.get()))
					found.add(document.get());
			}
			found.sort(Comparator.comparingLong(d -> Long.parseLong(d.getCustomId())));
			List<DocumentView> views = new ArrayList<>();
			for (Document document : found)
				views.add(ApiViews.document(document, caller));
			return Page.whole(views);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't find documents by legacy doc #", e);
		}
	}

	/**
	 * Files a new document (spec section 6.5) through CE's create, as the old
	 * UI's upload does, so Healdan's rules give it its doc #, Date filed and
	 * properties, and name an email after its subject. Nothing is stored
	 * unless every check passes. They run in this order, and the first that
	 * fails gives the answer:
	 * <ol>
	 * <li>{@code 400} for a {@code document} part that's missing or isn't the
	 * JSON expected, or a missing {@code file} part;</li>
	 * <li>{@code 404} for a folder the caller can't read, as for one that
	 * doesn't exist;</li>
	 * <li>{@code 409 filing-rule} for a folder that can't hold documents;</li>
	 * <li>{@code 403} without the Write permission on the folder;</li>
	 * <li>the name ({@code 422});</li>
	 * <li>the file: {@code 413 too-large} over the upload limit, {@code 422}
	 * when it's empty;</li>
	 * <li>the properties ({@code 422}).</li>
	 * </ol>
	 * One {@code 422} lists every field that's wrong: the name, the file, then
	 * the properties.
	 * <p>
	 * Only then is the doc # taken from Healdan's counter, and a Word or
	 * LibreOffice file that carries the {@code Healdan Doc ID} property is
	 * stamped with it and version 1.0 before CE's create stores it (M3 spec
	 * section 9.7, plan correction 2). A create that fails then leaves a gap
	 * in the doc #s, as a deleted document does.
	 *
	 * @param document the {@code document} part, as JSON
	 *        ({@link NewDocument})
	 * @param file the {@code file} part
	 * @param uri the request's addresses
	 * @return {@code 201} with the document, and its address as
	 *         {@code Location}
	 */
	@POST
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response create(@Multipart(value = "document", required = false) String document,
			@Multipart(value = "file", required = false) Attachment file, @Context UriInfo uri) {
		NewDocument request = parse(document);
		if (file == null)
			throw ApiProblem.badRequest("Send the file in a part named file.");
		Caller caller = Caller.current();
		User user = caller.user();
		File upload = null;
		try {
			Folder folder = FormResource.fileable(request.folderId(), user);
			if (!AccessChecks.allowed(user, Permission.WRITE, folder))
				throw ApiProblem.forbidden("You may not file documents into folder %d.".formatted(folder.getId()));

			String name = StringUtils.isNotBlank(request.name()) ? request.name() : Uploads.fileName(file);
			upload = Uploads.toTempFile(file);
			long id = Filer.get().file(folder, name, request.properties(), upload, caller).getId();

			if (Boolean.TRUE.equals(request.checkOut())) {
				// Past the Office preview saving the document as its conversion of the new file ends
				CheckoutResource.checkOut(id, caller.session());
				// Held on the device that filed it, as by any check-out through the API; filing names no app
				DeviceHolds.get().record(DocumentDAO.get().findById(id), caller.token(), null);
			}

			DocumentView filed = ApiViews.document(DocumentDAO.get().findById(id), user);
			String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/documents/{docNumber}")
					.build(filed.docNumber()).toString();
			return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location).entity(filed)
					.build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't file a document", e);
		} catch (IOException e) {
			throw new IllegalStateException("Can't read the uploaded file", e);
		} finally {
			FileUtils.deleteQuietly(upload);
		}
	}

	/**
	 * @return the {@code document} part
	 * @throws ApiProblem {@code 400} if it's missing, isn't the JSON expected
	 *         or doesn't say which folder
	 */
	private static NewDocument parse(String document) {
		NewDocument request = ApiJson.part(document, NewDocument.class, "document", "the document's details");
		if (request.folderId() == null)
			throw ApiProblem.badRequest("Say which folder to file the document into with folderId.");
		return request;
	}

	/**
	 * @return the documents the caller filed, changed, checked out or
	 *         downloaded lately and can still read, newest first; at most 50
	 * @param kind {@code email} for the emails alone; every kind by default
	 */
	@GET
	@Path("recent")
	public Page<DocumentView> recent(@QueryParam("kind") String kind) {
		if (kind != null && !FileKinds.EMAIL.equals(kind))
			throw ApiProblem.badRequest("The kind can be email, not %s.".formatted(kind));
		User caller = Caller.current().user();
		try {
			List<Document> documents = new ArrayList<>();
			// The kind filters before the cap of 50, so Emails lists as many as there are
			for (RecentDocument recent : RecentDocuments.get().list(caller.getId(),
					d -> ApiViews.shows(d) && (kind == null
							|| FileKinds.EMAIL.equals(FileKinds.of(FilenameUtils.getExtension(d.getFileName())))))) {
				Document document = DocumentDAO.get().findById(recent.docId());
				if (document != null)
					documents.add(document);
			}
			// The Authors in one query, not one a row
			Map<Long, Author> authors = ApiViews.authors(documents);
			List<DocumentView> views = new ArrayList<>();
			for (Document document : documents)
				views.add(ApiViews.document(document, caller, authors));
			return Page.whole(views);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list recent documents", e);
		}
	}

	/**
	 * @param docNumber the doc #
	 * @return the document
	 */
	@GET
	@Path("{docNumber: [0-9]+}")
	public DocumentView get(@PathParam("docNumber") long docNumber) {
		User caller = Caller.current().user();
		try {
			return ApiViews.document(readable(docNumber, caller), caller);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read document #" + docNumber, e);
		}
	}

	/**
	 * Renames the document (M6 spec section 6.7) through CE's rename. See
	 * {@link DocumentChanges#rename}: it needs the Rename permission, and is
	 * refused for the holds that refuse a save.
	 *
	 * @param docNumber the doc #
	 * @param request the new name; without the document's extension the
	 *        document keeps it, and another extension is {@code 422 invalid}
	 * @return the document
	 */
	@PATCH
	@Path("{docNumber: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public DocumentView rename(@PathParam("docNumber") long docNumber, RenameRequest request) {
		return changes.rename(docNumber, request == null ? null : request.name(), Caller.current());
	}

	/**
	 * Moves the document into another folder (M6 spec section 6.7) through
	 * CE's move. It needs the Move permission on the document and Write on the
	 * folder, which must be able to hold documents ({@code 409
	 * filing-rule}). Moving into another matter stamps the document with it,
	 * and a matter that already holds the message of an email refuses it
	 * ({@code 409 already-filed}).
	 *
	 * @param docNumber the doc #
	 * @param request the folder to move it into
	 * @return the document
	 */
	@POST
	@Path("{docNumber: [0-9]+}/move")
	@Consumes(MediaType.APPLICATION_JSON)
	public DocumentView move(@PathParam("docNumber") long docNumber, MoveRequest request) {
		return changes.move(docNumber, request == null ? null : request.folderId(), Caller.current());
	}

	/**
	 * Moves the document to the trash (M6 spec section 6.7), which the
	 * Classic Interface empties and restores from. It needs the Delete
	 * permission.
	 *
	 * @param docNumber the doc #
	 * @return {@code 204}
	 */
	@DELETE
	@Path("{docNumber: [0-9]+}")
	public Response delete(@PathParam("docNumber") long docNumber) {
		changes.delete(docNumber, Caller.current());
		return Response.noContent().build();
	}

	/**
	 * @param docNumber the doc #
	 * @param version a version of the document, such as {@code 1.0}; the
	 *        current file by default
	 * @return the file, as an attachment
	 */
	@GET
	@Path("{docNumber: [0-9]+}/content")
	@Produces(MediaType.WILDCARD)
	public Response content(@PathParam("docNumber") long docNumber, @QueryParam("version") String version,
			@Context Request request) {
		// JAX-RS answers HEAD with this method too: the headers alone, and nothing recorded
		boolean head = HttpMethod.HEAD.equals(request.getMethod());
		Caller caller = Caller.current();
		try {
			Document document = readable(docNumber, caller.user());
			if (!AccessChecks.allowed(caller.user(), Permission.DOWNLOAD, document))
				throw ApiProblem.forbidden("You may not download document #%d.".formatted(docNumber));

			refuseProtected(document, caller.session(), docNumber);

			FileOfVersion asked = fileOf(document, version, docNumber);
			String fileVersion = asked.fileVersion();
			String fileName = asked.fileName();

			// The store is the truth: the size recorded in the database can be wrong
			StoreResource resource = StoreResource.builder().docId(document.getId()).fileVersion(fileVersion).build();
			if (!Store.get().exists(resource))
				throw new ApiProblem(500, "server-error", "The document's file is missing from storage.");
			long size = Store.get().size(resource);

			// Opened before the answer starts, so that a file that can't be read is an error, never a 200 with a
			// body that stops. The body closes it
			InputStream in = head ? null : openStream(resource);

			// While the caller's session is at hand, and before the first byte is sent
			if (!head)
				recordDownload(document, caller.user(), caller.session());

			StreamingOutput body = head ? out -> {
				// No body: CXF would send the file itself, and a missing entity would make it send Content-Length 0
			} : out -> {
				try (in) {
					in.transferTo(out);
				}
			};
			return Response.ok(body, MimeType.getByFilename(fileName)).header(HttpHeaders.CONTENT_LENGTH, size)
					.header(HttpHeaders.CONTENT_DISPOSITION, disposition(fileName)).build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the content of document #" + docNumber, e);
		}
	}

	/**
	 * @param document the document
	 * @param version a version from its timeline, such as {@code 1.0}; blank for the current file
	 * @return the file version that holds that version's file, and the file's name
	 * @throws ApiProblem {@code 404} if the document has no such version
	 */
	static FileOfVersion fileOf(Document document, String version, long docNumber) throws PersistenceException {
		if (StringUtils.isBlank(version))
			return new FileOfVersion(document.getFileVersion(), document.getFileName());
		Version found = VersionDAO.get().findByVersion(document.getId(), version.strip());
		if (found == null)
			throw ApiProblem.notFound("Document #%d has no version %s".formatted(docNumber, version.strip()));
		return new FileOfVersion(found.getFileVersion(),
				StringUtils.isNotBlank(found.getFileName()) ? found.getFileName() : document.getFileName());
	}

	record FileOfVersion(String fileVersion, String fileName) {
	}

	/**
	 * @return the file's stream
	 * @throws IllegalStateException if it can't be opened; storage that can't be reached is its cause, and answered
	 *         {@code 503} by {@link ProblemMapper}
	 */
	static InputStream openStream(StoreResource resource) {
		try {
			return Store.get().getStream(resource);
		} catch (IOException e) {
			throw new IllegalStateException("Can't read the file of document #" + resource.getDocId(), e);
		}
	}

	/**
	 * A document's preview (M6 spec section 9.3): what to show of one version's file in a page. A picture every browser
	 * draws is its own bytes, as its own content type; a PDF is its own file; any other file's, a TIFF or a HEIC
	 * included, is its PDF conversion ({@link PreviewState}), which Healdan makes in the background for Office files. Read through the checks of the old UI's preview: the Preview permission on
	 * top of reading, and not for a document protected by a password. It is not recorded as a download, and needs no
	 * Download permission.
	 *
	 * @param docNumber the doc #
	 * @param version a version of the document, such as {@code 1.0}; the current file by default
	 * @return {@code 200} with the PDF or the picture; {@code 202} with {@code Retry-After} while a conversion is made
	 * @throws ApiProblem {@code 404 not-found} for a document the caller can't read or a version it doesn't have,
	 *         {@code 404 no-preview} for a file with no preview, {@code 403} without the Preview permission, and
	 *         {@code 503} while the file's storage can't be reached
	 */
	@GET
	@Path("{docNumber: [0-9]+}/preview")
	@Produces(MediaType.WILDCARD)
	public Response preview(@PathParam("docNumber") long docNumber, @QueryParam("version") String version,
			@Context Request request) {
		// JAX-RS answers HEAD with this method too: the headers alone
		boolean head = HttpMethod.HEAD.equals(request.getMethod());
		Caller caller = Caller.current();
		try {
			Document document = readable(docNumber, caller.user());
			if (!AccessChecks.allowed(caller.user(), Permission.PREVIEW, document))
				throw ApiProblem.forbidden("You may not preview document #%d.".formatted(docNumber));
			refuseProtected(document, caller.session(), docNumber);

			FileOfVersion asked = fileOf(document, version, docNumber);
			String extension = FilenameUtils.getExtension(asked.fileName());
			// A TIFF or a HEIC isn't shown as itself: most browsers can't draw it, and Safari draws a TIFF's first page only
			boolean picture = MediaTypes.isDrawnPicture(extension);
			boolean pdf = "pdf".equalsIgnoreCase(extension);
			if (!picture) {
				PreviewState state = PreviewState.of(document, asked.fileVersion(), asked.fileName());
				if (state == PreviewState.PENDING)
					return Response.status(Response.Status.ACCEPTED)
							.header(HttpHeaders.RETRY_AFTER, PREVIEW_RETRY_SECONDS).build();
				if (state == PreviewState.NONE)
					throw new ApiProblem(404, "no-preview", "Document #%d has no preview.".formatted(docNumber));
			}

			// A picture and a PDF are shown as they are; any other file by its conversion
			StoreResource resource = picture || pdf
					? StoreResource.builder().docId(document.getId()).fileVersion(asked.fileVersion()).build()
					: PreviewState.conversion(document.getId(), asked.fileVersion());
			if (!Store.get().exists(resource))
				throw new ApiProblem(500, "server-error", "The document's file is missing from storage.");
			long size = Store.get().size(resource);
			// Opened before the answer starts, as the content's is: an outage is a 503, never a 200 that stops short.
			// A HEAD opens nothing, which would be left unclosed
			InputStream in = head ? null : openStream(resource);
			StreamingOutput body = head ? out -> {
				// No body: CXF would send the file itself, and a missing entity would make it send Content-Length 0
			} : out -> {
				try (in) {
					in.transferTo(out);
				}
			};
			Response.ResponseBuilder response = Response
					.ok(body, picture ? MimeType.getByFilename(asked.fileName()) : PDF)
					.header(HttpHeaders.CONTENT_LENGTH, size).header(HttpHeaders.CONTENT_DISPOSITION, "inline");
			if (picture)
				// Its own bytes, served inline
				InlineHeaders.apply(response);
			return response.build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the preview of document #" + docNumber, e);
		}
	}

	/**
	 * The current file's thumbnail (M3 spec section 9.8), read through the
	 * same checks as its content, and not recorded as a download. Healdan
	 * makes thumbnails in the background, so a new file has none for a
	 * while.
	 *
	 * @param docNumber the doc #
	 * @return the thumbnail, a PNG
	 * @throws ApiProblem {@code 404} for a document the caller can't read and
	 *         one without a thumbnail yet, {@code 403} without the Download
	 *         permission and for a document protected by a password
	 */
	@GET
	@Path("{docNumber: [0-9]+}/thumbnail")
	@Produces(MediaType.WILDCARD)
	public Response thumbnail(@PathParam("docNumber") long docNumber) {
		Caller caller = Caller.current();
		try {
			Document document = readable(docNumber, caller.user());
			if (!AccessChecks.allowed(caller.user(), Permission.DOWNLOAD, document))
				throw ApiProblem.forbidden("You may not download document #%d.".formatted(docNumber));
			refuseProtected(document, caller.session(), docNumber);

			// Where CE's ThumbnailManager puts it
			StoreResource resource = StoreResource.builder().docId(document.getId())
					.fileVersion(document.getFileVersion()).suffixThumbnail().build();
			if (!Store.get().exists(resource))
				throw ApiProblem.notFound("Document #%d has no thumbnail yet.".formatted(docNumber));
			long size = Store.get().size(resource);
			// Opened before the answer starts. A thumbnail that can't be read is one that isn't there yet (spec section
			// 6.5): clients show their placeholder and ask again
			InputStream in;
			try {
				in = Store.get().getStream(resource);
			} catch (IOException e) {
				// Clients ask for thumbnails again and again: an outage, which HealdanStorage warned of, is logged at
				// debug, and anything else, such as a thumbnail that won't decrypt, at warn
				if (StorageUnreachableException.in(e) == null)
					log.warn("The thumbnail of document #{} can't be read: {}", docNumber, e.toString());
				log.debug("The thumbnail of document #{} can't be read", docNumber, e);
				throw ApiProblem.notFound("Document #%d has no thumbnail yet.".formatted(docNumber));
			}
			StreamingOutput body = out -> {
				try (in) {
					in.transferTo(out);
				}
			};
			return Response.ok(body, PNG).header(HttpHeaders.CONTENT_LENGTH, size).build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the thumbnail of document #" + docNumber, e);
		}
	}

	/**
	 * @return the document, if the caller may read it and the API shows it
	 * @throws ApiProblem {@code 404} if there is no such document, the caller
	 *         can't read it or it is outside the Stores
	 */
	static Document readable(long docNumber, User caller) throws PersistenceException {
		Optional<Document> document = AccessChecks.readableDocument(docNumber, caller);
		if (document.isEmpty() || !ApiViews.shows(document.get()))
			throw ApiProblem.notFound("No document #" + docNumber);
		return document.get();
	}

	/**
	 * The one refusal of a password-protected document, for content,
	 * check-out, save and a change of the properties.
	 *
	 * @throws ApiProblem {@code 403} if the document is protected by a password
	 *         that the caller's session hasn't given ({@link ApiViews#opens}).
	 *         No API request gives one, so through the API such a document is
	 *         always refused.
	 */
	static void refuseProtected(Document document, Session session, long docNumber) {
		if (!ApiViews.opens(document, session))
			throw ApiProblem.forbidden(
					"Document #%d is protected by a password. Open it in Healdan's web page.".formatted(docNumber));
	}

	/**
	 * Records CE's {@code event.downloaded}, as {@code ServletUtil.saveHistory}
	 * does for the old UI: not again when the session's latest download is of
	 * the same document and less than 30 seconds old. As there, a failure to
	 * record it is logged and doesn't stop the download.
	 */
	static void recordDownload(Document document, User user, Session session) {
		try {
			DocumentHistory history = new DocumentHistory();
			history.setDocument(document);
			history.setUser(user);
			history.setSession(session);
			history.setPath(FolderDAO.get().computePathExtended(document.getFolder().getId()));
			history.setEvent(DocumentEvent.DOWNLOADED);

			DocumentHistoryDAO histories = DocumentHistoryDAO.get();
			// The session's latest download alone: a device token's session can live for days
			List<DocumentHistory> latest = histories.findByWhere(
					"_entity.userId = :userId and _entity.event = :event and _entity.sessionId = :sessionId",
					Map.of("userId", user.getId(), "event", history.getEvent(), "sessionId", session.getSid()),
					"_entity.date desc, _entity.id desc", 1);
			if (!latest.isEmpty()) {
				Calendar oldest = Calendar.getInstance();
				oldest.setTime(history.getDate());
				oldest.add(Calendar.SECOND, -DEDUPLICATE_SECONDS);
				Date latestDate = latest.get(0).getDate();
				if (latest.get(0).getDocId().equals(history.getDocId()) && !oldest.getTime().after(latestDate))
					return;
			}
			histories.store(history);
		} catch (PersistenceException | RuntimeException e) {
			log.warn("Can't record the download of document {} by user {}", document.getId(), user.getId(), e);
		}
	}

	/**
	 * @return an attachment header that keeps the name: a plain ASCII
	 *         {@code filename} for old clients, and RFC 5987's
	 *         {@code filename*} with the real name
	 */
	static String disposition(String fileName) {
		StringBuilder ascii = new StringBuilder();
		for (char c : fileName.toCharArray())
			ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' && c != '%' ? c : '_');

		StringBuilder encoded = new StringBuilder();
		for (byte b : fileName.getBytes(StandardCharsets.UTF_8)) {
			char c = (char) (b & 0xff);
			if (c < 0x80 && (Character.isLetterOrDigit(c) || "!#$&+-.^_`|~".indexOf(c) >= 0))
				encoded.append(c);
			else
				encoded.append('%').append("%02X".formatted(b & 0xff));
		}
		return "attachment; filename=\"%s\"; filename*=UTF-8''%s".formatted(ascii, encoded);
	}
}
