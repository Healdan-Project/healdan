// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDetail;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDraft;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendList;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.admin.StorageView.AddedBackend;
import com.logicaldoc.healdan.api.admin.StorageView.Backend;
import com.logicaldoc.healdan.api.admin.StorageView.BackendOverview;
import com.logicaldoc.healdan.api.admin.StorageView.BackendTestResult;
import com.logicaldoc.healdan.api.admin.StorageView.KeyEvent;
import com.logicaldoc.healdan.api.admin.StorageView.PendingFiles;
import com.logicaldoc.healdan.api.admin.StorageView.RecoveryKey;
import com.logicaldoc.healdan.api.model.FieldError;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/**
 * {@code /v1/admin/backends}: the storage backends, everything the Classic Interface's Storage tab does with them
 * (Admin app spec section 8.6): add a local folder or an S3 bucket, Test before saving, edit, Test, remove, and an
 * encrypted backend's recovery key shown, confirmed and entered, with its history. The work and its rules are the
 * storage port's ({@link StoragePort}), which records each change itself; its refusals are {@code 409 refused} with
 * the tab's sentence, and a missing secret key file {@code 409 secret-key-missing}. Without the storage module, each
 * route answers {@code 503 storage-unavailable}, after a body that isn't the JSON expected and before anything else;
 * then a backend named in the path that doesn't exist is {@code 404}, as is any while storage backends are off. What a
 * form sends is checked here first ({@code 422 validation}), as a draft of the kind it names.
 * <p>
 * No answer holds an S3 secret, only whether one is stored; a recovery key is answered only by an encrypted backend's
 * add and by Show Recovery Key; and no problem quotes a secret, a recovery key or its last group as typed.
 */
@Component
@Path("/v1/admin/backends")
@AdminRoute(AdminArea.STORAGE)
@Produces(MediaType.APPLICATION_JSON)
public class AdminBackendsResource {

	/** A local folder */
	static final String LOCAL = "local";

	/** An S3 bucket */
	static final String S3 = "s3";

	/**
	 * A new backend, {@code openapi-admin.yaml}'s {@code NewBackend}: a member left out is null, as one sent as null.
	 */
	public record NewBackend(String kind, String name, String path, String endpoint, String region, String bucket,
			String prefix, Boolean pathStyle, String accessKey, String secret, Boolean encrypted, Boolean newKey) {

		/** @return its kind and name, never its secret or its access key */
		@Override
		public String toString() {
			return "NewBackend[" + kind + " " + name + "]";
		}
	}

	/**
	 * Settings to test, {@code openapi-admin.yaml}'s {@code BackendTrial}: a member left out is null.
	 */
	public record BackendTrial(Long id, String kind, String name, String path, String endpoint, String region,
			String bucket, String prefix, Boolean pathStyle, String accessKey, String secret, Boolean encrypted) {

		/** @return its kind and name, never its secret or its access key */
		@Override
		public String toString() {
			return "BackendTrial[" + id + " " + kind + " " + name + "]";
		}
	}

	/**
	 * A backend's new settings, {@code openapi-admin.yaml}'s {@code BackendChange}, read as JSON, so that a member left
	 * out (null here) is told apart from one sent as null ({@code NullNode}), and with {@code kind} and
	 * {@code encrypted}, which never change, so those are {@code 422 not-editable} rather than an unknown member's
	 * {@code 400}.
	 */
	public record BackendChange(JsonNode name, JsonNode path, JsonNode endpoint, JsonNode region, JsonNode bucket,
			JsonNode prefix, JsonNode pathStyle, JsonNode accessKey, JsonNode secret, JsonNode newKey, JsonNode kind,
			JsonNode encrypted) {

		/** @return which members were sent, never their values */
		@Override
		public String toString() {
			return "BackendChange[" + (name != null ? "name " : "") + (secret != null ? "secret" : "") + "]";
		}
	}

	/**
	 * A recovery key's last group, {@code openapi-admin.yaml}'s {@code RecoveryKeyConfirmation}.
	 */
	public record Confirmation(String lastGroup) {

