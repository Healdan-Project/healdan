// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.api.model.RestoreRequest;
import com.logicaldoc.healdan.api.model.RestoredItem;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.api.model.TrashPage;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.trash.TrashItemRow;
import com.logicaldoc.healdan.trash.TrashMemberRow;
import com.logicaldoc.healdan.trash.TrashQuery;
import com.logicaldoc.healdan.trash.TrashRemoval;
import com.logicaldoc.healdan.trash.TrashRestore;
import com.logicaldoc.healdan.trash.TrashState;
import com.logicaldoc.healdan.trash.TrashStore;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The Trash (folders and Trash spec 4.4, 4.5, 6.5): listing it, restoring an
 * item, removing one, and emptying the caller's. An item is one delete, which
 * {@link TrashViews} shows.
 * <p>
 * The work is {@link TrashRestore}'s and {@link TrashRemoval}'s, so a restore
 * runs through CE's own folder or document restore, where Healdan's hooks and
 * listeners decide as on every route. The checks here come first only to give
 * the refusals people meet codes of their own:
 * <ul>
 * <li>A person sees, restores and removes only the items they deleted, while
 * in the Trash. An administrator sees, restores and removes anyone's, and
 * restores from Removed. Any other item is {@code 404}, as one that doesn't
 * exist.</li>
 * <li>Restoring needs Delete on the item, as on every route (spec 4.4).</li>
 * <li>A Store, which only an administrator deletes, in the Classic Interface,
 * is restored there: CE puts it back under the root whatever place is asked,
 * so it's {@code 409 refused}, not {@code place-gone}, before anything is
 * written.</li>
 * <li>A restore back where it was that the place refuses, because it isn't
 * live, the caller can't read it, or the rules or the caller's permissions no
 * longer allow it there, is {@code 409 place-gone}, which a client answers by
 * asking where. A restore into a folder asked for is refused as a move there
 * would be.</li>
 * </ul>
 */
@Component
@Path("/v1/trash")
@Produces(MediaType.APPLICATION_JSON)
public class TrashResource {

	private static final Logger log = LoggerFactory.getLogger(TrashResource.class);

	private static final String MINE = "mine";

	private static final String REMOVED = "removed";

	private static final String DELETED = "deleted";

	private static final String DESTROYED = "destroyed";

	private static final Set<String> VIEWS = Set.of(MINE, "everyone", REMOVED);

	/** One for each of the Trash's columns, in the order a refusal names them */
	private static final List<String> SORT_NAMES = List.of("name", "docNumber", "deletedFrom", "deletedBy", DELETED,
			"removedBy", REMOVED, DESTROYED);

	private static final Set<String> SORTS = Set.copyOf(SORT_NAMES);

	/** What a refusal names: {@code name, docNumber, ..., removed or destroyed} */
	private static final String SORTS_NAMED = String.join(", ", SORT_NAMES.subList(0, SORT_NAMES.size() - 1)) + " or "
			+ SORT_NAMES.getLast();

	/** The sorts by a time, newest first unless asked; the others go A to Z, smallest first */
	private static final Set<String> TIMES = Set.of(DELETED, REMOVED, DESTROYED);

	/** What a person may restore or remove: their own items in the Trash */
	private static final Set<TrashState> IN_THE_TRASH = Set.of(TrashState.DELETED);

	/** What an administrator may restore: anyone's items, in the Trash or removed */
	private static final Set<TrashState> RESTORABLE_BY_ADMINS = Set.of(TrashState.DELETED, TrashState.REMOVED);

