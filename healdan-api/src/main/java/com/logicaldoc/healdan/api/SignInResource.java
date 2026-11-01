package com.logicaldoc.healdan.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.spring.LDDeferredSecurityContext;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.signin.IdClaims;
import com.logicaldoc.healdan.signin.PendingSignIns;
import com.logicaldoc.healdan.signin.PendingSignIns.Pending;
import com.logicaldoc.healdan.signin.ProviderClient;
import com.logicaldoc.healdan.signin.ProviderException;
import com.logicaldoc.healdan.signin.SignInAccounts;
import com.logicaldoc.healdan.signin.SignInAccounts.Allowed;
import com.logicaldoc.healdan.signin.SignInAccounts.Decision;
import com.logicaldoc.healdan.signin.SignInAccounts.Refused;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.SignIns;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.healdan.web.PublicAddress;
import com.logicaldoc.healdan.web.PublicAddress.Address;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * The web page's sign-in through a provider (M4 spec section 5.2), at
 * {@code /api/signin/}. These are pages a browser is sent through, not JSON
 * API: they take no token, answer with redirects and HTML, and
 * {@code openapi.yaml} leaves them out (spec section 11).
 * <ul>
 * <li>{@code GET /signin/<id>} starts a sign-in and sends the browser to the
 * provider, bound to this browser by the {@code hd_signin} cookie.</li>
 * <li>{@code GET /signin/<id>/callback} is where the provider sends it back.
 * The sign-in must be known, unexpired and unused, started with this provider
 * (no mix-up) and by this browser (no login CSRF). It ends with the same
 * {@code ldoc-sid} cookie as the password form, on the page asked for, or with
 * the browser back at the sign-in page and a refusal's code.</li>
 * <li>{@code GET /signin/done} is the page that tells the person to close the
 * tab, which the helper's sign-in ends on (spec section 6.2).</li>
 * </ul>
 * Nothing secret is logged: no code, state, nonce, verifier, token, secret or
 * cookie. Each refusal is logged with the provider, the identity if it's
 * known, and its code.
 */
@Component
@Path("/signin")
public class SignInResource {

	/** The cookie that binds a sign-in to the browser that started it */
	static final String COOKIE = "hd_signin";

	/**
	 * The sign-in took more than 10 minutes, or its state was unknown or used,
	 * or it came back to another browser or another provider's callback
	 */
	static final String EXPIRED = "expired";

	/** The provider couldn't be used, refused, or what it sent failed a check */
	static final String PROVIDER = "provider";

	/** CE's sign-in chain refused the account, or Healdan couldn't decide it */
	static final String ACCOUNT = "account";

	/** Where {@code login.jsp} finds the page asked for before signing in */
	static final String REQUESTED_URL = "RequestedUrl";

	/**
	 * Spring Security's {@code HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY}:
	 * where a password sign-in left in the HTTP session would be
	 */
	static final String SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";

	static final String SIGNED_IN = "Signed in to Healdan. You can close this tab.";

	/** What stands for the provider's label when the page doesn't know which provider it was */
	static final String SOME_PROVIDER = "your sign-in provider";

	/** What each refusal says (spec section 5.3); {@code {label}} is the provider's label */
	static final Map<String, String> MESSAGES = Map.of(SignInAccounts.NO_GROUP,
			"Your account at {label} doesn't give you access to Healdan. Ask an administrator.",
			SignInAccounts.EMAIL_TAKEN, "Healdan can't tell which account is yours. Ask an administrator.",
			SignInAccounts.USERNAME_TAKEN, "Healdan already has an account with your username. Ask an administrator.",
			ACCOUNT, "This account can't sign in now. Ask an administrator.", PROVIDER,
			"{label} couldn't sign you in. Try again, or ask an administrator.", EXPIRED,
			"That sign-in took too long or was already used. Start again.");

	private static final String NO_ADDRESS = "Healdan can't sign you in through a provider, because it doesn't know "
			+ "its own address. Ask whoever runs the server to set LOGICALDOC_PUBLIC_URL to its https:// address, such "
			+ "as https://healdan.example/, and restart Healdan.";

	private static final String PAGE = "healdan/api/signin/done.html";