		/** @return never the group */
		@Override
		public String toString() {
			return "Confirmation";
		}
	}

	/**
	 * A recovery key, {@code openapi-admin.yaml}'s {@code RecoveryKeyEntry}.
	 */
	public record Entry(String recoveryKey) {

		/** @return never the key */
		@Override
		public String toString() {
			return "Entry";
		}
	}

	/**
	 * @return whether storage backends are on, every backend, Original first, then by id, and the files with no Store
	 */
	@GET
	public BackendOverview list() {
		StorageAdministration port = StoragePort.get().require();
		// Every backend and the files with no Store, read together
		BackendList list = StoragePort.run("Can't read the storage backends", port::backends);
		List<Backend> backends = list.backends().stream()
				.sorted(Comparator.comparing((BackendDetail b) -> b.id() != AdminStoresResource.ORIGINAL)
						.thenComparingLong(BackendDetail::id))
				.map(Backend::of).toList();
		return new BackendOverview(list.on(), backends,
				new PendingFiles(list.otherFiles(), list.otherBytes(), list.otherStuck()));
	}

	/**
	 * Adds a backend, once Test passes on its settings, as the Storage tab's Add does. An encrypted backend's answer
	 * carries its recovery key, a showing the port records.
	 *
	 * @param body the new backend
	 * @param uri the request's address, for the backend's
	 * @return {@code 201} with the backend, its address as {@code Location}
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response add(NewBackend body, @jakarta.ws.rs.core.Context UriInfo uri) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new backend as a JSON object.");
		StorageAdministration port = StoragePort.get().require();
		List<FieldError> fields = new ArrayList<>();
		String kind = StringUtils.strip(body.kind());
		if (StringUtils.isEmpty(kind))
			fields.add(new FieldError("kind", "required", "Choose the kind of backend: a local folder or S3."));
		else if (!LOCAL.equals(kind) && !S3.equals(kind))
			fields.add(new FieldError("kind", "invalid", "A backend is a local folder, local, or an S3 bucket, s3."));
		required(fields, "name", body.name(), "Give the backend a name.");
		if (LOCAL.equals(kind)) {
			required(fields, "path", body.path(), "Give the folder's whole path.");
			otherKind(fields, "endpoint", body.endpoint(), LOCAL);
			otherKind(fields, "region", body.region(), LOCAL);
			otherKind(fields, "bucket", body.bucket(), LOCAL);
			otherKind(fields, "prefix", body.prefix(), LOCAL);
			otherKind(fields, "pathStyle", body.pathStyle(), LOCAL);
			otherKind(fields, "accessKey", body.accessKey(), LOCAL);
			otherKind(fields, "secret", body.secret(), LOCAL);
		} else if (S3.equals(kind)) {
			otherKind(fields, "path", body.path(), S3);
			required(fields, "endpoint", body.endpoint(), "Give the S3 service's address.");
			required(fields, "region", body.region(), "Give the bucket's region.");
			required(fields, "bucket", body.bucket(), "Give the bucket.");
			required(fields, "accessKey", body.accessKey(), "Give the access key ID.");
			required(fields, "secret", body.secret(), "Give the secret.");
		}
		if (!fields.isEmpty())
			throw ApiProblem.validation(fields);

		boolean s3 = S3.equals(kind);
		BackendDraft draft = s3
				? new BackendDraft(kind, body.name(), null, body.endpoint(), body.region(), body.bucket(),
						StringUtils.defaultString(body.prefix()), Boolean.TRUE.equals(body.pathStyle()),
						body.accessKey(), body.encrypted())
				: new BackendDraft(kind, body.name(), body.path(), null, null, null, null, false, null,
						body.encrypted());
		User by = Caller.current().user();
		var added = StoragePort.run("Can't add a storage backend",
				() -> port.add(draft, s3 ? body.secret() : null, Boolean.TRUE.equals(body.newKey()), by));
		String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/admin/backends/{id}")
				.build(added.backend().id()).toString();
		return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location)
				.entity(new AddedBackend(Backend.of(added.backend()), added.recoveryKey())).build();
	}

	/**
	 * Test before saving: the settings as typed, saved nowhere. A test that fails isn't a refusal: its problems are
	 * the answer, a setting the add would refuse among them. The settings of the other kind are left aside.
	 *
	 * @param body the settings
	 * @return what the test found, nothing when it passed
	 */
	@POST
	@Path("test")
	@Consumes(MediaType.APPLICATION_JSON)
	public BackendTestResult testNew(BackendTrial body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the settings as a JSON object.");
		StorageAdministration port = StoragePort.get().require();
		String kind = StringUtils.strip(body.kind());
		boolean local = LOCAL.equals(kind);
		boolean s3 = S3.equals(kind);
		BackendDraft draft = new BackendDraft(kind, body.name(), s3 ? null : body.path(),
				local ? null : body.endpoint(), local ? null : body.region(), local ? null : body.bucket(),
				local ? null : body.prefix(), !local && Boolean.TRUE.equals(body.pathStyle()),
				local ? null : body.accessKey(), body.encrypted());
		long id = body.id() != null ? body.id() : 0;
		return new BackendTestResult(StoragePort.run("Can't test a storage backend's settings",
				() -> port.testNew(id, draft, local ? null : body.secret())));
	}

