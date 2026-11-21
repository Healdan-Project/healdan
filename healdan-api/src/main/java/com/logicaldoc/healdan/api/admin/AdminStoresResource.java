// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDetail;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendList;
import com.logicaldoc.healdan.admin.stores.StoreAccess;
import com.logicaldoc.healdan.admin.stores.StoreService;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.FolderRenames;
import com.logicaldoc.healdan.api.admin.AdminFoldersResource.NameBody;
import com.logicaldoc.healdan.api.admin.MatterView.PersonBrief;
import com.logicaldoc.healdan.api.admin.StorageView.DeletedStore;
import com.logicaldoc.healdan.api.admin.StorageView.StoreInfo;
import com.logicaldoc.healdan.api.admin.WorkspaceView.DeletedFolder;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.trash.TrashStartup;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code /v1/admin/stores}: the Stores, New Store, a Store's page, its rename, delete and restore, and who can see it
 * (Admin app spec section 8.6), through {@link StoreService}, which records its changes in the administration record;
 * a delete and a restore are recorded straight after them. A Store is a workspace directly under the root;
 * LogicalDOC's Default workspace, the Templates Store and the root aren't Stores, and aren't renamed or deleted here.
 * A Store's create, rename and restore hold the root's folder lock while they check and write its name, so two can't
 * give one name twice. Which storage backend a Store keeps its files on, and its files, are
 * {@link AdminStoreStorageResource}'s routes, under the same address.
 */
@Component
@Path("/v1/admin/stores")
@AdminRoute(AdminArea.STORES)
@Produces(MediaType.APPLICATION_JSON)
public class AdminStoresResource {

	private static final Logger log = LoggerFactory.getLogger(AdminStoresResource.class);

	/** What the administration record calls a Store */
	private static final String STORE = "store";

	/** Original, the backend a Store keeps its files on until it's given another */
	static final long ORIGINAL = 1;

	/** The refusal of another backend than Original where storage backends aren't in use */
	static final String NO_BACKENDS = "Storage backends aren't in use on this server, so a new Store keeps its "
			+ "files on Original.";

	/** The refusal of a backend that doesn't exist */
	static final String NO_BACKEND = "That storage backend doesn't exist. Someone may have removed it meanwhile.";

	/** The refusal of a backend not offered to Stores */
	static final String NOT_OFFERED = "The recovery key of backend %s isn't confirmed yet, so no Store can use it: "
			+ "confirm its recovery key first.";

	/**
	 * {@code openapi-admin.yaml}'s {@code Store}: what a create answers.
	 *
	 * @param id its id, its folder's
	 * @param name its name
	 * @param backendId the backend it keeps its files on: the one asked for, or Original when none was, or when it
	 *        couldn't be given the one asked for
	 */
	public record StoreView(long id, String name, long backendId) {

