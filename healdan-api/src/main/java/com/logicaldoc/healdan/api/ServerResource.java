package com.logicaldoc.healdan.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.SystemInfo;
import com.logicaldoc.healdan.api.model.ServerInfo;
import com.logicaldoc.healdan.setting.HealdanSettings;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /v1/server}: what a client checks first, before it signs in
 * (spec section 6.1).
 */
@Component
@Path("/v1/server")
@Produces(MediaType.APPLICATION_JSON)
public class ServerResource {

	@GET
	@Public
	public ServerInfo get() {
		HealdanSettings settings = HealdanSettings.get();
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("matter", settings.matterLabel());
		labels.put("matters", settings.matterLabelPlural());
		return new ServerInfo("Healdan", SystemInfo.get().getRelease(), "v1", labels, List.of("password"));
	}
}
