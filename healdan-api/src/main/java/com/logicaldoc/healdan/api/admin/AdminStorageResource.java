// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageLimits;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.admin.StorageView.StorageSettings;
import com.logicaldoc.healdan.api.admin.StorageView.StuckFile;
import com.logicaldoc.healdan.api.model.FieldError;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code /v1/admin/storage}: the storage settings, the holding period, the read cache and parallel transfers, and the
 * files with no Store the mover gave up on, with Retry (Admin app spec section 8.6), through the storage port
 * ({@link StoragePort}), which records each change itself. Without the storage module, each answers
 * {@code 503 storage-unavailable}, after a body that isn't the JSON expected and before anything else.
 */
@Component
@Path("/v1/admin/storage")
@AdminRoute(AdminArea.STORAGE)
@Produces(MediaType.APPLICATION_JSON)
public class AdminStorageResource {

	/**
	 * The storage settings, {@code openapi-admin.yaml}'s {@code StorageSettingsChange}, read as JSON, so that a value
	 * that isn't a whole number is {@code 400} and one left out {@code 422}.
	 */
	public record SettingsBody(JsonNode holdDays, JsonNode cacheGb, JsonNode transfers) {
	}

	/**
	 * @return the settings, and each one's limits
	 */
	@GET
	@Path("settings")
	public StorageSettings settings() {
		return settings(StoragePort.get().require());
	}

	/**
	 * Saves the three settings, each checked against its limits before any is written.
	 *
	 * @param body the settings
	 * @return them, as saved
	 */
	@PUT
	@Path("settings")
	@Consumes(MediaType.APPLICATION_JSON)
	public StorageSettings saveSettings(SettingsBody body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the settings as a JSON object.");
		wholeNumber(body.holdDays(), "holdDays");
		wholeNumber(body.cacheGb(), "cacheGb");
		wholeNumber(body.transfers(), "transfers");
		StorageAdministration port = StoragePort.get().require();
		StorageLimits limits = port.limits();
		List<FieldError> fields = new ArrayList<>();
		check(fields, "holdDays", body.holdDays(), limits.maxHoldDays(), "The holding period is from 1 to %d days.");
		check(fields, "cacheGb", body.cacheGb(), limits.maxCacheGb(), "The read cache is from 1 to %d GB.");
		check(fields, "transfers", body.transfers(), limits.maxTransfers(), "Parallel transfers are from 1 to %d.");
		if (!fields.isEmpty())
			throw ApiProblem.validation(fields);

		StorageSettingsInfo settings = new StorageSettingsInfo(body.holdDays().intValue(), body.cacheGb().intValue(),
				body.transfers().intValue());
		StoragePort.run("Can't save the storage settings", () -> {
			port.saveSettings(settings, Caller.current().user());
			return null;
		});
		return settings(port);
	}

	/**
	 * @return the files with no Store the mover gave up on, the newest documents first, at most 1,000
	 */
	@GET
	@Path("stuck-files")
	public List<StuckFile> stuck() {
		StorageAdministration port = StoragePort.get().require();
		return StoragePort.run("Can't list the stuck files with no Store", () -> port.stuck(0)).stream()
				.map(StuckFile::of).toList();
	}

	/**
	 * Puts the stuck files with no Store back to waiting, and wakes the mover. With none stuck it changes nothing.
	 *
	 * @return {@code 204}
	 */
	@POST
	@Path("stuck-files/retry")
	public Response retry() {
		StorageAdministration port = StoragePort.get().require();
		StoragePort.run("Can't retry the stuck files with no Store", () -> {
			port.retry(0, Caller.current().user());
			return null;
		});
		return Response.noContent().build();
	}

	private static StorageSettings settings(StorageAdministration port) {
		StorageOverview overview = StoragePort.run("Can't read the storage settings", port::overview);
		return StorageSettings.of(overview.on(), overview.settings(), port.limits());
	}

	/**
	 * @throws ApiProblem {@code 400 bad-request} for a setting sent that isn't a whole number
	 */
	private static void wholeNumber(JsonNode value, String field) {
		if (value != null && !value.isNull() && !value.isIntegralNumber())
			throw ApiProblem.badRequest("Give %s as a whole number.".formatted(field));
	}

	/**
	 * Adds the setting's error: {@code required} when it's missing, {@code out-of-range} when it's below 1 or above its
	 * maximum
	 */
	private static void check(List<FieldError> fields, String field, JsonNode value, int max, String range) {
		if (value == null || value.isNull())
			fields.add(new FieldError(field, "required", "Give the setting a value."));
		else if (!value.canConvertToInt() || value.intValue() < 1 || value.intValue() > max)
			fields.add(new FieldError(field, "out-of-range", range.formatted(max)));
	}
}