		static StoreView of(Folder store, long backendId) {
			return new StoreView(store.getId(), store.getName(), backendId);
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
	 * @param backendId the storage backend it keeps its files on; null for Original
	 */
	public record NewStore(String name, List<Long> groupIds, Long backendId) {
	}

	/**
	 * Who can see a Store from then on, {@code openapi-admin.yaml}'s {@code StoreAccessChange}.
	 *
	 * @param groupIds the groups, or people's own groups, that can see it; null when they weren't sent
	 */
	public record StoreAccessChange(List<Long> groupIds) {
	}

	/**
	 * @return every live Store, by name, ignoring case, with what the Stores list shows of each
	 */
	@GET
	public List<StoreInfo> list() {
		try {
			return StoreInfos.of(StoreService.get().stores());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the Stores", e);
		}
	}

	/**
	 * Creates a Store, and gives each group listed Read, in one transaction, then gives it the backend asked for. The
	 * checks come in the contract's order: the body, the fields, then what Healdan's rules refuse, as
	 * {@code 409 refused}, the backend first, so nothing is made for a backend that can't be given. A Store made that
	 * couldn't then be given its backend stays on Original, and the answer's {@code backendId} says so.
	 *
	 * @param body the new Store
	 * @return {@code 201} with the Store, and the backend it keeps its files on
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

		Long backendId = body.backendId() != null && body.backendId() != ORIGINAL ? body.backendId() : null;
		StorageAdministration port = backendId != null ? offered(backendId) : null;
		User actor = Caller.current().user();
		// A refusal of the service's is a veto, which the ProblemMapper answers 409 refused, wrapped or not
		Folder store = withRootLock("Can't create a Store",
				() -> StoreService.get().create(name, body.groupIds(), actor));
		// Where it keeps its files, which the answer says: Original until it's given the backend asked for
		long given = ORIGINAL;
		if (port != null)
			try {
				port.setStoreBackend(store.getId(), backendId, actor);
				given = backendId;
			} catch (PersistenceException | RuntimeException e) {
				// The backend was offered when the create began: it was removed, or changed, since
				log.warn("Store {} was made on Original, as it couldn't be given backend {} after its create: {}",
						store.getId(), backendId, e.getClass().getSimpleName());
				log.debug("Store {} couldn't be given backend {}", store.getId(), backendId, e);
			}
		return Response.status(Response.Status.CREATED).entity(StoreView.of(store, given)).build();
	}

	/**
	 * @return the Stores in the Trash, deleted or removed and not destroyed yet, the most recently deleted first
	 */
	@GET
	@Path("deleted")
	public List<DeletedStore> deleted() {
		int days = HealdanSettings.get().trashRemovedDays();
		return StoreService.get().deleted(Caller.current().user().getTenantId()).stream().map(store -> {
			StoreService.Person by = store.deletedBy();
			return new DeletedStore(store.id(), store.name(), store.trashItemId(), store.state().code(),
					store.matters(), StorageView.date(store.deletedAt()),
					by != null ? new PersonBrief(by.id(), by.username(), by.name()) : null,
					StorageView.date(store.removedAt()),
					store.removedAt() != null ? StorageView.date(
							TrashStartup.destroyAfter(store.removedAt(), days, ZoneId.systemDefault()).toInstant())
							: null);
		}).toList();
	}

	/**
	 * @param id a Store
	 * @return it, for its page
	 */
	@GET
	@Path("{id: [0-9]+}")
	public StoreInfo get(@PathParam("id") long id) {
		return StoreInfos.of(existing(id));
	}

	/**
	 * Renames a Store, through {@link StoreService#rename}, which records it, holding the root's lock. Checks, in
	 * order: the body; a folder that isn't a live Store, but for LogicalDOC's Default workspace, the Templates Store
	 * and the root ({@code 404}); the name ({@code 422}); those three ({@code 409 refused}); then its own name, which
	 * changes nothing; then the service's refusals.
	 *
	 * @param id a Store
	 * @param body its new name
	 * @return the Store, renamed
	 */
	@PATCH
	@Path("{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public StoreInfo rename(@PathParam("id") long id, NameBody body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new name as a JSON object.");
		String sent = WorkspaceRequests.text(body.name(), "Give the name as text.");
		StoreService service = StoreService.get();
		String fixed = service.fixedFolder(id, "renamed");
		Folder store = fixed == null ? existing(id) : null;
		String name = FolderRenames.name(sent);
		if (fixed != null)
			throw ApiProblem.conflict("refused", fixed);
		if (!name.equals(store.getName())) {
			User actor = Caller.current().user();
			withRootLock("Can't rename Store #" + id, () -> service.rename(id, name, actor));
		}
		return StoreInfos.of(existing(id));
	}

