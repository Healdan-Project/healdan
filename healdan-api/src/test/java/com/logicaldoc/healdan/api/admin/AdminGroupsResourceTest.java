// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;

/**
 * {@code GET /v1/admin/groups} (Admin app spec section 8.6), every answer checked against
 * {@code openapi-admin.yaml}: the live groups by name, without people's own, searched by part of the name and paged.
 */
public class AdminGroupsResourceTest extends ApiTestCase {

	private static final String GROUPS = "/api/v1/admin/groups";

	private MockHttpSession web;

	@Before
	public void prepare() throws Exception {
		web = signInWeb(fx.user("boss", "admin"));
		fx.user("carol");
		Group partners = fx.group("Partners");
		partners.setDescription("The firm's partners");
		GroupDAO.get().store(partners);
		fx.group("Partners' Assistants");
		fx.group("IT_Support");
		fx.group("ITXSupport");
		fx.group("100% Club");
		fx.group("Clerks");
		GroupDAO.get().delete(fx.group("Old Partners").getId());
	}

	@Test
	public void testEveryGroupByName() throws Exception {
		ApiResponse response = get(GROUPS + "?limit=200").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode body = response.json();
		List<String> names = names(body);
		// Live groups only, not people's own, by name ignoring case
		assertTrue(names.containsAll(List.of("100% Club", "admin", "Clerks", "guest", "IT_Support", "ITXSupport",
				"Partners", "Partners' Assistants")));
		assertTrue(names.stream().noneMatch(name -> name.startsWith("_user_") || name.startsWith("Old Partners")));
		List<String> sorted = new ArrayList<>(names);
		sorted.sort(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)));
		assertEquals(sorted, names);
		assertEquals(names.size(), body.path("total").asLong());
		assertEquals(0, body.path("offset").asInt());
		assertEquals(200, body.path("limit").asInt());

		JsonNode partners = body.path("items").get(names.indexOf("Partners"));
		Group stored = GroupDAO.get().findByName("Partners", Tenant.DEFAULT_ID);
		assertEquals(stored.getId(), partners.path("id").asLong());
		assertEquals("The firm's partners", partners.path("description").textValue());
		// LogicalDOC keeps none as an empty description
		assertEquals("", GroupDAO.get().findByName("Clerks", Tenant.DEFAULT_ID).getDescription());
		assertTrue(body.path("items").get(names.indexOf("Clerks")).path("description").isNull());

		// A blank q is no q
		assertEquals(names, names(get(GROUPS + "?limit=200&q=%20%20").webSession(web).send().json()));
	}

	@Test
	public void testSearch() throws Exception {
		// Part of the name, in any case, the whitespace at either end left out
		assertEquals(List.of("Partners", "Partners' Assistants"), search(" PARTNER "));
		assertEquals(List.of("Partners' Assistants"), search("assist"));
		// % and _ as they are
		assertEquals(List.of("IT_Support"), search("_"));
		assertEquals(List.of("100% Club"), search("%"));
		assertEquals(List.of(), search("_user_"));
		JsonNode body = get(GROUPS + "?q=" + encode("partner")).webSession(web).send().json();
		assertEquals(2, body.path("total").asLong());
	}

	@Test
	public void testPaging() throws Exception {
		List<String> all = names(get(GROUPS + "?limit=200").webSession(web).send().json());

		JsonNode page = get(GROUPS + "?offset=2&limit=3").webSession(web).send().json();
		assertEquals(all.subList(2, 5), names(page));
		assertEquals(2, page.path("offset").asInt());
		assertEquals(3, page.path("limit").asInt());
		assertEquals(all.size(), page.path("total").asLong());
		// The default page is 50
		assertEquals(50, get(GROUPS).webSession(web).send().json().path("limit").asInt());
		// Past the end
		page = get(GROUPS + "?offset=500").webSession(web).send().json();
		assertEquals(0, page.path("items").size());
		assertEquals(all.size(), page.path("total").asLong());

		for (String query : List.of("?offset=-1", "?limit=0", "?limit=201", "?limit=many"))
			assertProblem(get(GROUPS + query).webSession(web).send(), 400, "bad-request");
	}

	@Test
	public void testNonAdministrator403() throws Exception {
		MockHttpSession clerk = signInWeb(fx.user("clerk"));

		assertProblem(get(GROUPS).webSession(clerk).send(), 403, "admins-only");
		assertProblem(get(GROUPS).token(signInAdmin()).send(), 403, "web-session-only");
		assertFalse(get(GROUPS).webSession(web).send().json().path("items").isEmpty());
	}

	private List<String> search(String q) {
		ApiResponse response = get(GROUPS + "?q=" + encode(q)).webSession(web).send();
		assertEquals(response.toString(), 200, response.status());
		return names(response.json());
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static List<String> names(JsonNode list) {
		List<String> names = new ArrayList<>();
		for (JsonNode group : list.path("items"))
			names.add(group.path("name").textValue());
		return names;
	}

	private static void assertProblem(ApiResponse response, int status, String code) {
		assertEquals(response.toString(), status, response.status());
		assertEquals(response.toString(), code, response.code());
	}
}