	/**
	 * @param id a backend
	 * @return it, as its page and its Edit window show it
	 */
	@GET
	@Path("{id: [0-9]+}")
	public Backend get(@PathParam("id") long id) {
		return Backend.of(StoragePort.backend(StoragePort.get().require(), id));
	}

	/**
	 * Saves a backend's settings, as the Storage tab's Edit does. A member left out stays as it is; the port is sent
	 * the whole draft, the stored settings with the change, but for an endpoint left out, which the port keeps from
	 * its row, as the answer this reads holds only its scheme, host and port. Checks, in order: the body's types; the storage module;
	 * the backend; then each member ({@code 422}): {@code kind} and {@code encrypted}, which never change, sent with a
	 * value; for Original, every member but {@code name} sent with a value; a member of the other kind sent with a
	 * value; the name, or a setting the kind needs, sent as null or blank. Then the port's refusals.
	 *
	 * @param id a backend
	 * @param body what changes
	 * @return the backend, saved
	 */
	@PATCH
	@Path("{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Backend edit(@PathParam("id") long id, BackendChange body) {
		if (body == null)
			throw ApiProblem.badRequest("Send what changes as a JSON object.");
		for (JsonNode text : new JsonNode[] { body.name(), body.path(), body.endpoint(), body.region(), body.bucket(),
				body.prefix(), body.accessKey(), body.secret() })
			if (sent(text) && !text.isTextual())
				throw ApiProblem.badRequest("Give each setting as text.");
		if (sent(body.pathStyle()) && !body.pathStyle().isBoolean())
			throw ApiProblem.badRequest("Give pathStyle as true or false.");
		if (sent(body.newKey()) && !body.newKey().isBoolean())
			throw ApiProblem.badRequest("Give newKey as true or false.");
		StorageAdministration port = StoragePort.get().require();
		BackendDetail stored = StoragePort.backend(port, id);

		List<FieldError> fields = new ArrayList<>();
		notEditable(fields, "kind", body.kind(), "A backend's kind never changes.");
		notEditable(fields, "encrypted", body.encrypted(),
				"Whether a backend is encrypted is chosen when it is added, and never changes.");
		if (body.name() != null && blank(body.name()))
			fields.add(new FieldError("name", "required", "Give the backend a name."));
		boolean s3 = S3.equals(stored.kind());
		if (stored.original()) {
			String why = "Original's place comes from the server's configuration: only its name changes.";
			for (String member : List.of("path", "endpoint", "region", "bucket", "prefix", "pathStyle", "accessKey",
					"secret"))
				notEditable(fields, member, member(body, member), why);
		} else if (s3) {
			notEditable(fields, "path", body.path(), "An S3 bucket has no path.");
			needed(fields, "endpoint", body.endpoint(), "Give the S3 service's address.");
			needed(fields, "region", body.region(), "Give the bucket's region.");
			needed(fields, "bucket", body.bucket(), "Give the bucket.");
			needed(fields, "accessKey", body.accessKey(), "Give the access key ID.");
		} else {
			needed(fields, "path", body.path(), "Give the folder's whole path.");
			String why = "A local folder has no S3 settings.";
			for (String member : List.of("endpoint", "region", "bucket", "prefix", "pathStyle", "accessKey",
					"secret"))
				notEditable(fields, member, member(body, member), why);
		}
		if (!fields.isEmpty())
			throw ApiProblem.validation(fields);

		String name = text(body.name(), stored.name());
		BackendDraft draft;
		String secret = null;
		if (stored.original())
			draft = new BackendDraft(stored.kind(), name, stored.path(), null, null, null, null, false, null,
					stored.encrypted());
		else if (s3) {
			String prefix = body.prefix() == null ? stored.prefix()
					: body.prefix().isNull() ? "" : StringUtils.strip(body.prefix().textValue());
			// The stored endpoint is answered only in part, so one left out is left to the port, which keeps the row's
			draft = new BackendDraft(stored.kind(), name, null, text(body.endpoint(), null),
					text(body.region(), stored.region()), text(body.bucket(), stored.bucket()),
					StringUtils.defaultString(prefix),
					sent(body.pathStyle()) ? body.pathStyle().booleanValue() : stored.pathStyle(),
					text(body.accessKey(), stored.accessKey()), stored.encrypted());
			secret = sent(body.secret()) && !body.secret().textValue().isBlank() ? body.secret().textValue() : null;
		} else
			draft = new BackendDraft(stored.kind(), name, text(body.path(), stored.path()), null, null, null, null,
					false, null, stored.encrypted());
		// A new secret key matters only with a secret to seal
		boolean newKey = secret != null && sent(body.newKey()) && body.newKey().booleanValue();
		String typed = secret;
		User by = Caller.current().user();
		return Backend.of(StoragePort.run("Can't save storage backend #" + id,
				() -> port.save(id, draft, typed, newKey, by)));
	}

