package com.logicaldoc.healdan.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

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

	/** The key of the public address, which LOGICALDOC_PUBLIC_URL sets */
	private static final String SERVER_URL = "server.url";

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
	 * An address as the manifest can write it into XML: the origin, which Office
	 * wants as the app domain, and a path if Healdan isn't at the root.
	 */
	private static final Pattern ADDRESS = Pattern.compile(
			"(?<scheme>https?)://(?<host>[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(?<port>:[0-9]{1,5})?(?<path>(?:/[A-Za-z0-9._~-]+)*)/*",
			Pattern.CASE_INSENSITIVE);

	/**
	 * The manifest, with the server's address in place of the template's
	 * placeholders. Each firm uploads its own.
	 */
	@GET
	@Public
	@Path("manifest.xml")
	public Response manifest(@Context HttpServletRequest request) {
		Address address = address(request);
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
	 * Works out the address Office will load the pages from: the public
	 * address when the install has one, otherwise the one this request was
	 * made to, which Tomcat's {@code RemoteIpValve} has already corrected from
	 * {@code X-Forwarded-Proto}. Office loads pages over HTTPS only, so
	 * anything else but a machine's own address is refused.
	 */
	private static Address address(HttpServletRequest request) {
		String configured = com.logicaldoc.util.spring.Context.get().getConfig().getProperty(SERVER_URL);
		String candidate = configured != null && !configured.isBlank() ? configured.trim() : requested(request);

		Matcher matcher = ADDRESS.matcher(candidate);
		if (matcher.matches()) {
			String scheme = matcher.group("scheme").toLowerCase(Locale.ROOT);
			String host = matcher.group("host").toLowerCase(Locale.ROOT);
			if ("https".equals(scheme) || isThisMachine(host)) {
				String origin = scheme + "://" + host + (matcher.group("port") != null ? matcher.group("port") : "");
				return new Address(origin, origin + matcher.group("path"));
			}
		}
		throw ApiProblem.conflict("no-public-address",
				"Office loads add-ins over HTTPS, and Healdan doesn't know an HTTPS address for itself. Set "
						+ "LOGICALDOC_PUBLIC_URL to the server's https:// address, such as https://healdan.example/, "
						+ "and restart Healdan.");
	}

	private static String requested(HttpServletRequest request) {
		String scheme = request.getScheme();
		String host = request.getServerName();
		// A bare IPv6 address needs its brackets in a URL
		if (host != null && host.indexOf(':') >= 0 && !host.startsWith("["))
			host = "[" + host + "]";
		int port = request.getServerPort();
		boolean usual = port <= 0 || ("http".equalsIgnoreCase(scheme) && port == 80)
				|| ("https".equalsIgnoreCase(scheme) && port == 443);
		return scheme + "://" + host + (usual ? "" : ":" + port) + request.getContextPath();
	}

	private static boolean isThisMachine(String host) {
		return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
	}

	/**
	 * @param origin scheme, host and port, which the manifest's app domain is
	 * @param base the origin and the install's path, if it has one
	 */
	private record Address(String origin, String base) {
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
