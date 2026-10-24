package com.logicaldoc.healdan.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.logicaldoc.healdan.api.model.FieldError;

/**
 * A refusal the API answers with an RFC 9457 problem (spec section 4.4).
 * {@link ProblemMapper} writes it. The {@code detail} is an English sentence a
 * client can show as it is.
 */
public class ApiProblem extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int status;

	private final String code;

	private final transient Map<String, Object> extras = new LinkedHashMap<>();

	/**
	 * @param status the HTTP status
	 * @param code the short stable string clients switch on, such as
	 *        {@code not-found}
	 * @param detail what went wrong, for the user
	 */
	public ApiProblem(int status, String code, String detail) {
		// A refusal, not a failure: no stack trace to fill in
		super(detail, null, false, false);
		this.status = status;
		this.code = code;
	}

	/**
	 * Adds a member to the problem, such as the {@code hold} of a
	 * {@code checked-out} problem.
	 *
	 * @param name the member's name
	 * @param value its value, written as JSON
	 * @return this problem
	 */
	public ApiProblem with(String name, Object value) {
		extras.put(name, value);
		return this;
	}

	public int status() {
		return status;
	}

	public String code() {
		return code;
	}

	public String detail() {
		return getMessage();
	}

	/**
	 * @return the members {@link #with} added, in order
	 */
	public Map<String, Object> extras() {
		return Collections.unmodifiableMap(extras);
	}

	public static ApiProblem notFound(String detail) {
		return new ApiProblem(404, "not-found", detail);
	}

	public static ApiProblem forbidden(String detail) {
		return new ApiProblem(403, "forbidden", detail);
	}

	public static ApiProblem badRequest(String detail) {
		return new ApiProblem(400, "bad-request", detail);
	}

	public static ApiProblem unauthenticated(String detail) {
		return new ApiProblem(401, "unauthenticated", detail);
	}

	/**
	 * @param code the conflict's code, such as {@code checked-out}
	 * @param detail what went wrong, for the user
	 * @return a 409 problem
	 */
	public static ApiProblem conflict(String code, String detail) {
		return new ApiProblem(409, code, detail);
	}

	/**
	 * @param fields what's wrong with each field
	 * @return a 422 problem listing them as {@code fields}
	 */
	public static ApiProblem validation(List<FieldError> fields) {
		String detail = fields.size() == 1 ? fields.get(0).detail() : "Some fields need correcting.";
		return new ApiProblem(422, "validation", detail).with("fields", List.copyOf(fields));
	}
}
