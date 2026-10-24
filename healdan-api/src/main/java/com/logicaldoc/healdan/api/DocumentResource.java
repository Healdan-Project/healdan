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

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.Multipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.DocumentManager;
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
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.NewDocument;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.docnumber.LegacyDocNumbers;
import com.logicaldoc.healdan.navigation.RecentDocuments;
import com.logicaldoc.healdan.navigation.RecentDocuments.RecentDocument;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileView;
import com.logicaldoc.healdan.profile.ProfileView.ProfileField;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.util.MimeType;

import jakarta.ws.rs.Consumes;
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

	/** The request fields a new document's name and file come in */
	private static final String NAME = "name";

	private static final String FILE = "file";

	/** As in {@code ServletUtil.saveHistory}: a download within this many seconds of the last is not recorded again */
	private static final int DEDUPLICATE_SECONDS = 30;

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
			for (long id : LegacyDocNumbers.find(legacy, caller.getTenantId()))
				AccessChecks.readableDocument(DocumentDAO.get().findById(id), caller).ifPresent(found::add);
			found.removeIf(d -> !ApiViews.hasDocNumber(d));
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

			List<FieldError> errors = new ArrayList<>();
			String name = StringUtils.strip(
					StringUtils.isNotBlank(request.name()) ? request.name() : Uploads.fileName(file));
			Uploads.checkName(name, NAME, errors);

			upload = Uploads.toTempFile(file);
			if (errors.isEmpty() && upload.length() > Uploads.maxBytes())
				throw Uploads.tooLarge();
			FieldError empty = Uploads.emptyError(upload, FILE);
			if (empty != null)
				errors.add(empty);

			Map<String, Object> values = checkProperties(request, folder, user, errors);
			if (!errors.isEmpty())
				throw ApiProblem.validation(errors);

			Document vo = new Document();
			vo.setFolder(folder);
			vo.setFileName(name);
			vo.setTenantId(user.getTenantId());
			vo.setLanguage(StringUtils.defaultIfBlank(user.getLanguage(), "en"));
			PropertyInput.set(vo, values);
			DocumentHistory transaction = new DocumentHistory();
			transaction.setSession(caller.session());
			transaction.setEvent(DocumentEvent.STORED);
			long id = DocumentManager.get().create(upload, vo, transaction).getDocument().getId();

			if (Boolean.TRUE.equals(request.checkOut())) {
				DocumentHistory checkout = new DocumentHistory();
				checkout.setSession(caller.session());
				checkout.setEvent(DocumentEvent.CHECKEDOUT);
				DocumentManager.get().checkout(id, checkout);
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
		if (document == null)
			throw ApiProblem.badRequest("Send the document's details as JSON in a part named document.");
		NewDocument request;
		try {
			request = ApiJson.mapper().readValue(document, NewDocument.class);
		} catch (JsonProcessingException e) {
			throw ApiProblem.badRequest(ProblemMapper.describe(e));
		}
		if (request == null)
			throw ApiProblem.badRequest("The document part must be a JSON object.");
		if (request.folderId() == null)
			throw ApiProblem.badRequest("Say which folder to file the document into with folderId.");
		return request;
	}

	/**
	 * Checks the properties sent for a new document over the form for its
	 * folder: each required one must have a value once inheritance has run.
	 *
	 * @return the values to set
	 */
	private static Map<String, Object> checkProperties(NewDocument request, Folder folder, User user,
			List<FieldError> errors) throws PersistenceException {
		Map<String, JsonNode> sent = request.properties() != null ? request.properties() : Map.of();
		List<ProfileField> form = ProfileView.get().forNewDocument(folder.getId(), user);
		List<FieldError> propertyErrors = new ArrayList<>();
		Map<String, Object> values = PropertyInput.check(form, sent, Inheritance.get().inheritedFrom(folder.getId()),
				user, propertyErrors);
		PropertyInput.checkRequired(form, values, propertyErrors);
		errors.addAll(propertyErrors);
		return values;
	}

	/**
	 * @return the documents the caller filed, changed, checked out or
	 *         downloaded lately and can still read, newest first; at most 50
	 */
	@GET
	@Path("recent")
	public Page<DocumentView> recent() {
		User caller = Caller.current().user();
		try {
			List<DocumentView> views = new ArrayList<>();
			for (RecentDocument recent : RecentDocuments.get().list(caller.getId())) {
				Document document = DocumentDAO.get().findById(recent.docId());
				if (document != null && ApiViews.hasDocNumber(document))
					views.add(ApiViews.document(document, caller));
			}
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

			// As every CE download path: a protected document opens only for a session that unprotected it
			if (document.isPasswordProtected() && !caller.session().getUnprotectedDocs().containsKey(document.getId()))
				throw ApiProblem.forbidden(
						"Document #%d is protected by a password. Open it in Healdan's web page.".formatted(docNumber));

			String fileVersion = document.getFileVersion();
			String fileName = document.getFileName();
			if (StringUtils.isNotBlank(version)) {
				Version found = VersionDAO.get().findByVersion(document.getId(), version.strip());
				if (found == null)
					throw ApiProblem.notFound("Document #%d has no version %s".formatted(docNumber, version.strip()));
				fileVersion = found.getFileVersion();
				if (StringUtils.isNotBlank(found.getFileName()))
					fileName = found.getFileName();
			}

			// The store is the truth: the size recorded in the database can be wrong
			StoreResource resource = StoreResource.builder().docId(document.getId()).fileVersion(fileVersion).build();
			if (!Store.get().exists(resource))
				throw new ApiProblem(500, "server-error", "The document's file is missing from storage.");
			long size = Store.get().size(resource);

			// While the caller's session is at hand, and before the first byte is sent
			if (!head)
				recordDownload(document, caller.user(), caller.session());

			StreamingOutput body = head ? out -> {
				// No body: CXF would send the file itself, and a missing entity would make it send Content-Length 0
			} : out -> {
				// Opened here so that it is closed here, whatever happens to the response
				try (InputStream in = Store.get().getStream(resource)) {
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
	 * @return the document, if the caller may read it
	 * @throws ApiProblem {@code 404} if there is no such document or the caller
	 *         can't read it
	 */
	static Document readable(long docNumber, User caller) throws PersistenceException {
		return AccessChecks.readableDocument(docNumber, caller)
				.orElseThrow(() -> ApiProblem.notFound("No document #" + docNumber));
	}

	/**
	 * Records CE's {@code event.downloaded}, as {@code ServletUtil.saveHistory}
	 * does for the old UI: not again when the session's latest download is of
	 * the same document and less than 30 seconds old. As there, a failure to
	 * record it is logged and doesn't stop the download.
	 */
	private static void recordDownload(Document document, User user, Session session) {
		try {
			DocumentHistory history = new DocumentHistory();
			history.setDocument(document);
			history.setUser(user);
			history.setSession(session);
			history.setPath(FolderDAO.get().computePathExtended(document.getFolder().getId()));
			history.setEvent(DocumentEvent.DOWNLOADED);

			DocumentHistoryDAO histories = DocumentHistoryDAO.get();
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
