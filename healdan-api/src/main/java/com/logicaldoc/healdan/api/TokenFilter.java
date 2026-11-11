// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.spring.LDAuthenticationToken;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.signin.ProviderRecheck;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.healdan.token.HealdanTokenAuthenticator;
import com.logicaldoc.healdan.token.TokenRefusedException;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * Signs every API request in, unless the resource method is {@link Public}
 * (spec section 5.3), in one of two ways:
 * <ul>
 * <li>from its {@code Authorization: Bearer} device token. A token from a
 * provider sign-in is checked back with its provider when that's due
 * ({@link ProviderRecheck}, M4 spec section 9), before its session;</li>
 * <li>with no Bearer {@code Authorization} header (none, or another scheme's,
 * such as the Basic credentials a browser sends to a proxy in front), from the
 * browser's CE web session, for Healdan's own pages, on
 * {@link WebSessionRoute}'s rule (M6 spec section 8.1). There the caller has
 * no token.</li>
 * </ul>
 * Nothing else signs a request in: no {@code sid} parameter or header, no
 * {@code ldoc-sid} cookie, no Basic and no {@code X-API-KEY} (section 5.1).
 * <p>
 * For the length of the request the caller is in {@link Caller}, and an
 * {@link LDAuthenticationToken} with the request's CE session is in
 * {@link SecurityContextHolder}, where CE's code looks for the current
 * session. {@link ApiServlet} clears both after every request, once the
 * response is written: a streamed body, such as a document's content, is
 * written after the response filters ran.
 */
@Priority(Priorities.AUTHENTICATION)
public class TokenFilter implements ContainerRequestFilter {

	private static final Pattern BEARER = Pattern.compile("^Bearer[ \\t]+(\\S+)[ \\t]*$", Pattern.CASE_INSENSITIVE);

