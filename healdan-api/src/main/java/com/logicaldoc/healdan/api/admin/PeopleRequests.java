// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.model.FieldError;

/**
 * What the people routes, {@link AdminUsersResource}'s and {@link AdminGroupsResource}'s, share: a call to the people
 * services with their refusals answered as the contract has them, and the checks of a body's members before the
 * path's.
 * <p>
 * The services check a form's fields themselves, and refuse them with a {@link FieldRefusal}, answered
 * {@code 422 validation} with its fields; their rules' refusals, each a {@code HealdanRuleException} with its
 * sentence, go to the {@code ProblemMapper}, which answers them {@code 409 refused}. Each change runs a transaction of
 * its own, so the routes call them with none open.
 */
final class PeopleRequests {

	private PeopleRequests() {
	}

	/** A people service's call, which may throw */
	@FunctionalInterface
	interface Call<T> {
		T run() throws PersistenceException;
	}

	/**
	 * @param failure what couldn't be done, for the log, when the call fails at the data layer
	 * @param call the service's call
	 * @return what it returned
	 * @throws ApiProblem {@code 422 validation} for the fields it refused; {@code 400 bad-request} for what it found
	 *         malformed, which the routes check first
	 */
	static <T> T run(String failure, Call<T> call) {
		try {
			return call.run();
		} catch (FieldRefusal e) {
			throw validation(e);
		} catch (IllegalArgumentException e) {
			throw ApiProblem.badRequest("The request is malformed.");
		} catch (PersistenceException e) {
			// A rule's refusal is a veto, which the ProblemMapper answers 409 refused, wrapped or not
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * @return the fields a service refused, as {@code 422 validation}'s {@code fields}, in the form's order
	 */
	static ApiProblem validation(FieldRefusal refusal) {
		return ApiProblem.validation(refusal.fields().stream()
				.map(field -> new FieldError(field.name(), field.code(), field.message())).toList());
	}

	/**
	 * @param groupIds the groups sent, null for none
	 * @throws ApiProblem {@code 400 bad-request} for a null among them, or a group sent twice: the contract's
	 *         {@code uniqueItems}
	 */
	static void checkGroupIds(List<Long> groupIds) {
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

	/**
	 * @param node a member of a body that's text when it's sent
	 * @param detail what to say when it isn't text
	 * @throws ApiProblem {@code 400 bad-request} when it's sent as something other than text or null
	 */
	static void checkText(JsonNode node, String detail) {
		if (node != null && !node.isNull() && !node.isTextual())
			throw ApiProblem.badRequest(detail);
	}

	/**
	 * @param node a member of a body, checked with {@link #checkText}
	 * @param current what it is now, for a member left out
	 * @return the text sent, null when it was sent as null, or the current value when it was left out
	 */
	static String merged(JsonNode node, String current) {
		if (node == null)
			return current;
		return node.isNull() ? null : node.textValue();
	}
}