	/** The page has no script, no image and nothing from elsewhere: only its own inline style */
	private static final String POLICY = "default-src 'none'; style-src 'unsafe-inline'";

	private static final String NO_STORE = "no-store";

	private static final String REFERRER_POLICY = "Referrer-Policy";

	/** An error code from the provider that can be quoted in the log as it is */
	private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

	/** The most characters of the provider's text quoted in the log */
	private static final int MAX_QUOTED = 200;

	private static final Logger log = LoggerFactory.getLogger(SignInResource.class);

	/**
	 * Starts a sign-in through a provider for the web page: Healdan keeps the
	 * state, nonce and verifier for 10 minutes, and binds them to this
	 * browser by a random value in the {@code hd_signin} cookie, whose SHA-256
	 * it keeps.
	 *
	 * @param id the provider's id
	 * @param request the request
	 * @return {@code 302} to the provider, or to the sign-in page with
	 *         {@code signin=provider} if the provider isn't on and set up or
	 *         can't be reached; {@code 409} if Healdan has no public address
	 */
	@GET
	@Public
	@Path("{id: [a-z0-9-]{1,32}}")
	public Response start(@PathParam("id") String id, @Context HttpServletRequest request) {
		Address address = PublicAddress.of(request).orElse(null);
		if (address == null)
			return noAddress(id);

		SignInProvider provider = usable(id);
		if (provider == null)
			return refused(address, id, null, PROVIDER, "no provider with that id is on and set up", false).build();

		ProviderClient.Start start;
		try {
			start = ProviderClient.get().start(provider, SignInProviders.callback(address.base(), id), false);
		} catch (ProviderException e) {
			return refused(address, id, null, PROVIDER, "it couldn't start: " + e.getMessage(), true).build();
		}

		String browser = ApiTokens.randomPart();
		PendingSignIns pending = PendingSignIns.get();
		pending.put(new Pending(id, start.state(), start.nonce(), start.verifier(), ApiTokens.hash(browser),
				pending.expiry(), null, null, null));
		return redirect(start.authorization()).header(HttpHeaders.SET_COOKIE,
				COOKIE + "=" + browser + cookieAttributes(address, request.isSecure())).build();
	}

	/**
	 * Where the provider sends the browser back: it checks the sign-in, then
	 * exchanges the code, decides the account and opens its CE session. The
	 * HTTP session gets a new id, then {@code ldoc-sid} is set and the
	 * session's id saved in the HTTP session, as the password form does.
	 *
	 * @param id the provider's id, from the address
	 * @param code the provider's code
	 * @param state the sign-in's state
	 * @param error the provider's error, such as {@code access_denied}
	 * @param errorDescription the provider's words about its error, for the
	 *        log
	 * @param request the request
	 * @param response the response, for the session's cookie
	 * @return {@code 302} to the page asked for or the web page, or to the
	 *         sign-in page with a refusal's code; {@code 409} if Healdan has no
	 *         public address
	 */
	@GET
	@Public
	@Path("{id: [a-z0-9-]{1,32}}/callback")
	public Response callback(@PathParam("id") String id, @QueryParam("code") String code,
			@QueryParam("state") String state, @QueryParam("error") String error,
			@QueryParam("error_description") String errorDescription, @Context HttpServletRequest request,
			@Context HttpServletResponse response) {
		Address address = PublicAddress.of(request).orElse(null);
		if (address == null)
			return noAddress(id);

		// Taken whatever comes of it, so a state works once
		Pending pending = PendingSignIns.get().take(state).orElse(null);
		if (pending == null)
			return refused(address, id, null, EXPIRED, "its state is unknown, was used or has expired", false)
					.build();
		// One callback per provider: an answer to another provider's sign-in isn't this one's (mix-up)
		if (!pending.provider().equals(id))
			return refused(address, id, null, EXPIRED,
					"it was started with the sign-in provider " + pending.provider() + ", not this one", true).build();
		// Only the browser that started a sign-in may finish it (login CSRF)
		if (!sameBrowser(request, pending))
			return refused(address, id, null, EXPIRED, "it came back to a browser that didn't start it", true)
					.build();

		// The cookie has done its work, whatever comes of the rest
		return finish(pending, code, error, errorDescription, address, request, response)
				.header(HttpHeaders.SET_COOKIE, COOKIE + "=" + "; Max-Age=0" + cookieAttributes(address,
						request.isSecure()))
				.build();
	}

