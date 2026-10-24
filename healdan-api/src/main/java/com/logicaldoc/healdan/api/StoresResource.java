package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.MatterView;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.StoreRef;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.security.AccessChecks;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The Stores and their matters (spec section 6.2). Administrators only: a
 * user finds matters by search and the left-pane lists, and learns nothing of
 * the Stores they can't read.
 */
@Component
@Path("/v1/stores")
@Produces(MediaType.APPLICATION_JSON)
public class StoresResource {

	/**
	 * @return the Stores, by name; not Default or Templates
	 */
	@GET
	public Page<StoreRef> list(@QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		try {
			requireAdmin();
			List<StoreRef> all = new ArrayList<>();
			for (Folder store : MatterService.get().stores())
				all.add(new StoreRef(store.getId(), store.getName()));
			all.sort(Comparator.comparing(StoreRef::name, String.CASE_INSENSITIVE_ORDER)
					.thenComparing(StoreRef::name).thenComparingLong(StoreRef::id));
			return paging.slice(all);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the Stores", e);
		}
	}

	/**
	 * @param id the Store's id
	 * @return the Store's matters, by client number then matter number
	 */
	@GET
	@Path("{id: [0-9]+}/matters")
	public Page<MatterView> matters(@PathParam("id") long id, @QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		try {
			requireAdmin();
			FolderPosition position = MatterLocator.get().locate(id);
			if (position.kind() != FolderPosition.Kind.STORE)
				throw ApiProblem.notFound("No Store #" + id);

			MatterDAO matters = MatterDAO.get();
			List<MatterView> views = new ArrayList<>();
			for (Matter matter : matters.findByStore(id, paging.offset(), paging.limit()))
				views.add(ApiViews.matter(matter, null));
			return new Page<>(views, paging.offset(), paging.limit(), matters.countByStore(id));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the matters of Store #" + id, e);
		}
	}

	private static void requireAdmin() throws PersistenceException {
		User caller = Caller.current().user();
		if (!AccessChecks.isAdmin(caller))
			throw ApiProblem.forbidden("Only administrators may list the Stores.");
	}
}
