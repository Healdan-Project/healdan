package com.logicaldoc.healdan.api;

import java.util.List;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.VersionView;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /v1/documents/{docNumber}/versions}: a document's timeline (spec
 * section 6.4).
 */
@Component
@Path("/v1/documents/{docNumber: [0-9]+}/versions")
@Produces(MediaType.APPLICATION_JSON)
public class VersionResource {

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
			List<VersionView> all = VersionDAO.get().findByDocId(document.getId()).stream()
					.map(VersionResource::view).toList();
			return paging.slice(all);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the versions of document #" + docNumber, e);
		}
	}

	private static VersionView view(Version v) {
		return new VersionView(v.getVersion(), v.getFileVersion(), kind(v.getVersion()), v.getVersionDate(),
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
