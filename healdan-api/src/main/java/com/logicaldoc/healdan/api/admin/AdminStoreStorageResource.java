// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.List;

import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.dashboard.Usage;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.admin.StorageView.PendingFiles;
import com.logicaldoc.healdan.api.admin.StorageView.StoreStorage;
import com.logicaldoc.healdan.api.admin.StorageView.StuckFile;
import com.logicaldoc.healdan.api.admin.StorageView.SwitchPreview;
import com.logicaldoc.healdan.api.model.FieldError;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code /v1/admin/stores/{id}/backend} and {@code /stuck-files}: the storage backend a Store keeps its files on, its
 * switch to another with its count first, and its files the mover gave up on, with Retry (Admin app spec section 8.6),
 * through the storage port ({@link StoragePort}), which records each change itself. Rooted at the Stores' own
 * address, as CXF matches a request's method only among the classes rooted at the best match's path, but in the
 * Storage area. Without the storage module, each answers {@code 503 storage-unavailable}, after a body that isn't the
 * JSON expected and before anything else; then a folder that isn't a live Store is {@code 404}.
 */
@Component
@Path("/v1/admin/stores")
@AdminRoute(AdminArea.STORAGE)
@Produces(MediaType.APPLICATION_JSON)
public class AdminStoreStorageResource {

	/**
	 * A Store's backend from then on, {@code openapi-admin.yaml}'s {@code StoreBackendChange}.
	 *
	 * @param backendId the backend; null when it wasn't sent
	 */
	public record StoreBackendChange(Long backendId) {
	}

	/**
	 * @param id a Store
	 * @param backendId a backend to count the move to; null for none
	 * @return its backend and its files, and with {@code backendId} what a switch to that backend would move
	 */
	@GET
	@Path("{id: [0-9]+}/backend")
	public StoreStorage backend(@PathParam("id") long id, @QueryParam("backendId") Long backendId) {
		StorageAdministration port = StoragePort.get().require();
		AdminStoresResource.existing(id);
		StorageOverview overview = StoragePort.run("Can't read the storage overview", port::overview);
		SwitchPreview preview = null;
		if (backendId != null) {
			// None is listed while storage backends are off
			StoragePort.existing(port, backendId);
			var moving = StoragePort.run("Can't count Store #" + id + "'s move",
					() -> port.previewSwitch(id, backendId));
			preview = new SwitchPreview(backendId, moving.files(), moving.bytes());
		}
		return storage(id, overview, preview);
	}

	/**
	 * Gives a Store another backend, as the Storage tab does; its files move in the background. Its own backend
	 * changes nothing.
	 *
	 * @param id a Store
	 * @param body the backend
	 * @return its backend and its files, after
	 */
	@PUT
	@Path("{id: [0-9]+}/backend")
	@Consumes(MediaType.APPLICATION_JSON)
	public StoreStorage setBackend(@PathParam("id") long id, StoreBackendChange body) {
		if (body == null)
			throw ApiProblem.badRequest("Say which backend with backendId.");
		StorageAdministration port = StoragePort.get().require();
		AdminStoresResource.existing(id);
		if (body.backendId() == null)
			throw ApiProblem.validation(List.of(new FieldError("backendId", "required", "Choose the backend.")));
		StoragePort.run("Can't give Store #" + id + " another backend", () -> {
			port.setStoreBackend(id, body.backendId(), Caller.current().user());
			return null;
		});
		return storage(id, StoragePort.run("Can't read the storage overview", port::overview), null);
	}

	/**
	 * @param id a Store
	 * @return its files the mover gave up on, the newest documents first, at most 1,000
	 */
	@GET
	@Path("{id: [0-9]+}/stuck-files")
	public List<StuckFile> stuck(@PathParam("id") long id) {
		StorageAdministration port = StoragePort.get().require();
		AdminStoresResource.existing(id);
		return StoragePort.run("Can't list Store #" + id + "'s stuck files", () -> port.stuck(id)).stream()
				.map(StuckFile::of).toList();
	}

	/**
	 * Puts a Store's stuck files back to waiting, and wakes the mover. With none stuck it changes nothing.
	 *
	 * @param id a Store
	 * @return {@code 204}
	 */
	@POST
	@Path("{id: [0-9]+}/stuck-files/retry")
	public Response retry(@PathParam("id") long id) {
		StorageAdministration port = StoragePort.get().require();
		AdminStoresResource.existing(id);
		StoragePort.run("Can't retry Store #" + id + "'s stuck files", () -> {
			port.retry(id, Caller.current().user());
			return null;
		});
		return Response.noContent().build();
	}

	/**
	 * @return the Store's backend and its files, as the overview has them: none while storage backends are off
	 */
	private static StoreStorage storage(long id, StorageOverview overview, SwitchPreview preview) {
		if (!overview.on())
			return new StoreStorage(id, false, null, 0, 0, PendingFiles.NONE, preview);
		StoreBackendInfo store = overview.stores().stream().filter(s -> s.storeId() == id).findFirst().orElse(null);
		if (store == null)
			// Made since the overview was read: on Original, with no file yet
			return new StoreStorage(id, true, backend(overview, AdminStoresResource.ORIGINAL), 0, 0,
					PendingFiles.NONE, preview);
		return new StoreStorage(id, true, backend(overview, store.backendId()), store.files(), store.bytes(),
				PendingFiles.of(store), preview);
	}

	private static Usage.Backend backend(StorageOverview overview, long id) {
		BackendInfo backend = overview.backends().stream().filter(b -> b.id() == id).findFirst().orElse(null);
		return StoreInfos.backend(backend);
	}
}