	/** A Bearer authorization, well formed or not */
	private static final Pattern BEARER_SCHEME = Pattern.compile("^[ \\t]*Bearer(?:[ \\t].*)?$",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final String NO_TOKEN = "Sign in first, and send the device token as Authorization: Bearer.";

	private static final String REFUSED = "This device token doesn't work any more. Sign in again.";

	private static final String NOT_WEB = "This request can't use the browser's session: %s.";

	private static final String SIGNED_OUT = "You're signed out, or your session has ended. Sign in again.";

	@Context
	private ResourceInfo resourceInfo;

	@Context
	private HttpServletRequest servletRequest;

	@Override
	public void filter(ContainerRequestContext requestContext) {
		Method method = resourceInfo.getResourceMethod();
		if (method != null && method.isAnnotationPresent(Public.class))
			return;

		String authorization = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
		if (!bearerScheme(authorization)) {
			webSession(requestContext);
			return;
		}

		String presented = bearer(authorization);
		if (presented == null) {
			requestContext.abortWith(unauthenticated(NO_TOKEN, "Bearer"));
			return;
		}

		// Refused here, before CE's sign-in chain, so a stale token never counts against the client's address
		ApiTokens tokens = ApiTokens.get();
		Date now = new Date();
		ApiToken token = tokens.findUsable(presented, now).orElse(null);
		if (token == null) {
			requestContext.abortWith(refused());
			return;
		}

		// Checked on every request as CE's sign-in checks a user: deleting a user fires no listener, and an
		// expiry date or working hours can pass while the bound session stays open
		User user = signInUser(token);
		if (user == null) {
			requestContext.abortWith(refused());
			return;
		}

		// The provider's say-so (M4 spec section 9), before the session and never inside CE's newSession: a check
		// that revokes the token closes its sessions, and nothing opens one for a revoked token
		SessionManager sessions = SessionManager.get();
		boolean reopening = bound(sessions, token, user) == null;
		if (!ProviderRecheck.get().keep(token, reopening)) {
			requestContext.abortWith(refused());
			return;
		}

		Session session;
		try {
			session = session(presented, token, user, reopening);
		} catch (AuthenticationException e) {
			ApiProblem throttled = AuthResource.throttled(e);
			requestContext.abortWith(throttled != null ? ProblemMapper.problem(throttled) : refused());
			return;
		}
		if (session == null) {
			requestContext.abortWith(refused());
			return;
		}

		tokens.touch(token, now);

		signIn(user, session, token);
	}

	/**
	 * The web-session route (M6 spec section 8.1), for a request with no
	 * Bearer {@code Authorization} header: signs it in with the browser's CE
	 * session if {@link WebSessionRoute} lets it. No one signs in here, so the
	 * throttle doesn't come into it. A request that isn't a same-origin
	 * browser request is refused as a request with no token is, with the
	 * Bearer challenge. A same-origin one that breaks the rule is
	 * {@code 403 web-session-refused}, so the page doesn't take it for a
	 * sign-out, and one with no open session is {@code 401} without a
	 * challenge.
	 */
	private void webSession(ContainerRequestContext requestContext) {
		if (!WebSessionRoute.sameOrigin(servletRequest)) {
			requestContext.abortWith(unauthenticated(NO_TOKEN, "Bearer"));
			return;
		}
		String refusal = WebSessionRoute.refusal(servletRequest).orElse(null);
		if (refusal != null) {
			requestContext.abortWith(ProblemMapper
					.problem(new ApiProblem(403, WebSessionRoute.REFUSED, NOT_WEB.formatted(refusal))));
			return;
		}

		Session session = WebSessionRoute.session(servletRequest).orElse(null);
		User user = session != null ? WebSessionRoute.user(session) : null;
		if (user == null) {
			requestContext.abortWith(webRefused(SIGNED_OUT));
			return;
		}

		SessionManager.get().renew(session.getSid());
		signIn(user, session, null);
	}

	/**
	 * Puts the caller in {@link Caller}, and an {@link LDAuthenticationToken}
	 * with its CE session in {@link SecurityContextHolder}, for the length of
	 * the request.
	 *
	 * @param token the device token, or null on the web-session route
	 */
	private static void signIn(User user, Session session, ApiToken token) {
		Caller.set(new Caller(user, session, token));
		LDAuthenticationToken authentication = new LDAuthenticationToken(user, null, authorities(user));
		authentication.setSid(session.getSid());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	/**
	 * Forgets the caller and the security context of this thread.
	 */
	static void clear() {
		Caller.clear();
		SecurityContextHolder.clearContext();
	}

	/**
	 * @return true if the authorization's scheme is Bearer, whether or not a
	 *         token follows; false for none or another scheme
	 */
	static boolean bearerScheme(String authorization) {
		return authorization != null && BEARER_SCHEME.matcher(authorization).matches();
	}

	/**
	 * @return the token in a Bearer authorization, or null if there's none
	 */
	static String bearer(String authorization) {
		if (authorization == null)
			return null;
		Matcher matcher = BEARER.matcher(authorization);
		return matcher.matches() ? matcher.group(1) : null;
	}

	/**
	 * @return the token's user, with its groups, if it still exists and may
	 *         sign in now, otherwise null
	 */
	private static User signInUser(ApiToken token) {
		try {
			return HealdanTokenAuthenticator.get().signInUser(token);
		} catch (TokenRefusedException e) {
			return null;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the user of a device token", e);
		}
	}

	/**
	 * @param checkedForReopening true if {@link ProviderRecheck#keep} was asked
	 *        as for a session opened again
	 * @return the CE session bound to the token, renewed, or a new one opened
	 *         for the token and bound to it ({@link ApiTokens#openSession}),
	 *         which CE's own routes can't find by the token; null if the
	 *         provider refused the token, now revoked
	 * @throws AuthenticationException if CE's sign-in chain refuses the token
	 */
	private Session session(String presented, ApiToken token, User user, boolean checkedForReopening)
			throws AuthenticationException {
		SessionManager sessions = SessionManager.get();
		Session open = bound(sessions, token, user);
		if (open != null) {
			sessions.renew(open.getSid());
			return open;
		}

		// It closed since the provider was asked, so opening it again is checked as any reopening is
		if (!checkedForReopening && !ProviderRecheck.get().keep(token, true))
			return null;
		return ApiTokens.get().openSession(presented, token, sessions.buildClient(servletRequest));
	}

	/**
	 * @return the open CE session bound to the token, if it's the user's, or
	 *         null
	 */
	private static Session bound(SessionManager sessions, ApiToken token, User user) {
		String sid = token.getSid();
		if (sid == null || !sessions.isOpen(sid))
			return null;
		Session open = sessions.get(sid);
		return open != null && open.getUserId() == user.getId() ? open : null;
	}

	/**
	 * @return the user's groups as authorities, as CE's own session filter
	 *         gives them
	 */
	private static Collection<GrantedAuthority> authorities(User user) {
		return user.getGroups().stream().map(Group::getName).<GrantedAuthority>map(SimpleGrantedAuthority::new)
				.toList();
	}

	private static Response refused() {
		return unauthenticated(REFUSED, "Bearer error=\"invalid_token\"");
	}

	/**
	 * @return {@code 401 unauthenticated} without a challenge, which a
	 *         browser's page needn't answer
	 */
	private static Response webRefused(String detail) {
		return ProblemMapper.problem(ApiProblem.unauthenticated(detail));
	}

	private static Response unauthenticated(String detail, String challenge) {
		return Response.fromResponse(ProblemMapper.problem(ApiProblem.unauthenticated(detail)))
				.header(HttpHeaders.WWW_AUTHENTICATE, challenge).build();
	}
}
