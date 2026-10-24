package com.logicaldoc.healdan.api;

import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.api.model.UserView;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /v1/me}: who the token signs in as (spec section 6.1).
 */
@Component
@Path("/v1/me")
@Produces(MediaType.APPLICATION_JSON)
public class MeResource {

	@GET
	public UserView me() {
		return ApiViews.user(Caller.current().user());
	}
}
