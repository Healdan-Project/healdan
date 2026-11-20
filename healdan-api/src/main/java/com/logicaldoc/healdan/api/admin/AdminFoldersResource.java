// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.workspace.WorkspaceAdmin;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.PropertyInput;
import com.logicaldoc.healdan.api.admin.WorkspaceRequests.RuleBody;
import com.logicaldoc.healdan.api.admin.WorkspaceView.DeletedFolder;
import com.logicaldoc.healdan.api.admin.WorkspaceView.FolderProperties;
import com.logicaldoc.healdan.api.admin.WorkspaceView.Rule;
import com.logicaldoc.healdan.api.admin.WorkspaceView.SearchFolder;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.profile.ProfileView.ProfileField;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/**
 * {@code /v1/admin/folders}: a workspace's folders in the Workspace Editor (Admin app spec section 8.4), a matter's
 * or a template's: New Folder, rename, move, delete, the values a folder sets, and New Search Folder. A folder outside
 * a workspace is {@code 404}, and the workspace's top folder is renamed, moved and deleted with its matter or template
 * ({@code 409}).
 * <p>
 * The changes are {@link WorkspaceAdmin}'s, each recorded inside its transaction; a delete goes to the caller's Trash
 * through the public folder routes' changes, and is recorded straight after. A change that gives a folder a name holds
 * the lock of the folder the name goes into, as the public folder routes do, so two can't give one name twice there.
 * <p>
 * The class is rooted at exactly {@code /v1/admin/folders}, as Task 16's folder access routes are: JAX-RS looks for a
 * route's method only in the root classes whose path matches best, so a class rooted deeper would hide the other's.
 */
@Component
@Path("/v1/admin/folders")
@AdminRoute(AdminArea.MATTERS)
@Produces(MediaType.APPLICATION_JSON)
public class AdminFoldersResource {

	/** What the administration record calls a folder */
	private static final String FOLDER = "folder";

	/**
	 * A folder's new name, {@code openapi-admin.yaml}'s {@code FolderRename} and {@code NewWorkspaceFolder}, read as
	 * JSON, so that a name of another type is {@code 400} and one left out {@code 422}.
	 */
	public record NameBody(JsonNode name) {
	}

	/**
	 * Where a folder moves, {@code openapi-admin.yaml}'s {@code FolderMove}.
	 */
	public record MoveBody(JsonNode folderId) {
	}

	/**
	 * A folder's values, the whole set, {@code openapi-admin.yaml}'s {@code FolderPropertiesChange}.
	 */
	public record ValuesBody(Map<String, JsonNode> values) {
	}

	/**
	 * A new search folder, {@code openapi-admin.yaml}'s {@code NewSearchFolder}.
	 */
	public record NewSearchFolderBody(JsonNode name, RuleBody rule) {
	}

