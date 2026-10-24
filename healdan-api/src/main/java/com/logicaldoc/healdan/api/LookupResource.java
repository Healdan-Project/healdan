package com.logicaldoc.healdan.api;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.LookupView;
import com.logicaldoc.healdan.docnumber.DocNumberLookup;
import com.logicaldoc.healdan.docnumber.DocNumberQuery;
import com.logicaldoc.healdan.docnumber.LookupResult;
import com.logicaldoc.healdan.matter.MatterLocator;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Resolving a typed doc # (spec section 6.3), with the old web page's rules
 * and messages.
 */
@Component
@Path("/v1/lookup")
@Produces(MediaType.APPLICATION_JSON)
public class LookupResource {

	/**
	 * @param q a doc # such as {@code #1234.2}
	 * @return the document's number and the version named
	 */
	@GET
	public LookupView lookup(@QueryParam("q") String q) {
		String text = q == null ? null : q.strip();
		if (!DocNumberQuery.looksLikeNumber(text))
			throw ApiProblem.badRequest(DocNumberLookup.notANumber(text));
		User user = Caller.current().user();
		try {
			LookupResult found = DocNumberLookup.lookup(text, user.getId());
			if (!found.found())
				throw ApiProblem.notFound(found.message());
			// Like GET /documents/{docNumber}: a document outside the Stores isn't shown
			if (!MatterLocator.get().locate(found.folderId()).isUnderAStore())
				throw ApiProblem.notFound("No document #" + found.docNumber());
			return new LookupView(found.docNumber(), found.version());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't look up " + q, e);
		}
	}
}
