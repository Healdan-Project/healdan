package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.MatterView;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.navigation.MatterEntry;
import com.logicaldoc.healdan.navigation.MyMatters;
import com.logicaldoc.healdan.navigation.RecentMatters;
import com.logicaldoc.healdan.security.AccessChecks;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Matters (spec sections 4.2 and 6.2): one by id, and the caller's My Matters
 * and Recent Matters lists. A matter whose workspace the caller can't read is
 * {@code 404}.
 */
@Component
@Path("/v1/matters")
@Produces(MediaType.APPLICATION_JSON)
public class MatterResource {

	/**
	 * @param id the matter's id
	 * @return the matter
	 */
	@GET
	@Path("{id: [0-9]+}")
	public MatterView get(@PathParam("id") long id) {
		User caller = Caller.current().user();
		try {
			Matter matter = MatterDAO.get().findById(id);
			if (matter == null || AccessChecks.readableFolder(matter.getFolderId(), caller).isEmpty())
				throw ApiProblem.notFound("No matter #" + id);
			return ApiViews.matter(matter, null);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read matter #" + id, e);
		}
	}

	/**
	 * @return the matters the caller added to My Matters, in display order
	 */
	@GET
	@Path("mine")
	public Page<MatterView> mine() {
		User caller = Caller.current().user();
		try {
			return Page.whole(views(MyMatters.get().list(caller.getId())));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list My Matters", e);
		}
	}

	/**
	 * @return the matters the caller changed documents in lately, newest first
	 */
	@GET
	@Path("recent")
	public Page<MatterView> recent() {
		User caller = Caller.current().user();
		try {
			return Page.whole(views(RecentMatters.get().list(caller.getId())));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list Recent Matters", e);
		}
	}

	private static List<MatterView> views(List<MatterEntry> entries) throws PersistenceException {
		List<MatterView> views = new ArrayList<>();
		for (MatterEntry entry : entries)
			views.add(ApiViews.matter(MatterDAO.get().findById(entry.matterId()), entry.lastChange()));
		return views;
	}
}
