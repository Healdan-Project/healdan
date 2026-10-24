package com.logicaldoc.healdan.api;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.ProfileFieldView;
import com.logicaldoc.healdan.profile.ProfileView;
import com.logicaldoc.healdan.rules.FilingRules;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /v1/folders/{folderId}/form}: the properties form for filing a
 * new document into a folder (spec section 6.7), with the values the document
 * would get and where they come from.
 */
@Component
@Path("/v1/folders/{folderId: [0-9]+}/form")
@Produces(MediaType.APPLICATION_JSON)
public class FormResource {

	/**
	 * @param folderId the folder
	 * @return the form's fields, in order
	 */
	@GET
	public Page<ProfileFieldView> get(@PathParam("folderId") long folderId) {
		User caller = Caller.current().user();
		try {
			Folder folder = fileable(folderId, caller);
			return Page.whole(PropertyValues.views(ProfileView.get().forNewDocument(folder.getId(), caller)));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the form of folder #" + folderId, e);
		}
	}

	/**
	 * @param folderId a folder
	 * @param caller the signed-in user
	 * @return the folder, if the caller may read it and documents can be filed
	 *         into it
	 * @throws ApiProblem {@code 404} as {@link FolderResource#view}, or
	 *         {@code 409 filing-rule} with the rule's words when the folder
	 *         can't hold documents: a Store, a matter's top level, a search
	 *         folder
	 * @throws PersistenceException error at data layer
	 */
	static Folder fileable(long folderId, User caller) throws PersistenceException {
		FolderResource.view(folderId, caller);
		Folder folder = FolderDAO.get().findById(folderId);
		try {
			FilingRules.get().checkDocumentTarget(folder);
		} catch (HealdanRuleException e) {
			throw ApiProblem.conflict("filing-rule", e.getMessage());
		}
		return folder;
	}
}