	/**
	 * The page that tells the person to close the tab, or what went wrong.
	 *
	 * @param error a refusal's code; none if the sign-in worked
	 * @param provider the id of the provider, whose label the message names;
	 *        without one it names "your sign-in provider"
	 * @return {@code 200} with the page
	 */
	@GET
	@Public
	@Path("done")
	public Response done(@QueryParam("error") String error, @QueryParam("provider") String provider) {
		return page(Response.Status.OK.getStatusCode(), error == null ? SIGNED_IN : message(error, provider));
	}

	/**
	 * @param code a refusal's code; one that isn't known says the provider
	 *        couldn't sign the person in
	 * @param providerId the provider's id, or null
	 * @return what the refusal says, with the provider's label
	 */
	static String message(String code, String providerId) {
		String text = MESSAGES.getOrDefault(code, MESSAGES.get(PROVIDER)).replace("{label}", label(providerId));
		// At the start of a sentence
		return text.startsWith(SOME_PROVIDER) ? "Y" + text.substring(1) : text;
	}

	private Response.ResponseBuilder finish(Pending pending, String code, String error, String errorDescription,
			Address address, HttpServletRequest request, HttpServletResponse response) {
		String id = pending.provider();
		SignInProvider provider = usable(id);
		if (provider == null)
			return refused(address, id, null, PROVIDER, "the provider isn't on and set up any more", false);
		if (error != null)
			return refused(address, id, null, PROVIDER,
					"the provider answered " + providerError(error, errorDescription, pending, code), true);

		ProviderClient.Tokens tokens;
		try {
			tokens = ProviderClient.get().exchange(provider, SignInProviders.callback(address.base(), id), code,
					pending.verifier(), pending.nonce());
		} catch (ProviderException e) {
			return refused(address, id, null, PROVIDER, e.getMessage(), true);
		} catch (RuntimeException e) {
			// Only its class: its message could quote what the provider sent
			return refused(address, id, null, PROVIDER, "the code's exchange failed on " + e.getClass().getName(),
					true);
		}

		IdClaims claims = tokens.claims();
		Decision decision;
		try {
			decision = SignInAccounts.get().decide(provider, claims);
		} catch (PersistenceException | RuntimeException e) {
			log.error("Couldn't decide the account of a sign-in through the sign-in provider {}", id, e);
			return refused(address, id, claims.identity(), ACCOUNT, "Healdan couldn't decide its account", true);
		}
		if (decision instanceof Refused refusal)
			return refused(address, id, claims.identity(), refusal.code(), "Healdan refused its account", false);
		User user = ((Allowed) decision).user();

		Session session;
		try {
			session = SignIns.openSession(user, SessionManager.get().buildClient(request));
		} catch (AuthenticationException e) {
			// Disabled, expired, outside working hours, legals not confirmed, or the address blocked
			return refused(address, id, claims.identity(), ACCOUNT, "LogicalDOC's sign-in refused the account "
					+ quote(user.getUsername()) + " (" + e.getClass().getSimpleName() + ")", false);
		}
		if (session == null)
			return refused(address, id, claims.identity(), ACCOUNT,
					"LogicalDOC's sign-in opened no session for the account " + quote(user.getUsername()), true);

		String landing = landing(request, address);
		signInBrowser(session.getSid(), request, response);
		log.info("Signed {} in to the web page through the sign-in provider {}", quote(user.getUsername()), id);
		return redirect(URI.create(landing));
	}

