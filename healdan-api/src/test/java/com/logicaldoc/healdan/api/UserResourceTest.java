package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;

/**
 * {@code GET /v1/users?q=}, for user fields such as Author (spec section
 * 6.1).
 */
public class UserResourceTest extends ApiTestCase {

	private static final String USERS = "/api/v1/users";

	private static List<String> usernames(ApiResponse response) {
		List<String> usernames = new ArrayList<>();
		for (JsonNode item : response.json().path("items"))
			usernames.add(item.path("username").textValue());
		return usernames;
	}

	private void rename(User user, String firstName, String lastName) {
		jdbc().update("update ld_user set ld_firstname = ?, ld_name = ? where ld_id = ?", firstName, lastName,
				user.getId());
		UserDAO.get().evict(User.class, user.getId());
	}

	@Test
	public void testFind() throws Exception {
		User clerk = fx.user("clerk");
		rename(clerk, "Carla", "Lerner");
		User jsmith = fx.user("jsmith");
		rename(jsmith, "Jane", "Smith");
		User off = fx.user("clerkoff");
		jdbc().update("update ld_user set ld_enabled = 0 where ld_id = ?", off.getId());
		String token = signIn(clerk);

		ApiResponse byUsername = get(USERS + "?q=cle").token(token).send();
		assertEquals(byUsername.toString(), 200, byUsername.status());
		// A disabled user is left out
		assertEquals(List.of("clerk"), usernames(byUsername));
		JsonNode hit = byUsername.json().path("items").path(0);
		assertEquals(clerk.getId(), hit.path("id").asLong());
		assertEquals("Carla Lerner", hit.path("name").textValue());

		assertEquals(List.of("jsmith"), usernames(get(USERS + "?q=JANE").token(token).send()));
		assertEquals(List.of("jsmith"), usernames(get(USERS + "?q=smi").token(token).send()));
		assertEquals(List.of("jsmith"), usernames(get(USERS + "?q=jane%20smith").token(token).send()));

		// _system matches by name, but isn't a person
		assertEquals(List.of(), usernames(get(USERS + "?q=system").token(token).send()));

		// A wildcard is a plain character
		assertEquals(List.of(), usernames(get(USERS + "?q=%25").token(token).send()));

		// At most 20, by name
		for (int i = 25; i >= 1; i--)
			fx.user("member%02d".formatted(i));
		List<String> members = usernames(get(USERS + "?q=member").token(token).send());
		assertEquals(20, members.size());
		assertEquals("member01", members.get(0));
		assertEquals("member20", members.get(19));
	}

	@Test
	public void testBlankQuery() throws Exception {
		String token = signIn(fx.user("clerk"));

		for (String path : List.of(USERS, USERS + "?q=", USERS + "?q=%20%20")) {
			ApiResponse response = get(path).token(token).send();
			assertEquals(path + ": " + response, 400, response.status());
			assertEquals("bad-request", response.code());
		}
	}
}
