// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

/**
 * The web-session route (M6 spec section 8.1): Healdan's own pages call the
 * API with the browser's CE session, same-origin only, and nothing else
 * reaches it. A request with no open session, or that no browser made from
 * this origin, is {@code 401 unauthenticated}; a same-origin one that breaks
 * the rule is {@code 403 web-session-refused}, so the page doesn't take it for
 * a sign-out. Clerk reads, writes and downloads in matter 998.1.
 */
public class WebSessionRouteTest extends ApiTestCase {

	private static final String ME = "/api/v1/me";

	private static final String FAVORITES = "/api/v1/me/favorites";

	private static final String PROBE = "/api/test/probe";

	private static final String SERVER_URL = "server.url";

	private static final String SAME_ORIGIN = "same-origin";

	/**
	 * A test-only resource that records what a request sees while it runs,
	 * for each method. It answers with a problem, as routes openapi.yaml
	 * doesn't describe must.
	 */
	@Path("/test")
	public static class Probe {

		static Caller caller;

		static String sid;

		static Set<String> authorities;

		@GET
		@Path("probe")
		public String get() {
			return probe();
		}

		@POST
		@Path("probe")
		public String post() {
			return probe();
		}

		@PATCH
		@Path("probe")
		public String patch() {
			return probe();
		}

		@PUT
		@Path("probe")
		public String put() {
			return probe();
		}

		@DELETE
		@Path("probe")
		public String delete() {
			return probe();
		}

		private static String probe() {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			sid = SessionManager.getCurrentSid();
			caller = Caller.current();
			authorities = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
					.collect(Collectors.toSet());
			throw ApiProblem.conflict("probed", "Probed.");
		}
	}

	private User clerk;

	private Folder documents;

	private String savedServerUrl;

	@Override
	protected List<Object> extraBeans() {
		return List.of(new Probe());
	}

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		savedServerUrl = Context.get().getConfig().getProperty(SERVER_URL);

