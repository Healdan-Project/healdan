// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.stores.StoreAccess;
import com.logicaldoc.healdan.admin.stores.StoreService;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.FolderRenames;
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
 * {@code /v1/admin/stores}: the Stores, New Store, and who can see a Store (Admin app spec section 8.6), through
 * {@link StoreService}, which records each change in the administration record. A Store is a workspace directly under
 * the root; LogicalDOC's Default workspace, the Templates Store and the root aren't Stores. A new Store keeps its files
 * on Original until its storage backend is changed, which Phase 3's Stores screen does.
 */
@Component
@Path("/v1/admin/stores")
@AdminRoute(AdminArea.STORES)
@Produces(MediaType.APPLICATION_JSON)
public class AdminStoresResource {

	/**
	 * {@code openapi-admin.yaml}'s {@code Store}.
	 *
	 * @param id its id, its folder's
	 * @param name its name
	 */
	public record StoreView(long id, String name) {

		static StoreView of(Folder store) {
			return new StoreView(store.getId(), store.getName());
		}
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code StoreAccess}: who can see a Store, each row with its level, {@code can-see}
	 * or {@code custom}.
	 *
	 * @param storeId the Store
	 * @param rows what it grants, groups first, then people, each by name
	 */
	public record StoreAccessView(long storeId, List<AccessView.Row> rows) {

		static StoreAccessView of(StoreAccess access) {
			return new StoreAccessView(access.storeId(), access.rows().stream().map(AccessView.Row::of).toList());
		}
	}

	/**
	 * A new Store, {@code openapi-admin.yaml}'s {@code NewStore}.
	 *
	 * @param name its name; null when it wasn't sent
	 * @param groupIds the groups that can see it; null when they weren't sent
	 */
	public record NewStore(String name, List<Long> groupIds) {
	}

	/**
	 * Who can see a Store from then on, {@code openapi-admin.yaml}'s {@code StoreAccessChange}.
	 *
	 * @param groupIds the groups, or people's own groups, that can see it; null when they weren't sent
	 */
	public record StoreAccessChange(List<Long> groupIds) {
	}

	/**
	 * @return every live Store, by name, ignoring case
	 */
	@GET
	public List<StoreView> list() {
		try {
			return StoreService.get().stores().stream().map(StoreView::of).toList();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the Stores", e);
		}
	}

	/**
	 * Creates a Store, and gives each group listed Read, in one transaction. The checks come in the contract's order:
	 * the body, the fields, then what Healdan's rules refuse, as {@code 409 refused}.
	 *
	 * @param body the new Store
	 * @return {@code 201} with the Store
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(NewStore body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new Store as a JSON object.");
		checkGroups(body.groupIds());
		List<FieldError> fields = new ArrayList<>();
		String name = FolderRenames.name(body.name(), fields);
		if (body.groupIds() == null)
			fields.add(groupsRequired());
		if (!fields.isEmpty())
			throw ApiProblem.validation(fields);

		try {
			// A refusal of the service's is a veto, which the ProblemMapper answers 409 refused, wrapped or not
			Folder store = StoreService.get().create(name, body.groupIds(), Caller.current().user());
			return Response.status(Response.Status.CREATED).entity(StoreView.of(store)).build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't create a Store", e);
		}
	}

	/**
	 * @param id a Store
	 * @return who can see it
	 */
	@GET
	@Path("{id: [0-9]+}/access")
	public StoreAccessView access(@PathParam("id") long id) {
		try {
			existing(id);
			return StoreAccessView.of(StoreService.get().access(id));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read who can see Store #" + id, e);
		}
	}

	/**
	 * Sets who can see a Store: each group listed gets Read, keeping its other permissions, and every other group's
	 * row goes. The checks come in the contract's order: the body, the Store, the fields, then what Healdan's rules
	 * refuse, as {@code 409 refused}.
	 *
	 * @param id a Store
	 * @param body the groups that can see it
	 * @return who can see it now
	 */
	@PUT
	@Path("{id: [0-9]+}/access")
	@Consumes(MediaType.APPLICATION_JSON)
	public StoreAccessView setAccess(@PathParam("id") long id, StoreAccessChange body) {
		if (body == null)
			throw ApiProblem.badRequest("Send who can see the Store as a JSON object.");
		checkGroups(body.groupIds());
		try {
			existing(id);
			if (body.groupIds() == null)
				throw ApiProblem.validation(List.of(groupsRequired()));
			StoreService service = StoreService.get();
			service.setAccess(id, body.groupIds(), Caller.current().user());
			return StoreAccessView.of(service.access(id));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't change who can see Store #" + id, e);
		}
	}

	/**
	 * @throws ApiProblem {@code 404 not-found} unless the folder is a live Store
	 */
	private static void existing(long id) throws PersistenceException {
		if (StoreService.get().store(id) == null)
			throw ApiProblem.notFound("There's no Store %d.".formatted(id));
	}

	/**
	 * @param groupIds the groups sent, null for none
	 * @throws ApiProblem {@code 400 bad-request} for a null among them, or a group sent twice: the contract's
	 *         {@code uniqueItems}
	 */
	private static void checkGroups(List<Long> groupIds) {
		if (groupIds == null)
			return;
		Set<Long> seen = new HashSet<>();
		for (Long id : groupIds) {
			if (id == null)
				throw ApiProblem.badRequest("Give each group as its id.");
			if (!seen.add(id))
				throw ApiProblem.badRequest("Name each group once.");
		}
	}

	private static FieldError groupsRequired() {
		return new FieldError("groupIds", "required",
				"Say which groups can see the Store, or give none for administrators alone.");
	}
}
