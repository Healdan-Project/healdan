// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.dashboard.UsageSummary;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.matter.Matter;

/**
 * The Dashboard's routes (Admin app spec section 8.1), each answer checked against {@code openapi-admin.yaml}. Usage
 * comes from the summary in memory; no test makes a real start, so each works it out itself.
 */
public class AdminDashboardResourceTest extends ApiTestCase {

	private static final String USAGE = "/api/v1/admin/dashboard/usage";

	@Test
	public void testUsage() throws Exception {
		User boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder documents = FolderDAO.get()
				.findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents", Tenant.DEFAULT_ID, true)
				.get(0);
		fx.upload(documents, "letter.txt", "Dear Sir", boss);
		awaitAsyncWork();
		UsageSummary.get().refreshNow();

		ApiResponse response = get(USAGE).webSession(signInWeb(boss)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode usage = response.json();
		Instant computedAt = Instant.parse(usage.path("computedAt").textValue());
		assertTrue(computedAt.toString(), computedAt.isAfter(Instant.now().minusSeconds(600)));
		assertEquals(1, usage.path("documents").path("total").asLong());
		assertEquals(1, usage.path("documents").path("last7Days").asLong());
		assertEquals(8, usage.path("storage").path("total").asLong());
		assertEquals(1, usage.path("matters").path("total").asLong());
		assertEquals(1, usage.path("clients").path("total").asLong());
		// admin and boss
		assertEquals(2, usage.path("people").path("total").asLong());
		assertEquals(1, usage.path("signedIn").asInt());

		JsonNode days = usage.path("filedPerDay");
		assertEquals(30, days.size());
		LocalDate first = LocalDate.parse(days.path(0).path("date").textValue());
		assertEquals(first.plusDays(29).toString(), days.path(29).path("date").textValue());
		long filed = 0;
		for (JsonNode day : days)
			filed += day.path("count").asLong();
		assertEquals(1, filed);

		JsonNode stores = usage.path("stores");
		assertEquals(1, stores.size());
		assertEquals(store.getId(), stores.path(0).path("id").asLong());
		assertEquals("Client Work", stores.path(0).path("name").textValue());
		assertEquals(1, stores.path(0).path("documents").asLong());
		assertTrue(stores.path(0).path("backend").isNull());

		JsonNode busiest = usage.path("busiestMatters");
		assertEquals(1, busiest.size());
		assertEquals(matter.getId(), busiest.path(0).path("id").asLong());
		assertEquals("1001.1", busiest.path(0).path("number").textValue());
		assertEquals("Charter Dispute", busiest.path(0).path("name").textValue());
		assertEquals(1, busiest.path(0).path("filed").asLong());
	}

	// Until the summary is worked out once, an empty one with no time, which the contract allows too
	@Test
	public void testUsageBeforeTheFirstSummary() throws Exception {
		ApiResponse response = get(USAGE).webSession(signInWeb(fx.user("boss", "admin"))).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode usage = response.json();
		List<String> nulls = new ArrayList<>();
		for (String part : List.of("computedAt", "failedAt", "documents", "storage", "matters", "clients", "people"))
			if (usage.path(part).isNull())
				nulls.add(part);
		assertEquals(List.of("computedAt", "failedAt", "documents", "storage", "matters", "clients", "people"), nulls);
		assertEquals(0, usage.path("filedPerDay").size());
		assertEquals(0, usage.path("stores").size());
		assertEquals(0, usage.path("busiestMatters").size());
		assertEquals(1, usage.path("signedIn").asInt());
	}

	// A refresh that failed shows as failedAt beside the last summary, with no error's text
	@Test
	public void testUsageAfterAFailedRefresh() throws Exception {
		MockHttpSession boss = signInWeb(fx.user("boss", "admin"));
		fx.client(1001, "Ashcombe");
		UsageSummary.get().refreshNow();
		jdbc().update("alter table hd_client rename to hd_client_away");
		try {
			assertThrows(RuntimeException.class, () -> UsageSummary.get().refreshNow());
		} finally {
			jdbc().update("alter table hd_client_away rename to hd_client");
		}

		ApiResponse response = get(USAGE).webSession(boss).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode usage = response.json();
		assertTrue(usage.path("computedAt").isTextual());
		Instant failedAt = Instant.parse(usage.path("failedAt").textValue());
		assertFalse(failedAt.isBefore(Instant.parse(usage.path("computedAt").textValue())));
		assertEquals(1, usage.path("clients").path("total").asLong());
		assertFalse(response.text(), response.text().contains("hd_client"));

		// Worked out again: no failure
		UsageSummary.get().refreshNow();
		assertTrue(get(USAGE).webSession(boss).send().json().path("failedAt").isNull());
	}

	@Test
	public void testNonAdministrator403() throws Exception {
		MockHttpSession clerk = signInWeb(fx.user("clerk"));

		ApiResponse response = get(USAGE).webSession(clerk).send();

		assertEquals(response.toString(), 403, response.status());
		assertEquals("admins-only", response.code());
	}

	@Test
	public void testDeviceToken403() throws Exception {
		ApiResponse response = get(USAGE).token(signInAdmin()).send();

		assertEquals(response.toString(), 403, response.status());
		assertEquals("web-session-only", response.code());
	}
}
