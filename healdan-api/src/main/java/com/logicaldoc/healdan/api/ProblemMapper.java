// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.cxf.jaxrs.utils.JAXRSUtils;
import org.apache.cxf.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.email.AlreadyFiledException;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.util.StorageUnreachableException;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * Turns exceptions into RFC 9457 problems (spec section 4.4):
 * <ul>
 * <li>an {@link ApiProblem} as it is;</li>
 * <li>the once-per-matter rule's refusal as {@code 409 already-filed}, with
 * its message and the copy as {@code document} (M5 spec \u00a76.4);</li>
 * <li>a file that can't be read because its storage can't be reached as
 * {@code 503 storage-unreachable}, with its message, which names the Store
 * (S3 storage spec \u00a76.5);</li>
 * <li>any other Healdan veto as {@code 409 refused}, with its message;</li>
 * <li>JSON that can't be read as {@code 400 bad-request};</li>
 * <li>what JAX-RS raises with its own status: {@code 404 not-found},
 * {@code 401 unauthenticated}, {@code 403 forbidden}, {@code 413 too-large}
 * and {@code bad-request} for any other 4xx, such as 405 and 415;</li>
 * <li>anything else as {@code 500 server-error}, logged, and never with its
 * message;</li>
 * <li>but a client that went away, which Tomcat reports as a
 * {@code ClientAbortException} while its answer is written or its request
 * read, isn't a failure of the server's: logged in one line at info, with its
 * frames at debug, and answered with an empty {@code 500}, not a problem.
 * Tomcat reports a client that stalled past its timeout the same way, which
 * the line's cause tells apart.</li>
 * </ul>
 * {@link #unexpected} says whether an exception is answered as an
 * unexpected failure, so that a save that fails part-way can say what's left
 * instead.
 */
public class ProblemMapper implements ExceptionMapper<Throwable> {

	/** The media type of a problem */
	public static final String PROBLEM_JSON = "application/problem+json";

	private static final String TYPE_BASE = "https://healdan.com/problems/";

	private static final String SERVER_ERROR = "Something went wrong on the server.";

	/**
	 * What Tomcat's connector throws when the client has gone away, known by
	 * name as healdan-api doesn't compile against Tomcat
	 */
	private static final String CLIENT_ABORT = "org.apache.catalina.connector.ClientAbortException";

	private static final Logger log = LoggerFactory.getLogger(ProblemMapper.class);

	/** How {@link #toResponse} answers an exception, in the order it checks: {@link #kind} decides */
	private enum Kind {
		CLIENT_GONE, PROBLEM, ALREADY_FILED, STORAGE_UNREACHABLE, VETO, BAD_JSON, WEB_APPLICATION, UNEXPECTED
	}

	@Override
	public Response toResponse(Throwable exception) {
		return switch (kind(exception)) {
		case CLIENT_GONE -> {
			// One line: the request may have done its work, such as a save, before its answer was lost
			List<Throwable> causes = ExceptionUtils.getThrowableList(exception);
			log.info("API client went away or stalled during {}: {}", request(), causes.getLast().toString());
			log.debug("API client went away before its answer was sent", exception);
			yield Response.serverError().build();
		}
		case PROBLEM -> problem((ApiProblem) exception);
		case ALREADY_FILED -> problem(alreadyFiled(find(exception, AlreadyFiledException.class)));
		case STORAGE_UNREACHABLE -> problem(new ApiProblem(503, "storage-unreachable",
				StorageUnreachableException.in(exception).getMessage()));
		case VETO -> problem(new ApiProblem(409, "refused", find(exception, HealdanVetoException.class).getMessage()));
		case BAD_JSON -> problem(ApiProblem.badRequest(describe((JsonProcessingException) exception)));
		case WEB_APPLICATION -> webApplicationProblem((WebApplicationException) exception);
		case UNEXPECTED -> {
			log.error("API request failed", exception);
			yield problem(new ApiProblem(500, "server-error", SERVER_ERROR));
		}
		};
	}

	/**
	 * @return how {@link #toResponse} answers the exception, with no side
	 *         effect: the client went away; it's an {@link ApiProblem}; it or
	 *         one of its causes is an {@link AlreadyFiledException}, a
	 *         {@link StorageUnreachableException} or another
	 *         {@link HealdanVetoException}; it's a
	 *         {@link JsonProcessingException} or a
	 *         {@link WebApplicationException}; otherwise an unexpected failure
	 */
	private static Kind kind(Throwable exception) {
		if (clientGone(exception))
			return Kind.CLIENT_GONE;
		if (exception instanceof ApiProblem)
			return Kind.PROBLEM;
		if (find(exception, AlreadyFiledException.class) != null)
			return Kind.ALREADY_FILED;
		if (StorageUnreachableException.in(exception) != null)
			return Kind.STORAGE_UNREACHABLE;
		if (find(exception, HealdanVetoException.class) != null)
			return Kind.VETO;
		if (exception instanceof JsonProcessingException)
			return Kind.BAD_JSON;
		if (exception instanceof WebApplicationException)
			return Kind.WEB_APPLICATION;
		return Kind.UNEXPECTED;
	}

	/**
	 * @return whether {@link #toResponse} answers this as an unexpected
	 *         failure, {@code 500 server-error} ({@link #kind})
	 */
	static boolean unexpected(Throwable exception) {
		return kind(exception) == Kind.UNEXPECTED;
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
	 * @return whether the exception is, or was caused by, Tomcat's report that
	 *         the client went away
	 */
	static boolean clientGone(Throwable exception) {
		// Safe from a loop of causes
		return ExceptionUtils.getThrowableList(exception).stream()
				.anyMatch(t -> CLIENT_ABORT.equals(t.getClass().getName()));
	}

	/**
	 * @return the method and path of the request being answered, for the log
	 */
	private static String request() {
		Message message = JAXRSUtils.getCurrentMessage();
		Message in = message != null && message.getExchange() != null ? message.getExchange().getInMessage() : null;
		return in == null ? "a request" : in.get(Message.HTTP_REQUEST_METHOD) + " " + in.get(Message.REQUEST_URI);
	}

	/**
	 * @return the exception of that type, if the exception is one or was
	 *         caused by one
	 */
	static <T extends Throwable> T find(Throwable exception, Class<T> type) {
		// Bounded, in case of a loop of causes
		Throwable t = exception;
		for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause())
			if (type.isInstance(t))
				return type.cast(t);
		return null;
	}

	/**
	 * @return the refusal, with the copy as the caller sees it; the rule names
	 *         only a copy the caller can read, and one the caller can't (a rule
	 *         run with no user) is left out
	 */
	private static ApiProblem alreadyFiled(AlreadyFiledException filed) {
		ApiProblem problem = new ApiProblem(409, "already-filed", filed.getMessage());
		try {
			User caller = Caller.current().user();
			Optional<Document> copy = AccessChecks.readableDocument(DocumentDAO.get().findById(filed.copyId()),
					caller);
			if (copy.isPresent())
				problem.with("document", ApiViews.document(copy.get(), caller));
		} catch (PersistenceException | RuntimeException e) {
			log.warn("Can't add document {} to an already-filed problem: {}", filed.copyId(), e.getClass().getName());
			log.debug("Can't add document {} to an already-filed problem", filed.copyId(), e);
		}
		return problem;
	}

	/**
	 * @return what's wrong with JSON that can't be read, for the user
	 */
	static String describe(JsonProcessingException e) {
		if (e instanceof UnrecognizedPropertyException unknown)
			return "The request has a field the server doesn't know: %s.".formatted(unknown.getPropertyName());
		boolean unkept = e instanceof ApiJson.UnkeptText;
		if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
			String field = mapping.getPath().get(mapping.getPath().size() - 1).getFieldName();
			if (field != null)
				return (unkept ? "The field %s has a character Healdan can't keep."
						: "The field %s has the wrong type or format.").formatted(field);
		}
		return unkept ? "The request has a character Healdan can't keep." : "The request body isn't the JSON expected.";
	}

	/**
	 * @return the problem as a response
	 */
	public static Response problem(ApiProblem problem) {
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
