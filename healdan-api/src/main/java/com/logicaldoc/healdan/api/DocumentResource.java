package com.logicaldoc.healdan.api;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.navigation.RecentDocuments;
import com.logicaldoc.healdan.navigation.RecentDocuments.RecentDocument;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.util.MimeType;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * Reading a document: {@code GET /v1/documents/{docNumber}} and its content
 * (spec section 6.4). A document the caller can't read is {@code 404}, the
 * same answer as one that doesn't exist (section 7).
 */
@Component
@Path("/v1/documents")
@Produces(MediaType.APPLICATION_JSON)
public class DocumentResource {

	/** As in {@code ServletUtil.saveHistory}: a download within this many seconds of the last is not recorded again */
	private static final int DEDUPLICATE_SECONDS = 30;

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
				if (document != null)
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
	public Response content(@PathParam("docNumber") long docNumber, @QueryParam("version") String version) {
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
			recordDownload(document, caller.user(), caller.session());

			StreamingOutput body = out -> {
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
	 * does for the old UI: not again for the same document within 30 seconds
	 * in the same session.
	 */
	private static void recordDownload(Document document, User user, Session session) throws PersistenceException {
		DocumentHistory history = new DocumentHistory();
		history.setDocument(document);
		history.setUser(user);
		history.setSession(session);
		history.setPath(FolderDAO.get().computePathExtended(document.getFolder().getId()));
		history.setEvent(DocumentEvent.DOWNLOADED);

		DocumentHistoryDAO histories = DocumentHistoryDAO.get();
		List<DocumentHistory> earlier = histories.findByUserIdAndEvent(user.getId(), history.getEvent(),
				session.getSid());
		if (!earlier.isEmpty()) {
			DocumentHistory latest = earlier.get(earlier.size() - 1);
			Calendar oldest = Calendar.getInstance();
			oldest.setTime(history.getDate());
			oldest.add(Calendar.SECOND, -DEDUPLICATE_SECONDS);
			Date latestDate = latest.getDate();
			if (latest.getDocId().equals(history.getDocId()) && !oldest.getTime().after(latestDate))
				return;
		}
		histories.store(history);
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
