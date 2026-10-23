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
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokens;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * Signs every API request in from its {@code Authorization: Bearer} device
 * token, unless the resource method is {@link Public} (spec section 5.3).
 * Nothing else signs a request in: no cookie, {@code sid}, Basic or
 * {@code X-API-KEY} (section 5.1).
 * <p>
 * For the length of the request the caller is in {@link Caller}, and an
 * {@link LDAuthenticationToken} with the token's CE session is in
 * {@link SecurityContextHolder}, where CE's code looks for the current
 * session. The response filter clears both, and {@link ApiServlet} clears
 * them again whatever happened.
 */
@Priority(Priorities.AUTHENTICATION)
public class TokenFilter implements ContainerRequestFilter, ContainerResponseFilter {

	private static final Pattern BEARER = Pattern.compile("^Bearer[ \\t]+(\\S+)[ \\t]*$", Pattern.CASE_INSENSITIVE);

	private static final String NO_TOKEN = "Sign in first, and send the device token as Authorization: Bearer.";

	private static final String REFUSED = "This device token doesn't work any more. Sign in again.";

	@Context
	private ResourceInfo resourceInfo;

	@Context
	private HttpServletRequest servletRequest;

	@Override
	public void filter(ContainerRequestContext requestContext) {
		Method method = resourceInfo.getResourceMethod();
		if (method != null && method.isAnnotationPresent(Public.class))
			return;

		String presented = bearer(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION));
		if (presented == null) {
			requestContext.abortWith(unauthenticated(NO_TOKEN, "Bearer"));
			return;
		}

		// Refused here, before CE's sign-in chain, so a stale token never counts against the client's address
		ApiTokens tokens = ApiTokens.get();
		Date now = new Date();
		ApiToken token = tokens.find(presented).filter(t -> tokens.usable(t, now)).orElse(null);
		if (token == null) {
			requestContext.abortWith(refused());
			return;
		}

		// Checked on every request: deleting a user fires no listener, and the bound session may predate a change
		User user = activeUser(token.getUserId());
		if (user == null) {
			requestContext.abortWith(refused());
			return;
		}

		Session session;
		try {
			session = session(presented, token, user);
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

		Caller.set(new Caller(user, session, token));
		LDAuthenticationToken authentication = new LDAuthenticationToken(user, null, authorities(user));
		authentication.setSid(session.getSid());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	@Override
	public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
		clear();
	}

	/**
	 * Forgets the caller and the security context of this thread.
	 */
	static void clear() {
		Caller.clear();
		SecurityContextHolder.clearContext();
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
	 * @return the user, with its groups, if it still exists and is enabled
	 */
	private static User activeUser(long userId) {
		try {
			User user = UserDAO.get().findById(userId, true);
			return user != null && user.getDeleted() == 0 && user.isEnabled() ? user : null;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the user of a device token", e);
		}
	}

	/**
	 * @return the CE session bound to the token, renewed, or a new one opened
	 *         with the token as the key and bound to it
	 * @throws AuthenticationException if CE's sign-in chain refuses the token
	 */
	private Session session(String presented, ApiToken token, User user) throws AuthenticationException {
		SessionManager sessions = SessionManager.get();
		String sid = token.getSid();
		if (sid != null && sessions.isOpen(sid)) {
			Session open = sessions.get(sid);
			if (open != null && open.getUserId() == user.getId()) {
				sessions.renew(sid);
				return open;
			}
		}

		Session created = sessions.newSession(presented, sessions.buildClient(servletRequest));
		if (created != null)
			ApiTokens.get().bind(token, created.getSid());
		return created;
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

	private static Response unauthenticated(String detail, String challenge) {
		return Response.fromResponse(ProblemMapper.problem(ApiProblem.unauthenticated(detail)))
				.header(HttpHeaders.WWW_AUTHENTICATE, challenge).build();
	}
}