	/**
	 * @param view {@code mine} (the default), {@code everyone} or
	 *        {@code removed}; the last two for administrators only
	 * @param sort {@code name}, {@code docNumber}, {@code deletedFrom},
	 *        {@code deletedBy}, {@code deleted}, {@code removedBy},
	 *        {@code removed} or {@code destroyed}, which {@link TrashStore}
	 *        sorts by; by default {@code removed} in the {@code removed} view
	 *        and {@code deleted} in the others
	 * @param order {@code asc} or {@code desc}; by default {@code desc} by a
	 *        time, newest first, and {@code asc} by anything else
	 * @param offset how many items to skip
	 * @param limit the most items to return
	 * @return a page of the items
	 */
	@GET
	public TrashPage list(@QueryParam("view") String view, @QueryParam("sort") String sort,
			@QueryParam("order") String order, @QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		String shown = StringUtils.isEmpty(view) ? MINE : view;
		if (!VIEWS.contains(shown))
			throw ApiProblem.badRequest("The view can be mine, everyone or removed, not %s.".formatted(view));
		String by = StringUtils.isEmpty(sort) ? (REMOVED.equals(shown) ? REMOVED : DELETED) : sort;
		if (!SORTS.contains(by))
			throw ApiProblem.badRequest("The sort can be %s, not %s.".formatted(SORTS_NAMED, sort));
		if (order != null && !"asc".equals(order) && !"desc".equals(order))
			throw ApiProblem.badRequest("The order can be asc or desc, not %s.".formatted(order));
		boolean descending = order != null ? "desc".equals(order) : TIMES.contains(by);
		Paging paging = Paging.of(offset, limit);

		User user = Caller.current().user();
		try {
			if (!MINE.equals(shown) && !AccessChecks.isAdmin(user))
				throw new ApiProblem(403, "admins-only",
						"Only administrators see everyone's Trash and what was removed from it.");
			TrashQuery query = new TrashQuery(shown, user.getId(), user.getTenantId(), by, descending,
					paging.offset(), paging.limit());
			TrashStore store = TrashStore.get();
			List<TrashItemRow> rows = store.page(query);
			// The top members and the setting are read once for the whole page
			Map<Long, TrashMemberRow> tops = store.tops(rows.stream().map(TrashItemRow::id).toList());
			int removedDays = HealdanSettings.get().trashRemovedDays();
			List<TrashItem> items = new ArrayList<>();
			for (TrashItemRow row : rows)
				items.add(TrashViews.item(row, tops.get(row.id()), removedDays, user));
			return new TrashPage(items, paging.offset(), paging.limit(), store.count(query), removedDays);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the Trash", e);
		}
	}

	/**
	 * Restores an item back where it was deleted from, or into the folder the
	 * body asks for. Checks, in order: a body that isn't the JSON expected
	 * ({@code 400}, {@code 415}); an item the caller may not restore
	 * ({@code 404}); no Delete on it ({@code 403 forbidden}); a Store, which is
	 * restored in the Classic Interface ({@code 409 refused}); then, back where
	 * it was, a place that isn't there for it ({@code 409 place-gone}); or,
	 * into a folder asked for, as a move there: a folder the caller can't read
	 * ({@code 404}), one that can't hold it ({@code 409 filing-rule}), no Add
	 * for a folder or Write for a document on it, unless it is where the item
	 * was ({@code 403 forbidden}), a folder into a matter's top level, unless
	 * the caller is an administrator ({@code 403 admins-only}); then the
	 * rules' own refusals, an email the matter holds already
	 * ({@code 409 already-filed}) and any other ({@code 409 refused}).
	 *
	 * @param id the item's id
	 * @param body the {@link RestoreRequest}, as JSON; optional, so read here
	 *        rather than by JAX-RS, which refuses a request without a content
	 *        type
	 * @param headers the request's headers, for the body's content type
	 * @return where it went, and the item, restored
	 */
	@POST
	@Path("{id: [0-9]+}/restore")
	public RestoredItem restore(@PathParam("id") long id, String body, @Context HttpHeaders headers) {
		Long folderId = request(body, headers.getMediaType()).folderId();
		Caller caller = Caller.current();
		User user = caller.user();
		try {
			boolean admin = AccessChecks.isAdmin(user);
			Set<TrashState> restorable = admin ? RESTORABLE_BY_ADMINS : IN_THE_TRASH;
			TrashItemRow row = item(id, user, admin, restorable);
			boolean folder = TrashViews.isFolder(row);
			StoredRows rows = StoredRows.get();
			if (!admin && !(folder ? rows.mayDelete(row.objectId(), user.getId())
					: rows.mayDeleteDocument(row.objectId(), user.getId())))
				throw ApiProblem.forbidden("You need the Delete permission on Trash item %d to restore it.".formatted(id));
			// A Store deleted in the Classic Interface: CE puts one back under the root whatever is asked, which no
			// place a client could choose would change, so it's refused before anything is written
			if (folder && isStore(row, rows))
				throw ApiProblem.conflict("refused", "A Store is restored in the Classic Interface.");

			long target = folderId != null ? folderAskedFor(row, folder, folderId, user, admin)
					: placeOf(row, folder, user, admin);
			long placeId;
			try {
				placeId = TrashRestore.get().restore(id, target, user, caller.session());
			} catch (PersistenceException e) {
				// Another restore, or a removal, may have come first: then it's no longer there to restore
				item(id, user, admin, restorable);
				// A veto of Healdan's rules is answered 409 refused, and the once-per-matter rule's 409
				// already-filed, by the ProblemMapper
				throw e;
			}
			log.debug("Restored Trash item {} into folder {}", id, placeId);
			return new RestoredItem(FolderResource.view(placeId, user),
					TrashViews.item(TrashStore.get().item(id).orElseThrow(), user));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't restore Trash item #" + id, e);
		}
	}

