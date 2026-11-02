package com.logicaldoc.healdan.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.web.PublicAddress;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;

/**
 * The Microsoft Office add-in's pages and manifest (M3.5 spec section 7). The
 * pages are in this plugin's jar, where the POM copies them from
 * {@code clients/office/}, and they're public, like the docs page: Office
 * loads them with no sign-in. They aren't JSON API, so {@code openapi.yaml}
 * leaves them out.
 */
@Component
@Path("/addin")
public class AddinResource {

	/** Where the pages are: the POM copies clients/office/ there */
	static final String ADDIN = "healdan/api/addin/";

	private static final String TEMPLATE = ADDIN + "manifest.template.xml";

	private static final String UTF8 = "; charset=UTF-8";

	/**
	 * The scripts are the server's own and Office.js, which Office requires to
	 * come from its network; the connections go to the Healdan Helper's ports
	 * on this Mac (spec section 5.8) and to Microsoft's own services.
	 */
	private static final String POLICY = "default-src 'self'; script-src 'self' https://appsforoffice.microsoft.com; "
			+ "style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
			+ "connect-src 'self' https://127.0.0.1:47615 https://127.0.0.1:47616 https://127.0.0.1:47617 "
			+ "https://127.0.0.1:47618 https://*.microsoft.com https://*.office.com https://*.office.net";

	/**
	 * The manifest, with the server's address in place of the template's
	 * placeholders. Each firm uploads its own.
	 */
	@GET
	@Public
	@Path("manifest.xml")
	public Response manifest(@Context HttpServletRequest request) {
		PublicAddress.Address address = address(request);
		String manifest = new String(read(TEMPLATE), StandardCharsets.UTF_8)
				.replace("{{BASE}}", address.base() + "/api/addin").replace("{{ORIGIN}}", address.origin());
		return ok(manifest.getBytes(StandardCharsets.UTF_8), "application/xml" + UTF8, false);
	}

	/**
	 * A page or script of the add-in. The pattern keeps out everything else,
	 * the manifest's template and dot files among it.
	 */
	@GET
	@Public
	@Path("{file: [A-Za-z0-9-]+\\.(html|js|css)}")
	public Response file(@PathParam("file") String file) {
		String extension = file.substring(file.lastIndexOf('.') + 1);
		return ok(content(file), switch (extension) {
		case "js" -> "text/javascript" + UTF8;
		case "css" -> "text/css" + UTF8;
		default -> "text/html" + UTF8;
		}, "html".equals(extension));
	}

	/**
	 * An image of the add-in: the ribbon's icons.
	 */
	@GET
	@Public
	@Path("assets/{file: [A-Za-z0-9-]+\\.png}")
	public Response asset(@PathParam("file") String file) {
		return ok(content("assets/" + file), "image/png", false);
	}

	private static byte[] content(String file) {
		byte[] content = readOrNull(ADDIN + file);
		if (content == null)
			throw ApiProblem.notFound("There's nothing at this address.");
		return content;
	}

	private static Response ok(byte[] content, String type, boolean page) {
		Response.ResponseBuilder response = Response.ok(content, type).header("X-Content-Type-Options", "nosniff");
		if (page)
			response.header("Content-Security-Policy", POLICY);
		return response.build();
	}

	/**
	 * Works out the address Office will load the pages from
	 * ({@link PublicAddress}). Office loads pages over HTTPS only, so anything
	 * else but a machine's own address is refused.
	 */
	private static PublicAddress.Address address(HttpServletRequest request) {
		return PublicAddress.of(request).orElseThrow(() -> ApiProblem.conflict("no-public-address",
				"Office loads add-ins over HTTPS, and Healdan doesn't know an HTTPS address for itself. Set "
						+ "LOGICALDOC_PUBLIC_URL to the server's https:// address, such as https://healdan.example/, "
						+ "and restart Healdan."));
	}

	private static byte[] read(String resource) {
		byte[] content = readOrNull(resource);
		if (content == null)
			throw new IllegalStateException("Missing from the classpath: " + resource);
		return content;
	}

	private static byte[] readOrNull(String resource) {
		try (InputStream in = AddinResource.class.getClassLoader().getResourceAsStream(resource)) {
			return in != null ? in.readAllBytes() : null;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