	/**
	 * Removes an empty backend, as the Storage tab's Remove does: never Original.
	 *
	 * @param id a backend
	 * @return {@code 204}
	 */
	@DELETE
	@Path("{id: [0-9]+}")
	public Response remove(@PathParam("id") long id) {
		StorageAdministration port = StoragePort.get().require();
		StoragePort.existing(port, id);
		StoragePort.run("Can't remove storage backend #" + id, () -> {
			port.remove(id, Caller.current().user());
			return null;
		});
		return Response.noContent().build();
	}

	/**
	 * Tests a saved backend in full, its marker and key included.
	 *
	 * @param id a backend
	 * @return what the test found, nothing when it passed
	 */
	@POST
	@Path("{id: [0-9]+}/test")
	public BackendTestResult test(@PathParam("id") long id) {
		StorageAdministration port = StoragePort.get().require();
		StoragePort.existing(port, id);
		return new BackendTestResult(StoragePort.run("Can't test storage backend #" + id, () -> port.test(id)));
	}

	/**
	 * Shows an encrypted backend's recovery key, a showing the port records.
	 *
	 * @param id a backend
	 * @return its recovery key
	 */
	@POST
	@Path("{id: [0-9]+}/recovery-key/show")
	public RecoveryKey showRecoveryKey(@PathParam("id") long id) {
		StorageAdministration port = StoragePort.get().require();
		StoragePort.existing(port, id);
		return new RecoveryKey(StoragePort.run("Can't show storage backend #" + id + "'s recovery key",
				() -> port.showRecoveryKey(id, Caller.current().user())));
	}

	/**
	 * Confirms an encrypted backend's recovery key by its last group, so that Stores can be given the backend.
	 *
	 * @param id a backend
	 * @param body the last group, as typed
	 * @return the backend, after
	 */
	@POST
	@Path("{id: [0-9]+}/recovery-key/confirm")
	@Consumes(MediaType.APPLICATION_JSON)
	public Backend confirmRecoveryKey(@PathParam("id") long id, Confirmation body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the last group as a JSON object.");
		StorageAdministration port = StoragePort.get().require();
		StoragePort.existing(port, id);
		if (StringUtils.isBlank(body.lastGroup()))
			throw ApiProblem.validation(List.of(new FieldError("lastGroup", "required",
					"Type the recovery key's last four characters.")));
		StoragePort.run("Can't confirm storage backend #" + id + "'s recovery key", () -> {
			port.confirmRecoveryKey(id, body.lastGroup(), Caller.current().user());
			return null;
		});
		return Backend.of(StoragePort.backend(port, id));
	}