	/**
	 * Moves an item to Removed, where only an administrator can restore it,
	 * for the days the administrators set. Checks: an item the caller may not
	 * remove, which is one not in the Trash, or someone else's unless the
	 * caller is an administrator ({@code 404}); an item none of whose members
	 * is in the Trash any more, though its record says so ({@code 409
	 * refused}).
	 *
	 * @param id the item's id
	 * @return {@code 204}
	 */
	@DELETE
	@Path("{id: [0-9]+}")
	public Response remove(@PathParam("id") long id) {
		User user = Caller.current().user();
		try {
			item(id, user, AccessChecks.isAdmin(user), IN_THE_TRASH);
			if (TrashRemoval.get().removeItem(id, user) == 0)
				throw ApiProblem.conflict("refused", "This item is no longer in the Trash.");
			log.debug("Removed Trash item {}", id);
			return Response.noContent().build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't remove Trash item #" + id, e);
		}
	}

	/**
	 * Moves every item of the caller's Trash to Removed: the items the caller
	 * deleted, an administrator's too.
	 *
	 * @return {@code 204}
	 */
	@DELETE
	public Response empty() {
		User user = Caller.current().user();
		try {
			int emptied = TrashRemoval.get().empty(user);
			log.debug("Emptied {} items from the Trash of user {}", emptied, user.getId());
			return Response.noContent().build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't empty the Trash", e);
		}
	}

	/**
	 * @param states the states the caller may act on the item in
	 * @return the item, if it is in the caller's tenant, the caller deleted it
	 *         or is an administrator, and it is in one of the states
	 * @throws ApiProblem {@code 404} otherwise, as for an unknown id
	 */
	private static TrashItemRow item(long id, User user, boolean admin, Set<TrashState> states) {
		TrashStore store = TrashStore.get();
		Optional<TrashItemRow> row = store.item(id);
		if (row.isEmpty() || row.get().tenantId() != user.getTenantId()
				|| !admin && !Long.valueOf(user.getId()).equals(row.get().deletedById())
				|| !states.contains(store.state(id)))
			throw ApiProblem.notFound("No Trash item #" + id);
		return row.get();
	}

	/**
	 * Back where it was: the folder it was deleted from, while it is live,
	 * readable and in a Store, and it and the rules let the item back in
	 *
	 * @return the folder
	 * @throws ApiProblem {@code 409 place-gone} otherwise
	 */
	private static long placeOf(TrashItemRow row, boolean folder, User user, boolean admin)
			throws PersistenceException {
		Optional<Long> place = TrashRestore.get().placeOf(row.id(), user);
		if (place.isEmpty()) {
			log.debug("Trash item {} has no folder to go back to", row.id());
			throw placeGone();
		}
		long placeId = place.get();
		try {
			refusePlace(row, folder, FolderResource.view(placeId, user), user, admin);
			// The rules' own checks of the place, which the restore asks again: what the item itself needs was
			// checked above
			FilingRules rules = FilingRules.get();
			if (folder) {
				rules.checkFolderRestore(row.objectId(), placeId, user);
			} else {
				rules.checkDocumentRestore(row.objectId(), placeId, user);
				rules.checkDocumentTarget(FolderDAO.get().findById(placeId));
			}
		} catch (ApiProblem | HealdanRuleException e) {
			log.debug("Trash item {} can't go back to folder {}", row.id(), placeId);
			throw placeGone();
		}
		return placeId;
	}