	/**
	 * Moves a Store, with its matters and everything live in them, to the caller's Trash as one item
	 * ({@code FolderChanges.deleteStore}), and records it once it has. The matters' rows stay.
	 *
	 * @param id a Store
	 * @return the Trash item the delete made, with what it holds
	 */
	@DELETE
	@Path("{id: [0-9]+}")
	public DeletedFolder delete(@PathParam("id") long id) {
		Caller caller = Caller.current();
		String name = Optional.ofNullable(storeOrNull(id)).map(Folder::getName).orElse(null);
		TrashItem item = WorkspaceRequests.changes().deleteStore(id, caller);
		AdminEvents.get().recordAfter(new AdminEvent(AdminArea.STORES, "store.deleted", STORE, id, name, null),
				caller.user());
		return DeletedFolder.of(item);
	}

	/**
	 * Restores a Store from the Trash, back directly under the root, with what its delete took
	 * ({@link StoreService#restore}), holding the root's lock, and records it once it has. Checks, in order: a folder
	 * that's no Store, live or in the Trash ({@code 404}); a live Store, which isn't in the Trash; then the service's
	 * refusals, each {@code 409 refused}.
	 *
	 * @param id a Store in the Trash
	 * @return the Store, back
	 */
	@POST
	@Path("{id: [0-9]+}/restore")
	public StoreInfo restore(@PathParam("id") long id) {
		StoreService service = StoreService.get();
		try {
			if (!service.isStore(id))
				throw ApiProblem.notFound("There's no Store %d.".formatted(id));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read Store #" + id, e);
		}
		Caller caller = Caller.current();
		// A live Store is refused by the service, as it isn't in the Trash
		Folder store = withRootLock("Can't restore Store #" + id,
				() -> service.restore(id, caller.user(), caller.session()));
		AdminEvents.get().recordAfter(
				new AdminEvent(AdminArea.STORES, "store.restored", STORE, id, store.getName(), null), caller.user());
		return StoreInfos.of(existing(id));
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
	 * @return the live Store
	 * @throws ApiProblem {@code 404 not-found} unless the folder is a live Store
	 */
	static Folder existing(long id) {
		Folder store = storeOrNull(id);
		if (store == null)
			throw ApiProblem.notFound("There's no Store %d.".formatted(id));
		return store;
	}

	/**
	 * @return the live Store; null for any other folder
	 */
	private static Folder storeOrNull(long id) {
		try {
			return StoreService.get().store(id);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read Store #" + id, e);
		}
	}

	/**
	 * Checks the backend a new Store is to keep its files on, before the Store is made: it exists, and is offered to
	 * Stores; with storage backends off, or on a server without the storage module, only Original is
	 *
	 * @param backendId a backend other than Original
	 * @return the storage port, to give the new Store the backend
	 * @throws ApiProblem {@code 409 refused} for a backend the Store can't be given
	 */
	private static StorageAdministration offered(long backendId) {
		Optional<StorageAdministration> found = StoragePort.get().find();
		if (found.isEmpty())
			throw ApiProblem.conflict("refused", NO_BACKENDS);
		StorageAdministration port = found.get();
		// Whether storage backends are on, and the backend, read together
		BackendList backends = StoragePort.run("Can't read the storage backends", port::backends);
		if (!backends.on())
			throw ApiProblem.conflict("refused", NO_BACKENDS);
		BackendDetail backend = backends.backends().stream().filter(b -> b.id() == backendId).findFirst()
				.orElse(null);
		if (backend == null)
			throw ApiProblem.conflict("refused", NO_BACKEND);
		if (!backend.offered())
			throw ApiProblem.conflict("refused", NOT_OFFERED.formatted(backend.name()));
		return port;
	}

	/** A create, rename or restore of a Store, which may throw */
	@FunctionalInterface
	private interface Change<T> {
		T run() throws PersistenceException;
	}

	/**
	 * Runs a change that gives a Store its name holding the root's folder lock, as the folder routes hold the folder a
	 * name goes into, so that two creates, renames or restores can't give one name twice
	 */
	private static <T> T withRootLock(String failure, Change<T> change) {
		long root;
		try {
			root = FolderDAO.get().findRoot(Caller.current().user().getTenantId()).getId();
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
		Callable<T> work = change::run;
		return WorkspaceRequests.changes().withFolderLocks(failure, work, root);
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
