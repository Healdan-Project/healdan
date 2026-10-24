package com.logicaldoc.healdan.api;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.LookupView;
import com.logicaldoc.healdan.docnumber.DocNumberLookup;
import com.logicaldoc.healdan.docnumber.DocNumberQuery;
import com.logicaldoc.healdan.docnumber.LookupResult;

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
		User user = Caller.current().user();
		try {
			LookupResult found = DocNumberLookup.lookup(q == null ? null : q.strip(), user.getId());
			if (!DocNumberQuery.looksLikeNumber(q == null ? null : q.strip()))
				throw ApiProblem.badRequest(found.message());
			if (!found.found())
				throw ApiProblem.notFound(found.message());
			return new LookupView(found.docNumber(), found.version());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't look up " + q, e);
		}
	}
}