	/**
	 * Into the folder asked for, refused as a move there would be
	 *
	 * @return the folder
	 */
	private static long folderAskedFor(TrashItemRow row, boolean folder, long folderId, User user, boolean admin)
			throws PersistenceException {
		refusePlace(row, folder, FolderResource.view(folderId, user), user, admin);
		return folderId;
	}

	/**
	 * Refuses a place for the item, as the moves refuse a target: one that
	 * can't hold it ({@code 409 filing-rule}); one where the caller may not
	 * add a folder or file a document, unless it is where the item was
	 * ({@code 403 forbidden}), as the rules ask; a folder into a matter's top
	 * level, unless the caller is an administrator ({@code 403 admins-only})
	 */
	private static void refusePlace(TrashItemRow row, boolean folder, FolderView into, User user, boolean admin)
			throws PersistenceException {
		StoredRows rows = StoredRows.get();
		FolderDAO folders = FolderDAO.get();
		if (!folder) {
			FormResource.fileable(into.id(), user);
			if (!Long.valueOf(into.id()).equals(rows.documentFolder(row.objectId()))
					&& !folders.isPermissionAllowed(Permission.WRITE, into.id(), user.getId()))
				throw ApiProblem.forbidden("You may not file documents into folder %d.".formatted(into.id()));
			return;
		}

		FolderRow self = rows.folder(row.objectId());
		// A matter's own folder goes back into a Store, by an administrator, as the rules allow
		boolean mattersOwn = self != null
				&& MatterLocator.get().locate(self.parentId()).kind() == FolderPosition.Kind.STORE;
		if ("store".equals(into.kind()) && !mattersOwn)
			throw ApiProblem.conflict("filing-rule", "A Store holds matters, not folders. Restore it into a matter.");
		if ("searchFolder".equals(into.kind()))
			throw ApiProblem.conflict("filing-rule", "A search folder can't hold folders.");
		if (into.id() == row.objectId() || folders.isInPath(row.objectId(), into.id()))
			throw ApiProblem.conflict("filing-rule", "A folder can't be restored into itself or a folder inside it.");
		if ((self == null || self.parentId() != into.id())
				&& !folders.isPermissionAllowed(Permission.ADD, into.id(), user.getId()))
			throw ApiProblem.forbidden("You may not add folders to folder %d.".formatted(into.id()));
		if ("matter".equals(into.kind()) && !admin)
			throw new ApiProblem(403, "admins-only",
					"Only administrators can restore folders into a matter's top level.");
	}

	/**
	 * @return true if the folder item's folder is a Store: a workspace, in
	 *         CE's words
	 */
	private static boolean isStore(TrashItemRow row, StoredRows rows) {
		FolderRow self = rows.folder(row.objectId());
		return self != null && self.type() == Folder.TYPE_WORKSPACE;
	}

	private static ApiProblem placeGone() {
		return ApiProblem.conflict("place-gone",
				"The folder it was deleted from is gone, or you can't restore into it. Choose another.");
	}

	/**
	 * @param body a restore's body, possibly empty
	 * @param type its content type, or null
	 * @return the request it makes; an empty body asks for back where it was
	 * @throws ApiProblem {@code 415} for a body that isn't JSON, {@code 400}
	 *         for JSON that isn't the object expected
	 */
	private static RestoreRequest request(String body, MediaType type) {
		if (StringUtils.isBlank(body))
			return new RestoreRequest(null);
		if (type == null || !type.isCompatible(MediaType.APPLICATION_JSON_TYPE))
			throw new ApiProblem(415, "bad-request", "Send where to restore it as JSON.");
		RestoreRequest request;
		try {
			request = ApiJson.mapper().readValue(body, RestoreRequest.class);
		} catch (JsonProcessingException e) {
			throw ApiProblem.badRequest(ProblemMapper.describe(e));
		}
		if (request == null)
			throw ApiProblem.badRequest("Where to restore it must be a JSON object.");
		return request;
	}
}