	/**
	 * Gives the browser the CE session as the password form does
	 * ({@code LDAuthenticationSuccessHandler}): a new HTTP session id against
	 * fixation, the HttpOnly {@code ldoc-sid} cookie on {@code /}, Secure when
	 * {@code cookies.secure} is on, and the session's id in the HTTP session,
	 * bound to it as {@code LDSecurityContextRepository} binds a password
	 * sign-in's, so killing the session in Sessions ends the HTTP session too.
	 * A password sign-in someone else left in the HTTP session is dropped, so
	 * it can't stand in for this one.
	 */
	private static void signInBrowser(String sid, HttpServletRequest request, HttpServletResponse response) {
		request.getSession(true);
		request.changeSessionId();
		HttpSession httpSession = request.getSession(true);
		httpSession.removeAttribute(SECURITY_CONTEXT);

		Cookie cookie = new Cookie(SessionManager.COOKIE_SID, sid);
		cookie.setHttpOnly(true);
		cookie.setSecure(com.logicaldoc.util.spring.Context.get().getConfig().getBoolean("cookies.secure", false));
		cookie.setPath("/");
		response.addCookie(cookie);
		SessionManager.get().saveSid(request, response, sid);
		LDDeferredSecurityContext.bindServletSession(sid, httpSession);
	}

	/**
	 * Where the password form would go: the HTTP session's
	 * {@code RequestedUrl}, under {@code login.jsp}'s conditions, and only if
	 * it's a page on this server; otherwise the web page.
	 */
	static String landing(HttpServletRequest request, Address address) {
		HttpSession httpSession = request.getSession(false);
		Object requested = httpSession != null ? httpSession.getAttribute(REQUESTED_URL) : null;
		String page = requested != null ? onThisServer(requested.toString(), address) : null;
		return page != null ? page : address.base() + "/frontend.jsp";
	}

	/**
	 * @param requested a page asked for, as a path or an address
	 * @param address the server's public address
	 * @return the page as an address on this server, or null if
	 *         {@code login.jsp} ignores it or it's anywhere else: another host,
	 *         port or scheme, a path that starts {@code //}, outside the
	 *         install's path, or anything {@link URI} can't parse, such as a
	 *         backslash or a line break
	 */
	static String onThisServer(String requested, Address address) {
		// login.jsp's own conditions
		if (requested.contains("/ace/") || requested.contains("/sc/") || requested.contains("/info")
				|| requested.endsWith("/frontend"))
			return null;

		URI uri;
		try {
			uri = new URI(requested);
		} catch (URISyntaxException e) {
			return null;
		}
		if (uri.isOpaque())
			return null;
		if (uri.getScheme() != null || uri.getRawAuthority() != null) {
			if (uri.getScheme() == null || uri.getRawAuthority() == null)
				return null;
			String origin = (uri.getScheme() + "://" + uri.getRawAuthority()).toLowerCase(Locale.ROOT);
			if (!origin.equals(address.origin()))
				return null;
		}

		String path = uri.getRawPath();
		String root = address.base().substring(address.origin().length()) + "/";
		if (path == null || !path.startsWith(root) || path.startsWith("//"))
			return null;
		return address.origin() + path + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
	}

	/**
	 * @return the provider, if it's on and has an issuer, a client id and a
	 *         secret that can be opened; otherwise null
	 */
	private static SignInProvider usable(String id) {
		return SignInProviders.get().enabled().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
	}

