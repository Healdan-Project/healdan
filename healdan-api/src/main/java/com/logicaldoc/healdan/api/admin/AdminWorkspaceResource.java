// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.workspace.WorkspaceAdmin;
import com.logicaldoc.healdan.admin.workspace.WorkspaceNode;
import com.logicaldoc.healdan.api.ApiProblem;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code /v1/admin/workspaces}: a workspace's tree, the Workspace Editor's left side (Admin app spec section 8.4), of a
 * matter's own folder or a template's. The folders in it are changed through {@link AdminFoldersResource} and
 * {@link AdminSearchFoldersResource}, each class rooted at its own path, as JAX-RS looks for a route's method only in
 * the classes whose root matches best.
 */
@Component
@Path("/v1/admin/workspaces")
@AdminRoute(AdminArea.MATTERS)
@Produces(MediaType.APPLICATION_JSON)
public class AdminWorkspaceResource {

	/**
	 * @param folderId a matter's own folder or a template's
	 * @return the workspace's top folder, with every live folder and search folder in it
	 */
	@GET
	@Path("{folderId: [0-9]+}/tree")
	public WorkspaceNode tree(@PathParam("folderId") long folderId) {
		try {
			WorkspaceNode tree = WorkspaceAdmin.get().tree(folderId);
			if (tree == null)
				throw ApiProblem.notFound("There's no workspace whose top folder is %d.".formatted(folderId));
			return tree;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the workspace of folder #" + folderId, e);
		}
	}
}
