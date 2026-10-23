package com.logicaldoc.healdan.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * The description file and the docs page (spec section 4.6). Swagger UI is
 * served from its webjar on the classpath, so the page loads nothing from the
 * internet and stays behind {@code /api/**}'s Spring Security exemption (spec
 * correction 5).
 */
@Component
@Path("/")
public class DocsResource {

	/**
	 * Where Swagger UI's files are in its webjar. The version is the POM's
	 * {@code swagger-ui.version}: DocsResourceTest fails when they differ.
	 */
	static final String SWAGGER_UI = "META-INF/resources/webjars/swagger-ui/5.32.15/";

	private static final String OPENAPI = "healdan/api/openapi.yaml";

	private static final String INDEX = "healdan/api/docs/index.html";

	private static final String UTF8 = "; charset=UTF-8";

	@GET
	@Public
	@Path("v1/openapi.yaml")
	@Produces("application/yaml")
	public byte[] openApi() {
		return read(OPENAPI);
	}

	/**
	 * {@code /docs} sends the browser to {@code /docs/}, so that the page's
	 * relative links work; {@code /docs/} is the page. JAX-RS matches both
	 * with one template.
	 */
	@GET
	@Public
	@Path("docs")
	public Response docs(@Context HttpServletRequest request) {
		if (!request.getRequestURI().endsWith("/"))
			return Response.status(Response.Status.SEE_OTHER)
					.header(HttpHeaders.LOCATION, request.getContextPath() + "/api/docs/").build();
		return index();
	}

	/**
	 * A file of Swagger UI. The webjar's own {@code index.html} would load its
	 * demo description from the internet, so it's Healdan's page instead.
	 */
	@GET
	@Public
	@Path("docs/{file: [A-Za-z0-9.-]+}")
	public Response file(@PathParam("file") String file) {
		if ("index.html".equals(file))
			return index();
		// Nothing hidden and no way up: "." and ".." match the template too
		byte[] content = file.startsWith(".") ? null : readOrNull(SWAGGER_UI + file);
		if (content == null)
			throw ApiProblem.notFound("There's nothing at this address.");
		return Response.ok(content, contentType(file)).build();
	}

	private static Response index() {
		return Response.ok(read(INDEX), "text/html" + UTF8).build();
	}

	private static String contentType(String file) {
		String extension = file.substring(file.lastIndexOf('.') + 1);
		return switch (extension) {
		case "js" -> "text/javascript" + UTF8;
		case "css" -> "text/css" + UTF8;
		case "html" -> "text/html" + UTF8;
		case "png" -> "image/png";
		case "txt" -> "text/plain" + UTF8;
		case "map", "json" -> "application/json";
		default -> "application/octet-stream";
		};
	}

	private static byte[] read(String resource) {
		byte[] content = readOrNull(resource);
		if (content == null)
			throw new IllegalStateException("Missing from the classpath: " + resource);
		return content;
	}

	private static byte[] readOrNull(String resource) {
		try (InputStream in = DocsResource.class.getClassLoader().getResourceAsStream(resource)) {
			return in != null ? in.readAllBytes() : null;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
