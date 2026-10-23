package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.SystemInfo;
import com.logicaldoc.healdan.setting.HealdanSettings;

public class ServerResourceTest extends ApiTestCase {

	@Test
	public void testServerInfo() {
		ApiResponse response = get("/api/v1/server").send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode info = response.json();
		assertEquals("Healdan", info.path("product").textValue());
		assertEquals(SystemInfo.get().getRelease(), info.path("version").textValue());
		assertEquals("v1", info.path("apiVersion").textValue());
		assertEquals("Matter", info.path("labels").path("matter").textValue());
		assertEquals("Matters", info.path("labels").path("matters").textValue());
		assertEquals(2, info.path("labels").size());
		assertEquals(1, info.path("signIn").size());
		assertEquals("password", info.path("signIn").path(0).textValue());
	}

	@Test
	public void testLabelsFollowSettings() {
		HealdanSettings.get().set(HealdanSettings.MATTER_LABEL, "Project");
		HealdanSettings.get().set(HealdanSettings.MATTER_LABEL_PLURAL, "Projects");

		JsonNode labels = get("/api/v1/server").send().json().path("labels");

		assertEquals("Project", labels.path("matter").textValue());
		assertEquals("Projects", labels.path("matters").textValue());
	}
}
