package com.logicaldoc.healdan.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.logicaldoc.core.HealdanVetoException;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * Turns every exception into an RFC 9457 problem (spec section 4.4):
 * <ul>
 * <li>an {@link ApiProblem} as it is;</li>
 * <li>a Healdan veto as {@code 409 refused}, with its message;</li>
 * <li>JSON that can't be read as {@code 400 bad-request};</li>
 * <li>what JAX-RS raises with its own status: {@code 404 not-found},
 * {@code 401 unauthenticated}, {@code 403 forbidden}, {@code 413 too-large}
 * and {@code bad-request} for any other 4xx, such as 405 and 415;</li>
 * <li>anything else as {@code 500 server-error}, logged, and never with its
 * message.</li>
 * </ul>
 */
public class ProblemMapper implements ExceptionMapper<Throwable> {

	/** The media type of a problem */
	public static final String PROBLEM_JSON = "application/problem+json";

	private static final String TYPE_BASE = "https://healdan.com/problems/";

	private static final String SERVER_ERROR = "Something went wrong on the server.";

	private static final Logger log = LoggerFactory.getLogger(ProblemMapper.class);

	@Override
	public Response toResponse(Throwable exception) {
		if (exception instanceof ApiProblem problem)
			return problem(problem);

		HealdanVetoException veto = findVeto(exception);
		if (veto != null)
			return problem(new ApiProblem(409, "refused", veto.getMessage()));

		if (exception instanceof JsonProcessingException json)
			return problem(ApiProblem.badRequest(describe(json)));

		if (exception instanceof WebApplicationException wae)
			return webApplicationProblem(wae);

		log.error("API request failed", exception);
		return problem(new ApiProblem(500, "server-error", SERVER_ERROR));
	}

	private static Response webApplicationProblem(WebApplicationException wae) {
		Response original = wae.getResponse();
		int status = original.getStatus();
		if (status < 400)
			return original;

		ApiProblem problem = switch (status) {
		// JAX-RS answers 404 when a query or path parameter can't be converted
		case 404 -> wae.getCause() != null ? ApiProblem.badRequest("A parameter of the request has the wrong type.")
				: ApiProblem.notFound("There's nothing at this address.");
		case 401 -> ApiProblem.unauthenticated("Sign in first.");
		case 403 -> ApiProblem.forbidden("You can't do this.");
		// CXF stopped reading an upload part over its limit (UploadFilter)
		case 413 -> Uploads.tooLarge();
		case 405 -> new ApiProblem(405, "bad-request", "This address doesn't take this request method.");
		case 406 -> new ApiProblem(406, "bad-request", "The server can't answer in a format the request accepts.");
		case 415 -> new ApiProblem(415, "bad-request", "The server doesn't take this request's content type.");
		default -> {
			if (status < 500)
				yield new ApiProblem(status, "bad-request", "The request is malformed.");
			log.error("API request failed", wae);
			yield new ApiProblem(status, "server-error", SERVER_ERROR);
		}
		};

		Response.ResponseBuilder builder = Response.fromResponse(problem(problem));
		// Such as the Allow of a 405
		for (Map.Entry<String, List<Object>> header : original.getHeaders().entrySet())
			if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(header.getKey())
					&& !HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(header.getKey()))
				header.getValue().forEach(value -> builder.header(header.getKey(), value));
		return builder.build();
	}

	/**
	 * @return the veto, if the exception is one or was caused by one
	 */
	private static HealdanVetoException findVeto(Throwable exception) {
		// Bounded, in case of a loop of causes
		Throwable t = exception;
		for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause())
			if (t instanceof HealdanVetoException veto)
				return veto;
		return null;
	}

	/**
	 * @return what's wrong with JSON that can't be read, for the user
	 */
	static String describe(JsonProcessingException e) {
		if (e instanceof UnrecognizedPropertyException unknown)
			return "The request has a field the server doesn't know: %s.".formatted(unknown.getPropertyName());
		if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
			String field = mapping.getPath().get(mapping.getPath().size() - 1).getFieldName();
			if (field != null)
				return "The field %s has the wrong type or format.".formatted(field);
		}
		return "The request body isn't the JSON expected.";
	}

	/**
	 * @return the problem as a response
	 */
	static Response problem(ApiProblem problem) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", TYPE_BASE + problem.code());
		body.put("title", title(problem.code()));
		body.put("status", problem.status());
		body.put("detail", problem.detail());
		body.put("code", problem.code());
		problem.extras().forEach(body::putIfAbsent);
		return Response.status(problem.status()).type(PROBLEM_JSON).entity(body).build();
	}

	/**
	 * @return the code as words, such as "Checked out" for {@code checked-out}
	 */
	private static String title(String code) {
		String words = code.replace('-', ' ');
		return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}
}
