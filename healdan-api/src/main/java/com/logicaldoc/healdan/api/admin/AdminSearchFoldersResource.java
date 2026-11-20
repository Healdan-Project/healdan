// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.workspace.WorkspaceAdmin;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.admin.WorkspaceRequests.RuleBody;
import com.logicaldoc.healdan.api.admin.WorkspaceView.Rule;
import com.logicaldoc.healdan.api.admin.WorkspaceView.SearchFolder;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code /v1/admin/search-folders}: a workspace's search folder and its rule, for the Workspace Editor to show and
 * change (Admin app spec section 8.4). A search folder is made, renamed, moved and deleted through
 * {@link AdminFoldersResource}, as a folder is, so its name follows a folder's rules; here only its rule changes.
 */
@Component
@Path("/v1/admin/search-folders")
@AdminRoute(AdminArea.MATTERS)
@Produces(MediaType.APPLICATION_JSON)
public class AdminSearchFoldersResource {

	/**
	 * A search folder's new rule, {@code openapi-admin.yaml}'s {@code SearchFolderChange}: the rule alone, so a body
	 * with {@code name} is {@code 400}, as any member the route doesn't take is.
	 */
	public record RuleChangeBody(RuleBody rule) {
	}

	/**
	 * @param id a workspace's search folder
	 * @return it, with its rule
	 */
	@GET
	@Path("{id: [0-9]+}")
	public SearchFolder get(@PathParam("id") long id) {
		return view(id);
	}

	/**
	 * Changes a search folder's rule, the whole of it, through the search folder service with the name it has, and
	 * records what changed.
	 *
	 * @param id a workspace's search folder
	 * @param body its new rule
	 * @return the search folder, changed
	 */
	@PATCH
	@Path("{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public SearchFolder change(@PathParam("id") long id, RuleChangeBody body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new rule as a JSON object.");
		WorkspaceRequests.checkTypes(body.rule());
		view(id);
		List<FieldError> errors = new ArrayList<>();
		SearchFolderRule rule = WorkspaceRequests.rule(body.rule(), errors);
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);
		try {
			WorkspaceAdmin.get().changeRule(id, rule, Caller.current().user());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't change search folder #" + id, e);
		}
		return view(id);
	}

	/**
	 * @return the search folder, with its rule
	 * @throws ApiProblem {@code 404 not-found} for anything but a workspace's search folder
	 */
	private static SearchFolder view(long id) {
		try {
			WorkspaceAdmin admins = WorkspaceAdmin.get();
			SearchFolderRule rule = admins.rule(id);
			Folder folder = rule != null ? admins.workspaceFolder(id) : null;
			if (folder == null)
				throw ApiProblem.notFound("There's no search folder %d in a workspace.".formatted(id));
			return new SearchFolder(id, folder.getName(), Rule.of(rule));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read search folder #" + id, e);
		}
	}
}
