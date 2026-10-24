package com.logicaldoc.healdan.api;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.Multipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.NewVersion;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.VersionView;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.token.DeviceHolds;
import com.logicaldoc.healdan.token.HoldDevice;
import com.logicaldoc.util.html.HTMLSanitizer;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * A document's timeline, {@code GET /v1/documents/{docNumber}/versions}
 * (spec section 6.4), and saving new content to it with {@code POST} (section
 * 6.6).
 */
@Component
@Path("/v1/documents/{docNumber: [0-9]+}/versions")
@Produces(MediaType.APPLICATION_JSON)
public class VersionResource {

	private static final Logger log = LoggerFactory.getLogger(VersionResource.class);

	/** The kinds of save: an ordinary one, and "Save as new version" */
	private static final String SNAPSHOT = "snapshot";

	private static final String VERSION = "version";

	/** The request fields the new content, its name and the comment come in */
	private static final String FILE = "file";

	private static final String NAME = "name";

	private static final String COMMENT = "comment";

	private static final String EXTENSION = "extension";

	private static final String SAVE_ID = "saveId";

	/** The codes of a save whose precondition failed (M3 spec section 9.5) */
	static final String NOT_HELD = "not-held";

	static final String VERSION_MOVED = "version-moved";

	/** The longest comment CE stores ({@code ld_version.ld_comment}) */
	private static final int MAX_COMMENT_LENGTH = 1000;

	/** A save's id, in any case; {@code hd_hold_device.hd_lastsaveid} holds 64 characters */
	private static final Pattern UUID = Pattern
			.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private final DocumentLocks locks;

	@Autowired
	public VersionResource(DocumentLocks locks) {
		this.locks = locks;
	}

	/**
	 * @param docNumber the doc #
	 * @param offset how many versions to skip
	 * @param limit the most versions to return
	 * @return the versions, newest first
	 */
	@GET
	public Page<VersionView> list(@PathParam("docNumber") long docNumber, @QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		try {
			Document document = DocumentResource.readable(docNumber, Caller.current().user());
			// CE's findByDocId orders by date alone, and a date has one-second precision
			List<VersionView> all = VersionDAO.get()
					.findByWhere("_entity.docId = :docId", Map.of("docId", document.getId()),
							"_entity.versionDate desc, _entity.id desc", null)
					.stream().map(VersionResource::view).toList();
			return paging.slice(all);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the versions of document #" + docNumber, e);
		}
	}