		clerk = fx.user("clerk");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		Probe.caller = null;
		Probe.sid = null;
		Probe.authorities = null;
	}

	@Override
	public void tearDown() throws IOException {
		ContextProperties config = Context.get().getConfig();
		if (savedServerUrl == null)
			config.remove(SERVER_URL);
		else
			config.setProperty(SERVER_URL, savedServerUrl);
		super.tearDown();
	}

	@Test
	public void testSameOriginReadSignsIn() {
		MockHttpSession web = signInWeb(clerk);

		ApiResponse me = get(ME).webSession(web).send();

		assertEquals(me.toString(), 200, me.status());
		assertEquals("clerk", me.json().path("username").textValue());
	}

	@Test
	public void testOnlySameOrigin() {
		MockHttpSession web = signInWeb(clerk);

		for (String site : List.of("cross-site", "same-site", "none", "Same-Origin", ""))
			assertRefused(get(ME).session(web).header(ApiCall.FETCH_SITE, site).send());
		// An old browser, or no browser at all
		assertRefused(get(ME).session(web).send());
		// Nor for a change, with the client's header
		for (String site : List.of("cross-site", "same-site"))
			assertRefused(post(FAVORITES).session(web).header(ApiCall.FETCH_SITE, site).header(ApiCall.CLIENT, "web")
					.json(Map.of("folderId", documents.getId())).send());
		assertEquals(0, favorites(web));
	}

	// A request from no browser, or another site, is told as a token client is: with the Bearer challenge. A
	// same-origin one gets none
	@Test
	public void testChallengeOnlyOutsideTheBrowser() {
		MockHttpSession web = signInWeb(clerk);

		assertEquals("Bearer", get(ME).session(web).send().header("WWW-Authenticate"));
		assertEquals("Bearer",
				get(ME).session(web).header(ApiCall.FETCH_SITE, "cross-site").send().header("WWW-Authenticate"));
		assertNull(get(ME).header(ApiCall.FETCH_SITE, SAME_ORIGIN).send().header("WWW-Authenticate"));
		assertNull(get(ME).webSession(web).header("Origin", "https://elsewhere.example").send()
				.header("WWW-Authenticate"));
	}

	@Test
	public void testOriginMustBeTheServers() {
		MockHttpSession web = signInWeb(clerk);

		assertRejected(get(ME).webSession(web).header("Origin", "https://elsewhere.example").send());
		assertRejected(get(ME).webSession(web).header("Origin", "null").send());
		assertRejected(get(ME).webSession(web).header("Origin", "http://localhost:8081").send());
		assertRejected(get(ME).webSession(web).header("Origin", "https://localhost:8080").send());
		assertRejected(post(FAVORITES).webSession(web).header("Origin", "https://elsewhere.example")
				.json(Map.of("folderId", documents.getId())).send());
		assertEquals(0, favorites(web));

		// The test context's server.url is http://localhost:8080
		assertEquals(200, get(ME).webSession(web).header("Origin", "http://localhost:8080").send().status());
		assertEquals(201, post(FAVORITES).webSession(web).header("Origin", "http://localhost:8080")
				.json(Map.of("folderId", documents.getId())).send().status());
	}

	@Test
	public void testOriginFollowsThePublicAddress() {
		MockHttpSession web = signInWeb(clerk);
		Context.get().getConfig().setProperty(SERVER_URL, "https://healdan.example/");

		assertEquals(200, get(ME).webSession(web).header("Origin", "https://healdan.example").send().status());
		// The address the request came to, another name than the public one, as a server's IP address is
		ApiResponse added = post(FAVORITES).webSession(web).header("Origin", "http://localhost:8080")
				.json(Map.of("folderId", documents.getId())).send();
		assertEquals(added.toString(), 201, added.status());
		// Neither
		assertRejected(get(ME).webSession(web).header("Origin", "https://elsewhere.example").send());

		// The scheme's own port, written or not, is the same origin
		Context.get().getConfig().setProperty(SERVER_URL, "https://healdan.example:443");
		assertEquals(200, get(ME).webSession(web).header("Origin", "https://healdan.example").send().status());
		assertRejected(get(ME).webSession(web).header("Origin", "https://healdan.example:8443").send());
	}

	@Test
	public void testOriginWithoutAPublicAddress() {
		MockHttpSession web = signInWeb(clerk);
		Context.get().getConfig().remove(SERVER_URL);

		// Plain HTTP from elsewhere gives no public address: the scheme and host the request came to stand in
		ApiResponse same = get(ME).webSession(web).server("http", "healdan.lan", 8080)
				.header("Origin", "http://healdan.lan:8080").send();
		assertEquals(same.toString(), 200, same.status());
		assertRejected(get(ME).webSession(web).server("http", "healdan.lan", 8080)
				.header("Origin", "http://localhost:8080").send());
		assertRejected(get(ME).webSession(web).server("http", "healdan.lan", 8080)
				.header("Origin", "https://healdan.lan:8080").send());
	}

	@Test
	public void testChangesNeedTheClientHeader() {
		MockHttpSession web = signInWeb(clerk);
		Map<String, Long> body = Map.of("folderId", documents.getId());

		assertRejected(sameOrigin(post(FAVORITES), web).json(body).send());
		assertRejected(sameOrigin(post(FAVORITES), web).header(ApiCall.CLIENT, "Web").json(body).send());
		assertRejected(sameOrigin(post(FAVORITES), web).header(ApiCall.CLIENT, "app").json(body).send());
		assertEquals(0, favorites(web));
		ApiResponse added = post(FAVORITES).webSession(web).json(body).send();
		assertEquals(added.toString(), 201, added.status());

		String favorite = FAVORITES + "/" + added.json().path("id").asLong();
		assertRejected(sameOrigin(delete(favorite), web).send());
		assertEquals(1, favorites(web));
		assertEquals(204, delete(favorite).webSession(web).send().status());
		assertEquals(0, favorites(web));

		for (ApiCall change : List.of(post(PROBE), patch(PROBE), put(PROBE), delete(PROBE)))
			assertRejected(sameOrigin(change, web).send());
		for (ApiCall change : List.of(post(PROBE), patch(PROBE), put(PROBE), delete(PROBE)))
			assertProbed(change.webSession(web).send());
		// In any case: CXF matches a method whatever its case, and fetch() sends patch as it's written
		for (String method : List.of("post", "patch", "put", "delete"))
			assertRejected(sameOrigin(call(method, PROBE), web).send());
		// A read needs none
		assertProbed(sameOrigin(get(PROBE), web).send());
	}

	@Test
	public void testNeedsAnOpenSession() {
		// No HTTP session
		assertRefused(get(ME).header(ApiCall.FETCH_SITE, SAME_ORIGIN).send());

		// One with no sid, or a sid that isn't open
		assertRefused(get(ME).webSession(new MockHttpSession()).send());
		MockHttpSession unknown = new MockHttpSession();
		unknown.setAttribute(SessionManager.PARAM_SID, "not-a-session");
		assertRefused(get(ME).webSession(unknown).send());

		MockHttpSession web = signInWeb(clerk);
		SessionManager.get().kill(sid(web));
		assertRefused(get(ME).webSession(web).send());
	}

	@Test
	public void testSidOnlyFromTheHttpSession() {
		String sid = sid(signInWeb(clerk));
		assertTrue(SessionManager.get().isOpen(sid));

		assertRefused(get(ME + "?sid=" + sid).header(ApiCall.FETCH_SITE, SAME_ORIGIN).send());
		assertRefused(get(ME).header(ApiCall.FETCH_SITE, SAME_ORIGIN).header(SessionManager.PARAM_SID, sid).send());
		assertRefused(get(ME).header(ApiCall.FETCH_SITE, SAME_ORIGIN).cookie(SessionManager.COOKIE_SID, sid).send());

		// Nor beside an HTTP session that holds none, which is left holding none
		MockHttpSession empty = new MockHttpSession();
		assertRefused(get(ME + "?sid=" + sid).webSession(empty).header(SessionManager.PARAM_SID, sid)
				.cookie(SessionManager.COOKIE_SID, sid).send());
		assertNull(empty.getAttribute(SessionManager.PARAM_SID));
	}

	@Test
	public void testAuthorizationGoesTheTokenRoute() {
		MockHttpSession web = signInWeb(clerk);
		String token = signIn(clerk);
		assertEquals(200, get(ME).webSession(web).token(token).send().status());
		assertEquals(204, delete("/api/v1/auth/tokens/current").token(token).send().status());

		ApiResponse revoked = get(ME).webSession(web).token(token).send();
		assertEquals(revoked.toString(), 401, revoked.status());
		assertEquals("unauthenticated", revoked.code());
		assertEquals("Bearer error=\"invalid_token\"", revoked.header("WWW-Authenticate"));

		// A Bearer header goes by its token alone, even with none
		ApiResponse none = get(ME).webSession(web).header("Authorization", "Bearer").send();
		assertEquals(none.toString(), 401, none.status());
		assertEquals("Bearer", none.header("WWW-Authenticate"));

		// Another scheme, such as the Basic credentials a browser sends to a proxy in front, signs nothing in, and
		// the web session works beside it
		String basic = Base64.getEncoder()
				.encodeToString(("clerk:" + HealdanFixtures.PASSWORD).getBytes(StandardCharsets.UTF_8));
		assertEquals(200, get(ME).webSession(web).header("Authorization", "Basic " + basic).send().status());
		assertEquals(200, get(ME).webSession(web).header("Authorization", "").send().status());
		assertRefused(get(ME).session(web).header("Authorization", "Basic " + basic).send());

		// The session itself still works
		assertTrue(SessionManager.get().isOpen(sid(web)));
		assertEquals(200, get(ME).webSession(web).send().status());
	}

	@Test
	public void testRenewsTheSession() {
		MockHttpSession web = signInWeb(clerk);
		Session session = SessionManager.get().get(sid(web));
		// Within the test context's timeout of a minute
		Date before = new Date(System.currentTimeMillis() - 20000L);
		session.setLastRenew(before);

		assertEquals(200, get(ME).webSession(web).send().status());

		assertTrue(session.getLastRenew().after(before));
	}

	@Test
	public void testNoTokenOnThisRoute() throws Exception {
		MockHttpSession web = signInWeb(clerk);
		String sid = sid(web);
		String device = signIn(clerk);

		// While the request runs, CE's code finds the web session and the user's groups
		assertProbed(get(PROBE).webSession(web).send());
		assertNull(Probe.caller.token());
		assertEquals("clerk", Probe.caller.user().getUsername());
		assertEquals(sid, Probe.caller.session().getSid());
		assertEquals(sid, Probe.sid);
		User reloaded = UserDAO.get().findById(clerk.getId(), true);
		assertEquals(reloaded.getGroups().stream().map(Group::getName).collect(Collectors.toSet()), Probe.authorities);

		// Afterwards, nothing is left on the thread
		assertNull(SecurityContextHolder.getContext().getAuthentication());
		assertThrows(IllegalStateException.class, Caller::current);

		// The user's devices, none of them this one
		ApiResponse tokens = get("/api/v1/auth/tokens").webSession(web).send();
		assertEquals(tokens.toString(), 200, tokens.status());
		assertEquals(1, tokens.json().path("items").size());
		assertFalse(tokens.json().path("items").path(0).path("current").booleanValue());

		ApiResponse signOut = delete("/api/v1/auth/tokens/current").webSession(web).send();
		assertEquals(signOut.toString(), 403, signOut.status());
		assertEquals("token-only", signOut.code());
		assertTrue(SessionManager.get().isOpen(sid));
		assertEquals(200, get(ME).token(device).send().status());
	}

	@Test
	public void testDisabledAndDeletedUsers() throws Exception {
		MockHttpSession web = signInWeb(clerk);
		jdbc().update("update ld_user set ld_enabled = 0 where ld_id = ?", clerk.getId());
		UserDAO.get().evict(User.class, clerk.getId());

		// The session is still open: the filter checks the user itself
		assertTrue(SessionManager.get().isOpen(sid(web)));
		assertRefused(get(ME).webSession(web).send());

		User temp = fx.user("temp");
		MockHttpSession tempWeb = signInWeb(temp);
		assertEquals(200, get(ME).webSession(tempWeb).send().status());
		UserDAO.get().delete(temp.getId(), PersistentObject.DELETED_CODE_DEFAULT);
		assertRefused(get(ME).webSession(tempWeb).send());
	}

	// The web session is no device: a check-out one of the user's devices holds is held elsewhere for it, as for a
	// token that signed in with no device, and what it checks out no device holds
	@Test
	public void testNoDevice() throws Exception {
		MockHttpSession web = signInWeb(clerk);
		Document held = fx.upload(documents, "Held.docx", "one", clerk);
		Document free = fx.upload(documents, "Free.docx", "one", clerk);
		awaitAsyncWork();
		String mac = signInDevice("mac-1");
		assertEquals(200, post(checkout(held)).token(mac).json(Map.of("app", "LibreOffice")).send().status());

		ApiResponse seen = get(document(held)).webSession(web).send();
		assertEquals(seen.toString(), 200, seen.status());
		assertEquals("mac-1", seen.json().path("hold").path("device").path("id").textValue());
		assertFalse(seen.json().path("canEdit").booleanValue());
		assertEquals(200, get("/api/v1/folders/" + documents.getId() + "/children").webSession(web).send().status());

		assertHeldElsewhere(post(checkout(held)).webSession(web).send());
		assertHeldElsewhere(post(checkout(held)).webSession(web).json(Map.of("move", true)).send());
		assertHeldElsewhere(patch(document(held) + "/properties").webSession(web)
				.json(Map.of("values", Map.of("legacydocnum", "OLD-1"))).send());
		assertHeldElsewhere(delete(checkout(held)).webSession(web).send());
		assertHeldElsewhere(save(web, held));

		ApiResponse out = post(checkout(free)).webSession(web).json(Map.of("app", "LibreOffice")).send();
		assertEquals(out.toString(), 200, out.status());
		assertTrue(out.json().path("hold").path("device").isNull());
		assertTrue(out.json().path("canEdit").booleanValue());
		ApiResponse saved = save(web, free);
		assertEquals(saved.toString(), 201, saved.status());
		assertTrue(saved.json().path("hold").path("device").isNull());
		// So any of the user's devices may write to it
		assertEquals(200, get(document(free)).token(mac).send().status());
		assertTrue(get(document(free)).token(mac).send().json().path("canEdit").booleanValue());
	}

	private ApiResponse save(MockHttpSession web, Document d) throws Exception {
		return post(document(d) + "/versions").webSession(web)
				.part("version", ApiJson.mapper().writeValueAsString(Map.of("kind", "snapshot", "keepCheckedOut", true)))
				.file("file", "a.docx", "two".getBytes(StandardCharsets.UTF_8)).send();
	}

	private String signInDevice(String device) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("username", "clerk");
		body.put("password", HealdanFixtures.PASSWORD);
		body.put("name", "Laptop");
		body.put("device", Map.of("id", device));
		ApiResponse response = post("/api/v1/auth/tokens").json(body).send();
		assertEquals(response.toString(), 201, response.status());
		return response.json().path("token").textValue();
	}

	private static String document(Document d) {
		return "/api/v1/documents/" + d.getCustomId();
	}

	private static String checkout(Document d) {
		return document(d) + "/checkout";
	}

	/**
	 * @return the call, in the HTTP session and same-origin, without
	 *         {@code X-Healdan-Client}
	 */
	private static ApiCall sameOrigin(ApiCall call, MockHttpSession web) {
		return call.session(web).header(ApiCall.FETCH_SITE, SAME_ORIGIN);
	}

	/**
	 * @return how many Favorites the user of the HTTP session has
	 */
	private int favorites(MockHttpSession web) {
		ApiResponse list = get(FAVORITES).webSession(web).send();
		assertEquals(list.toString(), 200, list.status());
		return list.json().path("items").size();
	}

	private static void assertRefused(ApiResponse response) {
		assertEquals(response.toString(), 401, response.status());
		assertEquals(response.toString(), "unauthenticated", response.code());
	}

	/** A same-origin request that breaks the rule, which signing in again wouldn't mend */
	private static void assertRejected(ApiResponse response) {
		assertEquals(response.toString(), 403, response.status());
		assertEquals(response.toString(), WebSessionRoute.REFUSED, response.code());
		assertNull(response.toString(), response.header("WWW-Authenticate"));
	}

	private static void assertProbed(ApiResponse response) {
		assertEquals(response.toString(), 409, response.status());
		assertEquals(response.toString(), "probed", response.code());
	}

	private static void assertHeldElsewhere(ApiResponse response) {
		assertEquals(response.toString(), 409, response.status());
		assertEquals(response.toString(), "held-elsewhere", response.code());
		JsonNode device = response.json().path("hold").path("device");
		assertEquals(response.toString(), "mac-1", device.path("id").textValue());
	}
}