	/**
	 * Puts an encrypted backend's key file back from its recovery key, which must decrypt its check object.
	 *
	 * @param id a backend
	 * @param body the recovery key, as typed
	 * @return the backend, after
	 */
	@POST
	@Path("{id: [0-9]+}/recovery-key/enter")
	@Consumes(MediaType.APPLICATION_JSON)
	public Backend enterRecoveryKey(@PathParam("id") long id, Entry body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the recovery key as a JSON object.");
		StorageAdministration port = StoragePort.get().require();
		StoragePort.existing(port, id);
		if (StringUtils.isBlank(body.recoveryKey()))
			throw ApiProblem.validation(
					List.of(new FieldError("recoveryKey", "required", "Type or paste the recovery key.")));
		StoragePort.run("Can't enter storage backend #" + id + "'s recovery key", () -> {
			port.enterRecoveryKey(id, body.recoveryKey(), Caller.current().user());
			return null;
		});
		return Backend.of(StoragePort.backend(port, id));
	}

	/**
	 * @param id a backend
	 * @return every showing and entering of its recovery key, newest first
	 */
	@GET
	@Path("{id: [0-9]+}/key-events")
	public List<KeyEvent> keyEvents(@PathParam("id") long id) {
		StorageAdministration port = StoragePort.get().require();
		StoragePort.existing(port, id);
		return StoragePort.run("Can't read storage backend #" + id + "'s key history", () -> port.keyEvents(id))
				.stream().map(KeyEvent::of).toList();
	}

	/** A member sent with a value: not left out, and not null */
	private static boolean sent(JsonNode node) {
		return node != null && !node.isNull();
	}

	/** A member sent as null, or as text that is blank */
	private static boolean blank(JsonNode node) {
		return node.isNull() || node.textValue().isBlank();
	}

	/** @return the text sent, the whitespace at either end taken off; the stored value for one left out */
	private static String text(JsonNode node, String stored) {
		return node != null ? StringUtils.strip(node.textValue()) : stored;
	}

	/** @return the member of a change, by its name */
	private static JsonNode member(BackendChange body, String name) {
		return switch (name) {
		case "path" -> body.path();
		case "endpoint" -> body.endpoint();
		case "region" -> body.region();
		case "bucket" -> body.bucket();
		case "prefix" -> body.prefix();
		case "pathStyle" -> body.pathStyle();
		case "accessKey" -> body.accessKey();
		case "secret" -> body.secret();
		default -> throw new IllegalArgumentException(name);
		};
	}

	/** {@code required} for a setting missing, null or blank */
	private static void required(List<FieldError> fields, String field, String value, String detail) {
		if (StringUtils.isBlank(value))
			fields.add(new FieldError(field, "required", detail));
	}

	/** {@code invalid} for a member of the other kind sent with a value */
	private static void otherKind(List<FieldError> fields, String field, Object value, String kind) {
		if (value != null)
			fields.add(new FieldError(field, "invalid",
					LOCAL.equals(kind) ? "A local folder has no S3 settings." : "An S3 bucket has no path."));
	}

	/** {@code not-editable} for a member sent with a value that the backend's edit doesn't take */
	private static void notEditable(List<FieldError> fields, String field, JsonNode value, String detail) {
		if (sent(value))
			fields.add(new FieldError(field, "not-editable", detail));
	}

	/** {@code required} for a setting the kind needs, sent as null or blank; one left out stays */
	private static void needed(List<FieldError> fields, String field, JsonNode value, String detail) {
		if (value != null && blank(value))
			fields.add(new FieldError(field, "required", detail));
	}
}
