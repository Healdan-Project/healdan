// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.List;

import com.logicaldoc.healdan.util.StorableText;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;

/**
 * Refuses a request with a query parameter that holds a character Healdan
 * can't keep, a NUL or a lone surrogate ({@link StorableText}), as
 * {@code 400 bad-request} (PostgreSQL spec section 7.5): PostgreSQL fails a
 * statement given a NUL, which would answer {@code 500}. Before matching, so
 * before the token check, and on public routes too. A JSON body's text is
 * checked as it's read ({@link ApiJson}).
 */
@PreMatching
public class TextFilter implements ContainerRequestFilter {

	static final String REFUSED = "A parameter of the request has a character Healdan can't keep.";

	@Override
	public void filter(ContainerRequestContext request) {
		for (List<String> values : request.getUriInfo().getQueryParameters().values())
			for (String value : values)
				if (!StorableText.holds(value)) {
					request.abortWith(ProblemMapper.problem(ApiProblem.badRequest(REFUSED)));
					return;
				}
	}
}