	/**
	 * Renames a folder or search folder below the workspace's top folder. Its own name changes nothing.
	 *
	 * @param id the folder
	 * @param body the new name
	 * @return {@code 204}
	 */
	@PATCH
	@Path("{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response rename(@PathParam("id") long id, NameBody body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new name as a JSON object.");
		String sent = WorkspaceRequests.text(body.name(), "Give the name as text.");
		Folder folder = WorkspaceRequests.folder(id);
		if (sent != null && StringUtils.strip(sent).equals(folder.getName()))
			return Response.noContent().build();
		List<FieldError> errors = new ArrayList<>();
		String name = WorkspaceRequests.name(sent, errors);
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);

		Caller caller = Caller.current();
		long parentId = folder.getParentId();
		WorkspaceRequests.changes().withFolderLocks("Can't rename folder #" + id, () -> {
			WorkspaceRequests.refuseMoved(id, parentId);
			return WorkspaceAdmin.get().rename(id, name, caller.user(), WorkspaceRequests.sid(caller));
		}, parentId);
		return Response.noContent().build();
	}

	/**
	 * Moves a folder or search folder below the workspace's top folder, and everything live in it, to the caller's
	 * Trash, and records it once it has.
	 *
	 * @param id the folder
	 * @return the Trash item the delete made, with what it holds
	 */
	@DELETE
	@Path("{id: [0-9]+}")
	public DeletedFolder delete(@PathParam("id") long id) {
		Folder folder = WorkspaceRequests.folder(id);
		Caller caller = Caller.current();
		try {
			WorkspaceAdmin admins = WorkspaceAdmin.get();
			admins.refuseTopDelete(folder);
			String label = admins.label(id);
			TrashItem item = WorkspaceRequests.changes().deleteWorkspaceFolder(id, caller);
			AdminEvents.get().recordAfter(new AdminEvent(AdminArea.MATTERS, "folder.deleted", FOLDER, id, label, null),
					caller.user());
			return DeletedFolder.of(item);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't delete folder #" + id, e);
		}
	}

	/**
	 * Creates a folder in a workspace's folder, its top folder included.
	 *
	 * @param id the folder to create it in
	 * @param body its name
	 * @return {@code 201} with the new folder, as a node of the tree
	 */
	@POST
	@Path("{id: [0-9]+}/children")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(@PathParam("id") long id, NameBody body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new folder as a JSON object.");
		String sent = WorkspaceRequests.text(body.name(), "Give the name as text.");
		WorkspaceRequests.folder(id);
		List<FieldError> errors = new ArrayList<>();
		String name = WorkspaceRequests.name(sent, errors);
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);

		Caller caller = Caller.current();
		long created = WorkspaceRequests.changes().withFolderLocks("Can't create a folder in folder #" + id,
				() -> WorkspaceAdmin.get().createFolder(id, name, caller.user(), WorkspaceRequests.sid(caller)), id);
		return Response.status(Response.Status.CREATED).entity(WorkspaceAdmin.get().node(created)).build();
	}

	/**
	 * Moves a folder or search folder below the workspace's top folder into another folder of the same workspace. A
	 * move into the folder it's in changes nothing.
	 *
	 * @param id the folder
	 * @param body the folder to move it into
	 * @return {@code 204}
	 */
	@POST
	@Path("{id: [0-9]+}/move")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response move(@PathParam("id") long id, MoveBody body) {
		if (body == null)
			throw ApiProblem.badRequest("Say which folder to move it into with folderId.");
		Long targetId = WorkspaceRequests.id(body.folderId(), "Give folderId as a folder's id.");
		Folder folder = WorkspaceRequests.folder(id);
		if (targetId == null)
			throw ApiProblem.validation(
					List.of(new FieldError("folderId", "required", "Say which folder to move it into.")));

		Caller caller = Caller.current();
		long parentId = folder.getParentId();
		WorkspaceRequests.changes().withFolderLocks("Can't move folder #" + id, () -> {
			WorkspaceRequests.refuseMoved(id, parentId);
			return WorkspaceAdmin.get().move(id, targetId, caller.user(), WorkspaceRequests.sid(caller));
		}, parentId, targetId);
		return Response.noContent().build();
	}

	/**
	 * @param id a workspace's folder
	 * @return the values it sets for what's filed below it
	 */
	@GET
	@Path("{id: [0-9]+}/properties")
	public FolderProperties properties(@PathParam("id") long id) {
		Folder folder = WorkspaceRequests.folder(id);
		refuseSearchFolder(folder);
		return view(id);
	}

	/**
	 * Sets the values a folder sets, the whole set: one left out, null, empty or equal to what the folder would
	 * inherit goes back to inheriting. Every value is checked before anything is written.
	 *
	 * @param id a workspace's folder
	 * @param body its values
	 * @return its values, saved
	 */
	@PUT
	@Path("{id: [0-9]+}/properties")
	@Consumes(MediaType.APPLICATION_JSON)
	public FolderProperties setProperties(@PathParam("id") long id, ValuesBody body) {
		if (body == null || body.values() == null)
			throw ApiProblem.badRequest("Send the folder's values as a JSON object in values.");
		Folder folder = WorkspaceRequests.folder(id);
		Caller caller = Caller.current();
		User user = caller.user();
		try {
			List<ProfileField> fields = WorkspaceAdmin.get().properties(id);
			List<String> order = new ArrayList<>(body.values().keySet());
			Set<String> fixed = ProfileSchema.get().fixed();
			List<FieldError> errors = new ArrayList<>();
			Map<String, JsonNode> checked = new LinkedHashMap<>();
			body.values().forEach((name, value) -> {
				if (fixed.contains(name))
					errors.add(new FieldError(name, "read-only", "%s comes from the matter, so a folder can't set it."
							.formatted(StringUtils.capitalize(name))));
				else
					checked.put(name, value);
			});
			// The whole set: a property left out goes back to inheriting
			for (ProfileField field : fields)
				checked.putIfAbsent(field.name(), NullNode.getInstance());
			Map<String, Object> values = PropertyInput.check(fields, checked,
					Inheritance.get().inheritedFrom(folder.getParentId()), user, errors);
			if (!errors.isEmpty()) {
				// In the order sent; a check of the whole, such as the Subclass's, by its field
				errors.sort(Comparator.comparingInt(error -> order.contains(error.field())
						? order.indexOf(error.field()) : order.size()));
				throw ApiProblem.validation(errors);
			}
			refuseSearchFolder(folder);
			if (!values.isEmpty())
				WorkspaceAdmin.get().setProperties(id, values, user, WorkspaceRequests.sid(caller));
			return view(id);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't set the values of folder #" + id, e);
		}
	}

	/**
	 * Creates a search folder in a workspace's folder, its top folder included, its name checked as a folder's.
	 *
	 * @param id the folder to create it in
	 * @param body its name and rule
	 * @param uri the request's address, for the search folder's
	 * @return {@code 201} with the search folder, and its address as {@code Location}
	 */
	@POST
	@Path("{id: [0-9]+}/search-folders")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response createSearchFolder(@PathParam("id") long id, NewSearchFolderBody body,
			@jakarta.ws.rs.core.Context UriInfo uri) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new search folder as a JSON object.");
		String sent = WorkspaceRequests.text(body.name(), "Give the name as text.");
		WorkspaceRequests.checkTypes(body.rule());
		WorkspaceRequests.folder(id);
		List<FieldError> errors = new ArrayList<>();
		String name = WorkspaceRequests.name(sent, errors);
		SearchFolderRule rule = WorkspaceRequests.rule(body.rule(), errors);
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);

		User user = Caller.current().user();
		long created = WorkspaceRequests.changes().withFolderLocks("Can't create a search folder in folder #" + id,
				() -> WorkspaceAdmin.get().createSearchFolder(id, name, rule, user), id);
		try {
			String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/admin/search-folders/{id}")
					.build(created).toString();
			SearchFolder view = new SearchFolder(created, name, Rule.of(WorkspaceAdmin.get().rule(created)));
			return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location).entity(view)
					.build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read search folder #" + created, e);
		}
	}

	/**
	 * @throws ApiProblem {@code 409 refused} for a search folder, which holds nothing and sets no values
	 */
	private static void refuseSearchFolder(Folder folder) {
		if (folder.getType() == HealdanConstants.FOLDER_TYPE_SEARCH)
			throw ApiProblem.conflict("refused", "A search folder sets no values.");
	}

	private static FolderProperties view(long id) {
		try {
			return FolderProperties.of(id, WorkspaceAdmin.get().properties(id));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the values of folder #" + id, e);
		}
	}
}
