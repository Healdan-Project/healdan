package com.logicaldoc.healdan.api;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.authentication.ApiKeyBlockedException;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.authentication.IPBlockedException;
import com.logicaldoc.core.security.authentication.PasswordExpiredException;
import com.logicaldoc.core.security.authentication.UsernameBlockedException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.SignIn;
import com.logicaldoc.healdan.api.model.SignedIn;
import com.logicaldoc.healdan.api.model.TokenView;
import com.logicaldoc.healdan.signin.OneTimeCodes;
import com.logicaldoc.healdan.signin.OneTimeCodes.Redeemed;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.signin.SignIns;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.spring.Context;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code /v1/auth/tokens}: signing in for a device token, with a password or
 * with the helper's one-time code from a provider sign-in (M4 spec section
 * 6.2), and the caller's own tokens (spec section 5.2). Nothing secret is
 * logged: no password, code, verifier, token or refresh token.
 */
@Component
@Path("/v1/auth/tokens")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

	static final String SIGN_IN_FAILED = "The username or password is wrong, or this account can't sign in.";

	static final String PASSWORD_EXPIRED = "Your password has expired. Change it in Healdan's web page, then sign in again.";

	/** The field of a sign-in's device id, in a validation problem */
	private static final String DEVICE_ID = "device.id";

	private static final String REQUIRED = "required";

	static final String NOT_BOTH = "Sign in with a username and password, or with a code, its verifier and its redirect "
			+ "address, not both.";

	/** What separates the two users of an impersonation in CE's sign-in */
	private static final String IMPERSONATION = ">";

	private static final Logger log = LoggerFactory.getLogger(AuthResource.class);

	/**
	 * Signs in for a device token, in one of two ways, never both:
	 * <ul>
	 * <li>with a username and password, through CE's whole sign-in chain,
	 * throttle included;</li>
	 * <li>with the one-time code a sign-in through a provider handed the
	 * helper's loopback address, its PKCE verifier and that address (M4 spec
	 * section 6.2, step 6). The code works once, for 60 seconds, and only with
	 * the verifier and the address it was issued for. The CE session opens
	 * here, through the chain, as for any provider sign-in (spec section
	 * 7.6).</li>
	 * </ul>
	 * Either way the token is bound to the session the sign-in opens. A code
	 * that doesn't work is refused exactly as a wrong password is.
	 *
	 * @param body the credentials, the device's name and its id
	 * @param request the HTTP request, for the client's address
	 * @return {@code 201} with the token
	 */
	@POST
	@Public
	@Consumes(MediaType.APPLICATION_JSON)
	public Response signIn(SignIn body, @jakarta.ws.rs.core.Context HttpServletRequest request) {
		SignIn credentials = body != null ? body : new SignIn(null, null, null, null, null, null, null);
		boolean byCode = credentials.code() != null || credentials.codeVerifier() != null
				|| credentials.redirectUri() != null;
		boolean byPassword = credentials.username() != null || credentials.password() != null;

		List<FieldError> errors = new ArrayList<>();
		if (byCode && byPassword)
			notBoth(credentials, errors);
		else if (byCode)
			requireCode(credentials, errors);
		else
			requirePassword(credentials, errors);
		// Before signing in, so a bad name opens no session, clears no failure count and uses up no code
		String name = name(credentials, errors);
		String device = device(credentials, errors);
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);

		return byCode ? exchange(credentials, name, device, request) : password(credentials, name, device, request);
	}

	private Response password(SignIn credentials, String name, String device, HttpServletRequest request) {
		// A device token stands for its own user's password, never for someone impersonated
		if (credentials.username().contains(IMPERSONATION))
			throw signInFailed();

		SessionManager sessions = SessionManager.get();
		Session session;
		try {
			session = sessions.newSession(credentials.username(), credentials.password(),
					sessions.buildClient(request));
		} catch (AuthenticationException e) {
			throw signInProblem(e, credentials.username(), credentials.password());
		}
		if (session == null)
			throw signInFailed();
		return issue(session, name, device, null, null);
	}

	/**
	 * Exchanges the helper's one-time code: it's redeemed, and so used up,
	 * whatever comes of the rest. The provider's refresh token goes with the
	 * device token, sealed, for the re-checks (spec section 9), and never to
	 * the helper.
	 */
	private Response exchange(SignIn credentials, String name, String device, HttpServletRequest request) {
		Redeemed redeemed = OneTimeCodes.get()
				.redeem(credentials.code(), credentials.codeVerifier(), credentials.redirectUri()).orElse(null);
		if (redeemed == null) {
			log.info("Refused a sign-in with a helper's code: the code is unknown, used or expired, or the verifier "
					+ "or the redirect address doesn't match it");
			throw signInFailed();
		}

		User user;
		try {
			user = UserDAO.get().findById(redeemed.userId());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the account of a helper's code", e);
		}
		if (user == null) {
			log.info("Refused a sign-in with a helper's code through the sign-in provider {}: its account is gone",
					redeemed.provider());
			throw signInFailed();
		}

		// Sealed before the session opens, so a key that can't be made leaves no session behind
		String refresh = SecretBox.get().seal(redeemed.refreshToken());
		SessionManager sessions = SessionManager.get();
		Session session;
		try {
			session = SignIns.openSession(user, sessions.buildClient(request));
		} catch (AuthenticationException e) {
			ApiProblem throttled = throttled(e);
			if (throttled != null)
				throw throttled;
			// Disabled, expired, outside working hours, unlinked meanwhile, or the address blocked
			log.info("LogicalDOC's sign-in refused the account {} of a helper's code through the sign-in provider "
					+ "{} ({})", SignInResource.quote(user.getUsername()), redeemed.provider(),
					e.getClass().getSimpleName());
			throw signInFailed();
		}
		if (session == null)
			throw signInFailed();

		Response response = issue(session, name, device, redeemed.provider(), refresh);
		if (redeemed.refreshToken() == null)
			// Spec section 9: it works as a password's token does, within M2's limits
			log.info("Issued a device token to {} through the sign-in provider {}, which gave no refresh token, so "
					+ "the token won't be re-checked with it", SignInResource.quote(user.getUsername()),
					redeemed.provider());
		else
			log.info("Issued a device token to {} through the sign-in provider {}",
					SignInResource.quote(user.getUsername()), redeemed.provider());
		return response;
	}

	/**
	 * Issues the device token, bound to the session the sign-in opened.
	 *
	 * @param provider the sign-in provider, or null for a password sign-in
	 * @param refresh the provider's refresh token, sealed, or null
	 */
	private static Response issue(Session session, String name, String device, String provider, String refresh) {
		User user = session.getUser();
		ApiTokens.Issued issued;
		try {
			issued = ApiTokens.get().issue(user, name, device, session.getSid(), provider, refresh);
		} catch (PersistenceException e) {
			SessionManager.get().kill(session.getSid());
			throw new IllegalStateException("Can't issue a device token", e);
		}

		ApiToken row = issued.row();
		// The password changed while signing in, so the token was revoked as soon as it was stored
		if (row.getDeleted() != 0)
			throw signInFailed();
		return Response.status(Response.Status.CREATED)
				.entity(new SignedIn(issued.token(), row.getId(), row.getName(), row.getExpires(), ApiViews.user(user)))
				.build();
	}

	/**
	 * @return the caller's usable tokens, newest first
	 */
	@GET
	public Page<TokenView> list() {
		Caller caller = Caller.current();
		long currentId = caller.token().getId();
		return Page.whole(ApiTokens.get().list(caller.user().getId()).stream()
				.map(t -> new TokenView(t.getId(), t.getName(), t.getCreation(), t.getLastUsed(), t.getExpires(),
						t.getId() == currentId))
				.toList());
	}

	/**
	 * Signs out: revokes the token making the request, and closes its
	 * session.
	 */
	@DELETE
	@Path("current")
	public Response signOut() {
		ApiTokens.get().revoke(Caller.current().token());
		return Response.noContent().build();
	}

	/**
	 * Revokes one of the caller's tokens.
	 *
	 * @param id the token's id
	 * @return {@code 204}, or {@code 404} if the caller has no such usable
	 *         token
	 */
	@DELETE
	@Path("{id: [0-9]+}")
	public Response revoke(@PathParam("id") long id) {
		ApiTokens tokens = ApiTokens.get();
		ApiToken token = tokens.list(Caller.current().user().getId()).stream().filter(t -> t.getId() == id)
				.findFirst().orElseThrow(() -> ApiProblem.notFound("You have no device token with this id."));
		tokens.revoke(token);
		return Response.noContent().build();
	}

	private static void requirePassword(SignIn credentials, List<FieldError> errors) {
		if (StringUtils.isBlank(credentials.username()))
			errors.add(new FieldError("username", REQUIRED, "Username is required."));
		if (StringUtils.isEmpty(credentials.password()))
			errors.add(new FieldError("password", REQUIRED, "Password is required."));
	}

	private static void requireCode(SignIn credentials, List<FieldError> errors) {
		if (StringUtils.isEmpty(credentials.code()))
			errors.add(new FieldError("code", REQUIRED, "Give the one-time code."));
		if (StringUtils.isEmpty(credentials.codeVerifier()))
			errors.add(new FieldError("codeVerifier", REQUIRED, "Give the code's verifier."));
		if (StringUtils.isEmpty(credentials.redirectUri()))
			errors.add(new FieldError("redirectUri", REQUIRED, "Give the redirect address the sign-in started with."));
	}

	/**
	 * A body with a password's fields and a code's: each field it has of
	 * either is invalid, and nothing is signed in or redeemed.
	 */
	private static void notBoth(SignIn credentials, List<FieldError> errors) {
		Map<String, String> given = new LinkedHashMap<>();
		given.put("username", credentials.username());
		given.put("password", credentials.password());
		given.put("code", credentials.code());
		given.put("codeVerifier", credentials.codeVerifier());
		given.put("redirectUri", credentials.redirectUri());
		given.forEach((field, value) -> {
			if (value != null)
				errors.add(new FieldError(field, "invalid", NOT_BOTH));
		});
	}

	/**
	 * @param credentials the sign-in's body
	 * @param errors where to add what's wrong with the device's name, as the
	 *        field {@code name}
	 * @return the name, stripped, or "Unnamed device"; null if it's wrong
	 */
	private static String name(SignIn credentials, List<FieldError> errors) {
		try {
			return ApiTokens.checkName(credentials.name());
		} catch (HealdanRuleException e) {
			// Too long, as for any other name; otherwise characters the database can't store
			errors.add(new FieldError("name", ApiTokens.nameTooLong(credentials.name()) ? Uploads.TOO_LONG : "invalid",
					e.getMessage()));
			return null;
		}
	}

	/**
	 * @param credentials the sign-in's body
	 * @param errors where to add what's wrong with the device's id, as the
	 *        field {@code device.id}
	 * @return the device's id, or null if the body names no device or a
	 *         wrong one
	 */
	private static String device(SignIn credentials, List<FieldError> errors) {
		if (credentials.device() == null)
			return null;
		String id = credentials.device().id();
		if (StringUtils.isEmpty(id)) {
			errors.add(new FieldError(DEVICE_ID, REQUIRED, "Give the device's id."));
			return null;
		}
		try {
			return ApiTokens.checkDevice(id);
		} catch (HealdanRuleException e) {
			errors.add(new FieldError(DEVICE_ID, ApiTokens.deviceTooLong(id) ? Uploads.TOO_LONG : "invalid",
					e.getMessage()));
			return null;
		}
	}

	private static ApiProblem signInFailed() {
		return new ApiProblem(401, "sign-in-failed", SIGN_IN_FAILED);
	}

	/**
	 * Turns a refused sign-in into its problem. The detail never says whether
	 * the username or the password was wrong, or that the account is
	 * disabled.
	 */
	private static ApiProblem signInProblem(AuthenticationException e, String username, String password) {
		ApiProblem throttled = throttled(e);
		if (throttled != null)
			return throttled;
		// CE's chain reports an expired password whatever the password given, so only the right one hears it
		if (e instanceof PasswordExpiredException && passwordMatches(username, password))
			return new ApiProblem(401, "password-expired", PASSWORD_EXPIRED);
		return signInFailed();
	}

	/**
	 * @param e why a sign-in was refused
	 * @return {@code 429 too-many-attempts} if the throttle blocked it, with
	 *         the configured wait, otherwise null
	 */
	static ApiProblem throttled(AuthenticationException e) {
		String waitKey;
		if (e instanceof UsernameBlockedException)
			waitKey = "throttle.username.wait";
		else if (e instanceof IPBlockedException)
			waitKey = "throttle.ip.wait";
		else if (e instanceof ApiKeyBlockedException)
			waitKey = "throttle.apikey.wait";
		else
			return null;
		int minutes = Context.get().getConfig().getInt(waitKey, 15);
		return new ApiProblem(429, "too-many-attempts", "Too many failed sign-ins. Try again in %s."
				.formatted(minutes == 1 ? "1 minute" : minutes + " minutes"));
	}

	private static boolean passwordMatches(String username, String password) {
		try {
			User user = UserDAO.get().getUser(username);
			return user != null && user.getPassword() != null
					&& user.getPassword().equals(Encrypter.encryptSHA256(password));
		} catch (PersistenceException | NoSuchAlgorithmException e) {
			log.warn("Can't check the password of {}", username, e);
			return false;
		}
	}
}
