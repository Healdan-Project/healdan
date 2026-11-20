// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.access.AccessGrant;
import com.logicaldoc.healdan.admin.access.FolderAccessService;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.model.FieldError;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code /v1/admin/folders/{id}/access} and {@code /apply-access}: the Workspace Editor's Who Can Use This Folder
 * (Admin app spec section 8.4), through {@link FolderAccessService}, for a workspace's folder or search folder, its top
 * folder included. Rows go in as exact flags, by group or by person, and come out with their levels.
 * <p>
 * The root is {@code /v1/admin/folders}, with the folder's id in each method's path, so that another resource of the
 * folder routes can share the root: JAX-RS only looks for a method in the root classes whose path matches a request
 * best, and {@code /v1/admin/folders/{id}} would match {@code PATCH /v1/admin/folders/5} better than
 * {@code /v1/admin/folders} does.
 */
@Component
@Path("/v1/admin/folders")
@AdminRoute(AdminArea.MATTERS)
@Produces(MediaType.APPLICATION_JSON)
public class AdminFolderAccessResource {

	/**
	 * A folder's new permissions, {@code openapi-admin.yaml}'s {@code FolderAccessChange}: its own rows, or its
	 * parent's.
	 *
	 * @param followParent true to follow its parent; null when it wasn't sent
	 * @param rows its own rows; null when they weren't sent
	 */
	public record AccessChange(Boolean followParent, List<Grant> rows) {
	}

	/**
	 * One row, {@code openapi-admin.yaml}'s {@code AccessGrant}.
	 *
	 * @param groupId a group, or a person's own group; null when it wasn't sent
	 * @param userId a person; null when it wasn't sent
	 * @param permissions the permissions' names, as the contract gives them; null when they weren't sent
	 */
	public record Grant(Long groupId, Long userId, List<String> permissions) {
	}

	/**
	 * @param id a workspace's folder
	 * @return who can use it
	 */
	@GET
	@Path("{id: [0-9]+}/access")
	public AccessView access(@PathParam("id") long id) {
		try {
			existing(id);
			return AccessView.of(FolderAccessService.get().read(id, Caller.current().user()));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read who can use folder #" + id, e);
		}
	}

	/**
	 * Gives a folder permissions of its own, exactly the rows given, or makes it follow its parent. The checks come
	 * in the contract's order: the body, the folder, the fields, then what Healdan's rules refuse, as
	 * {@code 409 refused}.
	 *
	 * @param id a workspace's folder
	 * @param body its rows, or {@code followParent}
	 * @return who can use it now
	 */
	@PUT
	@Path("{id: [0-9]+}/access")
	@Consumes(MediaType.APPLICATION_JSON)
	public AccessView setAccess(@PathParam("id") long id, AccessChange body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the folder's permissions as a JSON object.");
		List<Set<Permission>> flags = flags(body.rows());
		try {
			existing(id);
			FolderAccessService service = FolderAccessService.get();
			List<FieldError> fields = fields(body, service);
			if (!fields.isEmpty())
				throw ApiProblem.validation(fields);

			if (Boolean.TRUE.equals(body.followParent()))
				return AccessView.of(service.followParent(id, Caller.current().user()));
			List<AccessGrant> grants = new ArrayList<>();
			for (int i = 0; i < body.rows().size(); i++) {
				Grant row = body.rows().get(i);
				grants.add(new AccessGrant(row.groupId(), row.userId(), flags.get(i)));
			}
			return AccessView.of(service.save(id, grants, Caller.current().user()));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't change who can use folder #" + id, e);
		}
	}

	/**
	 * Apply to Subfolders: every folder and search folder below this one, at every level and in the Trash too,
	 * follows it.
	 *
	 * @param id a workspace's folder
	 * @return {@code 204}
	 */
	@POST
	@Path("{id: [0-9]+}/apply-access")
	public Response applyToSubfolders(@PathParam("id") long id) {
		try {
			existing(id);
			Caller caller = Caller.current();
			FolderAccessService.get().applyToSubfolders(id, caller.user(), caller.session().getSid());
			return Response.noContent().build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't apply folder #" + id + "'s permissions to its subfolders", e);
		}
	}

	/**
	 * @throws ApiProblem {@code 404 not-found} unless the folder is a workspace's
	 */
	private static void existing(long id) throws PersistenceException {
		if (FolderAccessService.get().workspaceFolder(id) == null)
			throw ApiProblem.notFound("There's no folder %d in a workspace.".formatted(id));
	}

	/**
	 * @param rows the rows sent, null for none
	 * @return each row's permissions, null where none were sent
	 * @throws ApiProblem {@code 400 bad-request} for a row that isn't an object, and a permission that isn't one of
	 *         the contract's, or is named twice
	 */
	private static List<Set<Permission>> flags(List<Grant> rows) {
		List<Set<Permission>> flags = new ArrayList<>();
		if (rows == null)
			return flags;
		for (Grant row : rows) {
			if (row == null)
				throw ApiProblem.badRequest("Send each row as a JSON object.");
			if (row.permissions() == null) {
				flags.add(null);
				continue;
			}
			Set<Permission> granted = EnumSet.noneOf(Permission.class);
			for (String name : row.permissions()) {
				Permission flag = name != null ? AccessView.permission(name) : null;
				if (flag == null)
					throw ApiProblem.badRequest("Name each permission as the contract does, such as read or write.");
				if (!granted.add(flag))
					throw ApiProblem.badRequest("Name each permission once.");
			}
			flags.add(granted);
		}
		return flags;
	}

	/**
	 * @return what's wrong with the fields, in order: {@code rows} missing, or sent with {@code followParent: true};
	 *         a row naming neither a group nor a person, or both, or one an earlier row named, a person and their
	 *         own group being the same; and a row's permissions missing or empty
	 */
	private static List<FieldError> fields(AccessChange body, FolderAccessService service) {
		List<FieldError> fields = new ArrayList<>();
		boolean follow = Boolean.TRUE.equals(body.followParent());
		if (body.rows() == null) {
			if (!follow)
				fields.add(new FieldError("rows", "required", "Give the folder's rows, or make it follow its parent."));
			return fields;
		}
		if (follow) {
			fields.add(new FieldError("rows", "invalid",
					"Give the folder's rows or make it follow its parent, not both."));
			return fields;
		}

		Set<String> named = new HashSet<>();
		for (int i = 0; i < body.rows().size(); i++) {
			Grant row = body.rows().get(i);
			String field = "rows[%d]".formatted(i);
			if (row.groupId() == null && row.userId() == null)
				fields.add(new FieldError(field, "required", "Name a group or a person."));
			else if (row.groupId() != null && row.userId() != null)
				fields.add(new FieldError(field, "invalid", "Name a group or a person, not both."));
			else if (!named.add(who(row, service)))
				fields.add(new FieldError(field, "duplicate", "Give each group or person one row."));
			if (row.permissions() == null || row.permissions().isEmpty())
				fields.add(new FieldError(field + ".permissions", "required", "Give it at least one permission."));
		}
		return fields;
	}

	/**
	 * @return whom a row names: its group, a person's own group for a person who has one
	 */
	private static String who(Grant row, FolderAccessService service) {
		if (row.groupId() != null)
			return "group " + row.groupId();
		Long own = service.personalGroupId(row.userId());
		return own != null ? "group " + own : "user " + row.userId();
	}
}