	/**
	 * @return true if the request carries the {@code hd_signin} cookie the
	 *         sign-in was started with, compared by its hash in constant time
	 */
	private static boolean sameBrowser(HttpServletRequest request, Pending pending) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null || pending.browserHash() == null)
			return false;
		byte[] expected = pending.browserHash().getBytes(StandardCharsets.US_ASCII);
		boolean found = false;
		for (Cookie cookie : cookies)
			if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()
					&& MessageDigest.isEqual(expected,
							ApiTokens.hash(cookie.getValue()).getBytes(StandardCharsets.US_ASCII)))
				found = true;
		return found;
	}

	/**
	 * @return the {@code hd_signin} cookie's attributes: on the sign-in pages'
	 *         path at the public address, HttpOnly, {@code SameSite=Lax} so the
	 *         provider's redirect brings it back, and Secure over HTTPS
	 */
	private static String cookieAttributes(Address address, boolean secure) {
		String path = address.base().substring(address.origin().length()) + "/api/signin/";
		return "; Path=" + path + "; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "");
	}

	/**
	 * Logs a refusal, with the provider, the identity if it's known, and the
	 * code, and sends the browser to the sign-in page with the code.
	 *
	 * @param why what went wrong, for the log; never a secret
	 * @param notable true to log it as a warning: the provider failing, Healdan
	 *        failing, or a sign-in coming back where it shouldn't, which are
	 *        worth an admin's look
	 */
	private static Response.ResponseBuilder refused(Address address, String provider, String identity, String code,
			String why, boolean notable) {
		String who = identity != null ? " for the identity " + quote(identity) : "";
		if (notable)
			log.warn("Refused a sign-in through the sign-in provider {}{} ({}): {}", provider, who, code, why);
		else
			log.info("Refused a sign-in through the sign-in provider {}{} ({}): {}", provider, who, code, why);
		return redirect(URI.create(address.base() + "/login.jsp?signin=" + URLEncoder.encode(code,
				StandardCharsets.UTF_8)));
	}

	private static Response.ResponseBuilder redirect(URI location) {
		return Response.status(Response.Status.FOUND).location(location).header(HttpHeaders.CACHE_CONTROL, NO_STORE)
				.header(REFERRER_POLICY, "no-referrer");
	}

	private static Response noAddress(String id) {
		log.warn("Can't run a sign-in through the sign-in provider {}: Healdan has no public address it can use. "
				+ "Set LOGICALDOC_PUBLIC_URL to the server's https:// address", id);
		return page(Response.Status.CONFLICT.getStatusCode(), NO_ADDRESS);
	}

	/**
	 * @return the page, with the message in it, HTML-escaped
	 */
	private static Response page(int status, String message) {
		String html = new String(read(PAGE), StandardCharsets.UTF_8).replace("{{MESSAGE}}", escape(message));
		return Response.status(status).entity(html.getBytes(StandardCharsets.UTF_8)).type("text/html; charset=UTF-8")
				.header("Content-Security-Policy", POLICY).header("X-Content-Type-Options", "nosniff")
				.header(HttpHeaders.CACHE_CONTROL, NO_STORE).header(REFERRER_POLICY, "no-referrer").build();
	}

	private static String label(String id) {
		if (id == null || !SignInProviders.ID.matcher(id).matches())
			return SOME_PROVIDER;
		return SignInProviders.get().find(id).map(SignInProvider::label).orElse(SOME_PROVIDER);
	}

	/**
	 * @return the provider's error code, if it's fit to quote, and its
	 *         description, on one line and with the sign-in's secrets hidden
	 */
	private static String providerError(String error, String description, Pending pending, String code) {
		StringBuilder text = new StringBuilder(
				ERROR_CODE.matcher(error).matches() ? error : "an error code that isn't fit to quote");
		if (description != null && !description.isBlank()) {
			String redacted = description;
			for (String secret : new String[] { pending.state(), pending.nonce(), pending.verifier(), code })
				if (secret != null && !secret.isEmpty())
					redacted = redacted.replace(secret, "[hidden]");
			text.append(": ").append(quote(redacted));
		}
		return text.toString();
	}

	/** @return text from outside, on one line and at most {@value #MAX_QUOTED} characters */
	private static String quote(String text) {
		if (text == null)
			return "";
		StringBuilder line = new StringBuilder();
		text.codePoints().forEach(c -> {
			int type = Character.getType(c);
			boolean control = Character.isISOControl(c) || type == Character.FORMAT
					|| type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
			line.appendCodePoint(control ? ' ' : c);
		});
		return line.length() > MAX_QUOTED ? line.substring(0, MAX_QUOTED) + "..." : line.toString();
	}

	private static String escape(String text) {
		StringBuilder escaped = new StringBuilder(text.length());
		for (char c : text.toCharArray())
			switch (c) {
			case '&' -> escaped.append("&amp;");
			case '<' -> escaped.append("&lt;");
			case '>' -> escaped.append("&gt;");
			case '"' -> escaped.append("&quot;");
			case '\'' -> escaped.append("&#39;");
			default -> escaped.append(c);
			}
		return escaped.toString();
	}

	private static byte[] read(String resource) {
		try (InputStream in = SignInResource.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null)
				throw new IllegalStateException("Missing from the classpath: " + resource);
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
