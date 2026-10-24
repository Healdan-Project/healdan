package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.UserView;
import com.logicaldoc.healdan.security.AccessChecks;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /v1/me}: who the token signs in as (spec section 6.1), and
 * {@code GET /v1/me/checkouts}: the documents the caller has checked out
 * (section 6.4).
 */
@Component
@Path("/v1/me")
@Produces(MediaType.APPLICATION_JSON)
public class MeResource {

	@GET
	public UserView me() {
		return ApiViews.user(Caller.current().user());
	}

	/**
	 * @return the documents the caller has checked out and can still read,
	 *         the latest check-out first
	 */
	@GET
	@Path("checkouts")
	public Page<DocumentView> checkouts() {
		User caller = Caller.current().user();
		try {
			// The partial documents the DAO returns are only good for their ids
			List<DocumentView> views = new ArrayList<>();
			for (Document partial : DocumentDAO.get().findByLockUserAndStatus(caller.getId(),
					DocumentStatus.CHECKEDOUT)) {
				Document document = DocumentDAO.get().findById(partial.getId());
				if (AccessChecks.readableDocument(document, caller).isPresent() && ApiViews.shows(document))
					views.add(ApiViews.document(document, caller));
			}
			views.sort(Comparator.comparing(MeResource::since, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(Comparator.comparingLong(DocumentView::docNumber).reversed()));
			return Page.whole(views);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the check-outs", e);
		}
	}

	private static Date since(DocumentView view) {
		HoldView hold = view.hold();
		return hold == null ? null : hold.since();
	}
}
