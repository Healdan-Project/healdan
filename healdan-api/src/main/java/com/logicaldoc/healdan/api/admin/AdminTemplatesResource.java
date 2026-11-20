// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.workspace.TemplateAdmin;
import com.logicaldoc.healdan.admin.workspace.TemplateInfo;
import com.logicaldoc.healdan.admin.workspace.TemplateProblem;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.admin.AdminFoldersResource.NameBody;
import com.logicaldoc.healdan.api.admin.WorkspaceView.DeletedFolder;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.setting.HealdanSettings;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/**
 * {@code /v1/admin/templates}: the workspace templates (Admin app spec section 8.5), their list, New Template (empty
 * or a copy of another), rename, delete, and what New Matter would refuse in a matter made from one. A template's
 * folders are its workspace's, changed through {@link AdminFoldersResource}. Creating and renaming are
 * {@link TemplateAdmin}'s, recorded inside their transactions, holding the Templates Store's lock meanwhile, so two
 * can't give one name twice; a delete goes to the caller's Trash through the public folder routes' changes, and is
 * recorded straight after.
 */
@Component
@Path("/v1/admin/templates")
@AdminRoute(AdminArea.MATTERS)
@Produces(MediaType.APPLICATION_JSON)
public class AdminTemplatesResource {

	/** What the administration record calls a template */
	private static final String TEMPLATE = "template";

	/**
	 * A new template, {@code openapi-admin.yaml}'s {@code NewTemplate}.
	 */
	public record NewTemplateBody(JsonNode name, JsonNode copyOf) {
	}

	/**
	 * @return every template, by name ignoring case, with what each holds and the matters made from it
	 */
	@GET
	public List<TemplateInfo> list() {
		try {
			return TemplateAdmin.get().list();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the templates", e);
		}
	}

	/**
	 * Creates a template, empty or a copy of another.
	 *
	 * @param body its name, and the template to copy
	 * @param uri the request's address, for the template's
	 * @return {@code 201} with the template, and its address as {@code Location}
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(NewTemplateBody body, @jakarta.ws.rs.core.Context UriInfo uri) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new template as a JSON object.");
		String sent = WorkspaceRequests.text(body.name(), "Give the name as text.");
		Long copyOf = WorkspaceRequests.id(body.copyOf(), "Give copyOf as a template's id, or null for none.");
		List<FieldError> errors = new ArrayList<>();
		String name = WorkspaceRequests.name(sent, errors);
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);

		Caller caller = Caller.current();
		long id = withTemplatesLock("Can't create a template",
				() -> TemplateAdmin.get().create(name, copyOf, caller.user(), WorkspaceRequests.sid(caller)));
		String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/admin/templates/{id}").build(id)
				.toString();
		return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location).entity(existing(id))
				.build();
	}

	/**
	 * @param id a template
	 * @return it, as the list gives it
	 */
	@GET
	@Path("{id: [0-9]+}")
	public TemplateInfo get(@PathParam("id") long id) {
		return existing(id);
	}

	/**
	 * Renames a template. Its own name changes nothing.
	 *
	 * @param id a template
	 * @param body its new name
	 * @return the template, renamed
	 */
	@PATCH
	@Path("{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public TemplateInfo rename(@PathParam("id") long id, NameBody body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new name as a JSON object.");
		String sent = WorkspaceRequests.text(body.name(), "Give the name as text.");
		TemplateInfo template = existing(id);
		if (sent != null && StringUtils.strip(sent).equals(template.name()))
			return template;
		List<FieldError> errors = new ArrayList<>();
		String name = WorkspaceRequests.name(sent, errors);
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);

		Caller caller = Caller.current();
		withTemplatesLock("Can't rename template #" + id,
				() -> TemplateAdmin.get().rename(id, name, caller.user(), WorkspaceRequests.sid(caller)));
		return existing(id);
	}

	/**
	 * Moves the template's folder, with its folders and search folders, to the caller's Trash, and records it once it
	 * has. Matters already made from it are unchanged.
	 *
	 * @param id a template
	 * @return the Trash item the delete made, with what it holds
	 */
	@DELETE
	@Path("{id: [0-9]+}")
	public DeletedFolder delete(@PathParam("id") long id) {
		TemplateInfo template = existing(id);
		Caller caller = Caller.current();
		TrashItem item = WorkspaceRequests.changes().deleteWorkspaceFolder(id, caller);
		AdminEvents.get().recordAfter(new AdminEvent(AdminArea.MATTERS, "template.deleted", TEMPLATE, id,
				template.name(), null), caller.user());
		return DeletedFolder.of(item);
	}

	/**
	 * @param id a template
	 * @return what New Matter would refuse in a matter made from it, none for a clean template
	 */
	@GET
	@Path("{id: [0-9]+}/problems")
	public List<TemplateProblem> problems(@PathParam("id") long id) {
		try {
			List<TemplateProblem> problems = TemplateAdmin.get().problems(id);
			if (problems == null)
				throw ApiProblem.notFound("There's no template %d.".formatted(id));
			return problems;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't check template #" + id, e);
		}
	}

	/**
	 * @return the template
	 * @throws ApiProblem {@code 404 not-found} for anything but a template
	 */
	private static TemplateInfo existing(long id) {
		try {
			TemplateInfo template = TemplateAdmin.get().info(id);
			if (template == null)
				throw ApiProblem.notFound("There's no template %d.".formatted(id));
			return template;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read template #" + id, e);
		}
	}

	/** A create or rename of a template, which may throw */
	@FunctionalInterface
	private interface Change<T> {
		T run() throws PersistenceException;
	}

	/**
	 * Runs a create or a rename holding the Templates Store's lock, as the folder routes hold the folder a name goes
	 * into
	 */
	private static <T> T withTemplatesLock(String failure, Change<T> change) {
		Long templates = HealdanSettings.get().templatesStoreId();
		if (templates == null) {
			try {
				return change.run();
			} catch (PersistenceException e) {
				throw new IllegalStateException(failure, e);
			}
		}
		return WorkspaceRequests.changes().withFolderLocks(failure, change::run, templates);
	}
}
