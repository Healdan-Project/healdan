package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.apikey.ApiKey;
import com.logicaldoc.core.security.apikey.ApiKeyDAO;
import com.logicaldoc.core.security.spring.LDAuthenticationProvider;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.healdan.token.TokenRefusedException;
import com.logicaldoc.webservice.rest.endpoint.RestAuthService;
import com.logicaldoc.webservice.soap.endpoint.SoapAuthService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A device token signs in only at {@code /api} (spec section 5.1). LogicalDOC's
 * own routes refuse it however it's sent: to {@code loginApiKey} over SOAP or
 * REST, as {@code X-API-KEY} while a CE session the API opened for it is open,
 * and as the password or the key of a Basic sign-in, which WebDAV, CMIS and
 * the REST services take.
 */
public class LogicalDocRoutesTest extends ApiTestCase {

	private static final String ME = "/api/v1/me";

	private User clerk;

	@Before
	public void createClerk() throws Exception {
		clerk = fx.user("clerk");
	}

	private String boundSid(String token) {
		return jdbc().queryForObject("select hd_sid from hd_api_token where hd_hash = ?", String.class,
				ApiTokens.hash(token));
	}

	/**
	 * Closes the token's CE session, so that the API's filter opens a new one
	 * on the next request, as it does once the first one times out.
	 *
	 * @return the session the API opened
	 */
	private String reopened(String token) {
		String first = boundSid(token);
		SessionManager.get().kill(first);
		ApiResponse me = get(ME).token(token).send();
		assertEquals(me.toString(), 200, me.status());
		String sid = boundSid(token);
		assertNotEquals(first, sid);
		assertTrue(SessionManager.get().isOpen(sid));
		return sid;
	}

	private static MockHttpServletRequest request(String apiKey) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/services/rest/auth/loginApiKey");
		request.setRemoteAddr("10.9.8.7");
		if (apiKey != null)
			request.addHeader(SessionManager.HEADER_APIKEY, apiKey);
		return request;
	}

	private static Message message(HttpServletRequest request) {
		Message message = new MessageImpl();
		message.put(AbstractHTTPDestination.HTTP_REQUEST, request);
		return message;
	}

	/** CE's SOAP auth service, as CXF runs it for a request */
	private static SoapAuthService soapAuth(HttpServletRequest request) {
		Message message = message(request);
		return new SoapAuthService() {
			{
				currentMessage = message;
			}
		};
	}

	/** CE's REST auth service, as CXF runs it for a request */
	private static RestAuthService restAuth(HttpServletRequest request) {
		Message message = message(request);
		return new RestAuthService() {
			{
				currentMessage = message;
			}
		};
	}

	private long openSessions() {
		return SessionManager.get().getSessions().stream()
				.filter(s -> s.isOpen() && clerk.getUsername().equals(s.getUsername())).count();
	}

	private int failureCounts() {
		return jdbc().queryForObject("select count(*) from ld_sequence where ld_name like 'loginfail-%'",
				Integer.class);
	}

	@Test
	public void testLoginApiKeyRefusesTokens() throws Exception {
		String token = signIn(clerk);

		assertThrows(TokenRefusedException.class, () -> soapAuth(request(null)).loginApiKey(token));
		assertThrows(TokenRefusedException.class, () -> restAuth(request(token)).loginApiKey(token));

		// Also while a CE session the API opened for the token is open
		reopened(token);
		assertThrows(TokenRefusedException.class, () -> soapAuth(request(null)).loginApiKey(token));
		assertThrows(TokenRefusedException.class, () -> restAuth(request(token)).loginApiKey(token));

		// No session but the API's own, and no failure counted
		assertEquals(1L, openSessions());
		assertEquals(0, failureCounts());

		// LogicalDOC's own API keys still sign in there
		ApiKey key = new ApiKey(clerk.getId(), "Script");
		key.setTenantId(clerk.getTenantId());
		ApiKeyDAO.get().store(key);
		String sid = restAuth(request(key.getDecodedKey())).loginApiKey(key.getDecodedKey());
		assertTrue(SessionManager.get().isOpen(sid));
	}

	@Test
	public void testApiKeyHeaderFindsNoSession() throws Exception {
		String token = signIn(clerk);

		// Every LogicalDOC route finds its session this way: the session filter, Basic sign-ins, WebDAV, CMIS and
		// the web services
		assertNull(SessionManager.get().getSessionId(request(token)));

		String sid = reopened(token);
		assertNull(SessionManager.get().getSessionId(request(token)));
		assertNull(SessionManager.get().getSession(request(token)));

		// The API goes on using the session it opened, and signing out closes it
		assertEquals(200, get(ME).token(token).send().status());
		assertEquals(sid, boundSid(token));
		assertEquals(204, delete("/api/v1/auth/tokens/current").token(token).send().status());
		assertFalse(SessionManager.get().isOpen(sid));
	}

	@Test
	public void testBasicSignInRefusesTokens() throws Exception {
		String token = signIn(clerk);
		reopened(token);

		// As the password
		assertThrows(org.springframework.security.core.AuthenticationException.class,
				() -> basic(request(null), clerk.getUsername(), token));

		// As the sign-in's key, with any password
		MockHttpServletRequest withKey = request(null);
		withKey.addParameter("key", token);
		assertThrows(org.springframework.security.core.AuthenticationException.class,
				() -> basic(withKey, clerk.getUsername(), "Not-the-password-1"));

		// The password itself still signs in
		assertNotNull(basic(request(null), clerk.getUsername(), HealdanFixtures.PASSWORD));
	}

	/** A Basic sign-in, as Spring Security hands it to CE */
	private static Authentication basic(MockHttpServletRequest request, String username, String password) {
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		try {
			return new LDAuthenticationProvider()
					.authenticate(new UsernamePasswordAuthenticationToken(username, password));
		} finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}
}