	/**
	 * Saves new content to the document (spec section 6.6), through CE's
	 * check-in as the old UI does. It runs under the document's
	 * {@link DocumentLocks lock}, so two saves of one document are applied one
	 * after the other. The checks run in this order, and the first that fails
	 * gives the answer:
	 * <ol>
	 * <li>{@code 400} for a {@code version} part that's missing or isn't the
	 * JSON expected, or a missing {@code file} part;</li>
	 * <li>{@code 404} for a document the caller can't read;</li>
	 * <li>a {@code saveId} that's the last one stored for the document is a
	 * save sent again after its answer was lost: {@code 201} with the
	 * document as it is, storing nothing, whatever happened to it since;</li>
	 * <li>{@code 403} without the Write permission, or for a document
	 * protected by a password, which the API never opens;</li>
	 * <li>{@code 409 checked-out} for someone else's check-out, administrators
	 * included, and {@code 409 locked} for any lock, the caller's own too;
	 * {@code 409 held-elsewhere} for the caller's own check-out on another of
	 * their devices;</li>
	 * <li>the preconditions ({@link #refusePreconditions}): {@code 409
	 * not-held}, then {@code 409 version-moved};</li>
	 * <li>{@code 409 refused} for an immutable or archived document;</li>
	 * <li>the name ({@code 422}): it must keep the document's extension;</li>
	 * <li>the file: {@code 413 too-large} over the upload limit, {@code 422}
	 * when it's empty;</li>
	 * <li>the comment ({@code 422}), cleaned of markup as the old UI's
	 * check-in cleans it, over 1000 characters; the {@code saveId}
	 * ({@code 422}) when it isn't a UUID.</li>
	 * </ol>
	 * None of these leaves anything behind. A free document is then checked
	 * out to the caller, checked in with the file, and with
	 * {@code keepCheckedOut} checked out again, held on the caller's device;
	 * the check-in on closing ends the device's hold. Once the save is
	 * stored, its {@code saveId} is kept, in the same write as the device. A
	 * rule that refuses the check-in ({@code 409 refused}) leaves the
	 * document as it was, but checked out to the caller: CE writes nothing
	 * when a rule refuses it. A
	 * save CE didn't store is a {@code 500} ({@link #confirmStored}) that says
	 * what's left: the document as it was, or its content the file sent as a
	 * version missing from its timeline; either way the previous version and
	 * its file can still be read. Any other failure ({@code 500}) may come
	 * before CE stores the file, which CE then puts back, or after, when the
	 * document's content is the file sent: the client reads the document and
	 * its versions before it sends the save again.
	 *
	 * @param docNumber the doc #
	 * @param version the {@code version} part, as JSON ({@link NewVersion})
	 * @param file the {@code file} part
	 * @return {@code 201} with the document
	 */
	@POST
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response save(@PathParam("docNumber") long docNumber,
			@Multipart(value = "version", required = false) String version,
			@Multipart(value = "file", required = false) Attachment file) {
		NewVersion request = parse(version);
		if (file == null)
			throw ApiProblem.badRequest("Send the file in a part named file.");
		Caller caller = Caller.current();
		String failure = "Can't save document #" + docNumber;
		try {
			long id = DocumentResource.readable(docNumber, caller.user()).getId();
			DocumentView saved = locks.withLock(id, failure, () -> save(docNumber, request, file, caller));
			return Response.status(Response.Status.CREATED).entity(saved).build();
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * The save itself, under the document's lock
	 */
	private static DocumentView save(long docNumber, NewVersion request, Attachment file, Caller caller)
			throws Exception {
		User user = caller.user();
		Session session = caller.session();
		// Read again: another request may have changed it while this one waited
		Document document = DocumentResource.readable(docNumber, user);
		DeviceHolds devices = DeviceHolds.get();
		// Sent again after its answer was lost (Review Focus 1): it's stored, whatever happened to the document since
		if (request.saveId() != null && request.saveId().equals(devices.lastSaveId(document)))
			return ApiViews.document(document, user);
		if (!AccessChecks.allowed(user, Permission.WRITE, document))
			throw ApiProblem.forbidden("You may not save to document #%d.".formatted(docNumber));
		DocumentResource.refuseProtected(document, session, docNumber);
		// Never over someone else's hold, administrators included (spec section 6.6), nor the caller's elsewhere
		CheckoutResource.refuseHolds(document, user, docNumber);
		CheckoutResource.refuseElsewhere(document, caller, docNumber);
		refusePreconditions(document, request, user, docNumber);
		CheckoutResource.refuseImmutable(document, user, false, docNumber);
		CheckoutResource.refuseArchived(document, docNumber);

		List<FieldError> errors = new ArrayList<>();
		String name = name(request, file, document.getFileName(), errors);
		File upload = null;
		try {
			upload = Uploads.toTempFile(file);
			if (errors.isEmpty() && upload.length() > Uploads.maxBytes())
				throw Uploads.tooLarge();
			FieldError empty = Uploads.emptyError(upload, FILE);
			if (empty != null)
				errors.add(empty);
			// As the old UI's check-in cleans it; CE can't store a longer one, and would fail after the check-out
			String comment = HTMLSanitizer.sanitizeSimpleText(request.comment());
			if (comment != null && comment.length() > MAX_COMMENT_LENGTH)
				errors.add(new FieldError(COMMENT, Uploads.TOO_LONG,
						"The comment can't be longer than %d characters.".formatted(MAX_COMMENT_LENGTH)));
			// Stored after the save, so it must fit
			if (request.saveId() != null && !UUID.matcher(request.saveId()).matches())
				errors.add(new FieldError(SAVE_ID, "invalid", "The save's id must be a UUID."));
			if (!errors.isEmpty())
				throw ApiProblem.validation(errors);

			// The checks above leave it free or checked out to the caller, held here if on any device
			String app = devices.current(document).map(HoldDevice::getApp).orElse(null);
			if (!ApiViews.checkedOutBy(document, user)) {
				DocumentManager.get().checkout(document.getId(),
						CheckoutResource.history(session, DocumentEvent.CHECKEDOUT));
				// So that if the check-in is refused, the check-out it leaves is this device's
				devices.record(DocumentDAO.get().findById(document.getId()), caller.token(), app);
			}
			DocumentHistory checkin = CheckoutResource.history(session, DocumentEvent.CHECKEDIN);
			checkin.setComment(comment);
			String before = document.getVersion();
			// Waits for CE's version record, so the timeline shows the save at once
			DocumentManager.get()
					.checkin(document.getId(), upload, name, VERSION.equals(request.kind()), null, checkin).get();
			confirmStored(document.getId(), docNumber, before, user);

			if (Boolean.TRUE.equals(request.keepCheckedOut()))
				checkOutAgain(document.getId(), session, docNumber);
			Document saved = DocumentDAO.get().findById(document.getId());
			/*
			 * The first save through the API records the device, and the check-in on closing ends its hold. The same
			 * write keeps the save's id, now that the save is stored: under the document's lock, so a request
			 * repeating it that waited for the lock finds it.
			 */
			if (ApiViews.checkedOutBy(saved, user))
				devices.record(saved, caller.token(), app, request.saveId());
			else
				devices.clear(saved, request.saveId());
			return ApiViews.document(saved, user);
		} finally {
			FileUtils.deleteQuietly(upload);
		}
	}

	/**
	 * The save's preconditions (M3 spec section 9.5). They come after the
	 * holds, so another person's hold stays {@code 409 checked-out}, and the
	 * caller's on another device {@code 409 held-elsewhere}.
	 *
	 * @throws ApiProblem with {@code ifHeld}, {@code 409 not-held} for a
	 *         document not checked out to the caller, such as one released or
	 *         checked in from the web page; then {@code 409 version-moved},
	 *         with the {@code document} as it is, when its {@code version}
	 *         isn't {@code ifVersion}
	 */
	private static void refusePreconditions(Document document, NewVersion request, User user, long docNumber)
			throws PersistenceException {
		if (Boolean.TRUE.equals(request.ifHeld()) && !ApiViews.checkedOutBy(document, user))
			throw ApiProblem.conflict(NOT_HELD, "Document #%d isn't checked out to you.".formatted(docNumber));
		if (request.ifVersion() != null && !request.ifVersion().equals(document.getVersion()))
			throw ApiProblem.conflict(VERSION_MOVED,
					"Document #%d changed since the version this save started from: it's at version %s now."
							.formatted(docNumber, document.getVersion()))
					.with("document", ApiViews.document(document, user));
	}

	/**
	 * Makes sure CE stored a save or a filing before the API says it did. CE
	 * writes the version record after the check-in or the creation returns,
	 * and when that write fails it logs the failure and returns the document
	 * all the same. After a save, its own record may then have moved to the
	 * new version or not, so the answer says which (spec sections 6.5 and
	 * 6.6, PR C review concern 4).
	 *
	 * @param docId the document's id
	 * @param docNumber its doc #
	 * @param before its version before the save, or null for a document just
	 *        filed
	 * @param user the caller
	 * @throws ApiProblem {@code 500 server-error} unless the document moved to
	 *         a new version, or has one at all when just filed, and that
	 *         version has its record
	 * @throws PersistenceException error at data layer
	 */
	static void confirmStored(long docId, long docNumber, String before, User user) throws PersistenceException {
		Document after = DocumentDAO.get().findById(docId);
		boolean moved = after != null && !Objects.equals(before, after.getVersion());
		if (moved && VersionDAO.get().findByVersion(docId, after.getVersion()) != null)
			return;

		boolean filed = before == null;
		log.error("The {} of document #{} (id {}) wasn't stored: the document is at version {}, which {}",
				filed ? "filing" : "save", docNumber, docId, after != null ? after.getVersion() : null,
				moved ? "has no version record" : "is the version before the save");
		String stored;
		if (filed)
			stored = "Document #%d was filed, but its version %s isn't in its timeline. Its content is the file sent."
					.formatted(docNumber, after != null ? after.getVersion() : null);
		else if (moved)
			stored = ("Document #%d's save wasn't stored completely: its content is the file sent, as version %s, but "
					+ "that version isn't in its timeline.").formatted(docNumber, after.getVersion());
		else
			stored = "Document #%d's save wasn't stored: its content is as it was.".formatted(docNumber);
		// Filing checks the document out only after this
		String hold = after != null && ApiViews.checkedOutBy(after, user) ? " It's still checked out to you."
				: " It isn't checked out to you.";
		throw new ApiProblem(500, "server-error",
				stored + hold + (filed ? " Don't file it again." : " Send the save again."));
	}

	/**
	 * CE's check-in always releases the check-out, so a save while editing
	 * checks the document out again. The old web page can take it in the
	 * moment between (spec section 6.6): the save stands, and the answer's
	 * {@code hold} shows who has the document.
	 */
	private static void checkOutAgain(long docId, Session session, long docNumber) {
		try {
			DocumentManager.get().checkout(docId, CheckoutResource.history(session, DocumentEvent.CHECKEDOUT));
		} catch (PersistenceException e) {
			log.warn("Saved document #{} but couldn't check it out again: {}", docNumber, e.getMessage());
		}
	}

	/**
	 * @param request the {@code version} part
	 * @param file the {@code file} part
	 * @param current the document's name
	 * @param errors where to add what's wrong with the name, as for a new
	 *        document's, but for its extension: it must be the document's. The
	 *        field is {@code name} for the version part's name, {@code file}
	 *        for the file part's.
	 * @return the name to save under: the version part's, or else the file
	 *         part's, or the document's when neither gives one; an extension
	 *         that differs only in case keeps the document's spelling, as Word
	 *         may send {@code Lease.DOCX} for {@code Lease.docx}
	 */
	private static String name(NewVersion request, Attachment file, String current, List<FieldError> errors) {
		// A browser names a file part it has no name for "blob", so a browser-based client names it here
		boolean named = StringUtils.isNotBlank(request.name());
		String field = named ? NAME : FILE;
		String sent = StringUtils.strip(named ? request.name() : Uploads.fileName(file));
		String name = StringUtils.isBlank(sent) ? current : sent;
		Uploads.checkName(name, field, errors);
		errors.removeIf(error -> EXTENSION.equals(error.code()));

		String extension = Uploads.extension(current);
		String sentExtension = Uploads.extension(name);
		if (!sentExtension.equalsIgnoreCase(extension)) {
			errors.add(new FieldError(field, EXTENSION,
					extension.isEmpty() ? "The file's name can't have an extension, as the document's has none."
							: "The file's name must end in .%s, as the document's does.".formatted(extension)));
			return name;
		}
		return name.substring(0, name.length() - sentExtension.length()) + extension;
	}

	/**
	 * @return the {@code version} part
	 * @throws ApiProblem {@code 400} if it's missing, isn't the JSON expected
	 *         or doesn't say which kind of save it is
	 */
	private static NewVersion parse(String version) {
		if (version == null)
			throw ApiProblem.badRequest("Send the save's details as JSON in a part named version.");
		NewVersion request;
		try {
			request = ApiJson.mapper().readValue(version, NewVersion.class);
		} catch (JsonProcessingException e) {
			throw ApiProblem.badRequest(ProblemMapper.describe(e));
		}
		if (request == null)
			throw ApiProblem.badRequest("The version part must be a JSON object.");
		if (!SNAPSHOT.equals(request.kind()) && !VERSION.equals(request.kind()))
			throw ApiProblem.badRequest("Say what to save with kind: snapshot or version.");
		return request;
	}

	private static VersionView view(Version v) {
		// CE's data can lack a version date; the version's own date stands in
		Date date = v.getVersionDate() != null ? v.getVersionDate() : v.getDate();
		return new VersionView(v.getVersion(), v.getFileVersion(), kind(v.getVersion()), date,
				v.getUserId() == 0L ? null : ApiViews.userRef(v.getUserId(), v.getUsername()), v.getComment(),
				v.getEvent());
	}

	/**
	 * @return {@code version} when the minor number is 0 (such as {@code 2.0}),
	 *         else {@code snapshot}
	 */
	static String kind(String version) {
		int dot = version.lastIndexOf('.');
		return dot < 0 || "0".equals(version.substring(dot + 1)) ? "version" : "snapshot";
	}
}
