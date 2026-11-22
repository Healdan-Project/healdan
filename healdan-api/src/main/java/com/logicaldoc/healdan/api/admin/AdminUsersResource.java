// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static com.logicaldoc.healdan.api.admin.PeopleRequests.checkGroupIds;
import static com.logicaldoc.healdan.api.admin.PeopleRequests.checkText;
import static com.logicaldoc.healdan.api.admin.PeopleRequests.merged;
import static com.logicaldoc.healdan.api.admin.PeopleRequests.run;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.Page;
import com.logicaldoc.healdan.admin.people.Created;
import com.logicaldoc.healdan.admin.people.PeopleFilter;
import com.logicaldoc.healdan.admin.people.PeopleSort;
import com.logicaldoc.healdan.admin.people.Person;
import com.logicaldoc.healdan.admin.people.PersonRow;
import com.logicaldoc.healdan.admin.people.PersonStatus;
import com.logicaldoc.healdan.admin.people.ResetResult;
import com.logicaldoc.healdan.admin.people.SignedOut;
import com.logicaldoc.healdan.admin.people.UserAdministration;
import com.logicaldoc.healdan.admin.people.UserDraft;
import com.logicaldoc.healdan.admin.people.UserForm;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.Paging;
import com.logicaldoc.healdan.api.admin.PeopleView.CreatedUser;
import com.logicaldoc.healdan.api.admin.PeopleView.SignedOutView;
import com.logicaldoc.healdan.api.admin.PeopleView.TemporaryPassword;
import com.logicaldoc.healdan.api.admin.PeopleView.UserCheckout;
import com.logicaldoc.healdan.api.admin.PeopleView.UserDevice;
import com.logicaldoc.healdan.api.admin.PeopleView.UserOptionsView;
import com.logicaldoc.healdan.api.admin.PeopleView.UserRow;
import com.logicaldoc.healdan.api.admin.PeopleView.UserView;
import com.logicaldoc.healdan.api.model.FieldError;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/**
 * {@code /v1/admin/users}: the people (Admin app spec section 8.7), through {@link UserAdministration}, which checks
 * every change's fields and rules itself, records each change in the administration record, and keeps an enabled
 * administrator under its guards: the Users list, the options New User and Edit offer, a person's page, New User,
 * Edit, Disable and Enable, Reset Password, Sign Out Everywhere, Clear Sign-In Block, a person's groups, their
 * devices and the documents they have checked out.
 * <p>
 * A person is an account of the caller's tenant that isn't deleted or a system account; anything else in the path is
 * {@code 404}. The checks come in the contract's order: the body ({@code 400}), the person ({@code 404}), the fields
 * ({@code 422}), then the rules ({@code 409 refused}). A temporary password is only in New User's and Reset
 * Password's answers, when it wasn't emailed.
 */
@Component
@Path("/v1/admin/users")
@AdminRoute(AdminArea.PEOPLE)
@Produces(MediaType.APPLICATION_JSON)
public class AdminUsersResource {

	/**
	 * A new person, {@code openapi-admin.yaml}'s {@code NewUser}: each member null when it wasn't sent.
	 */
	public record NewUser(String username, String firstName, String lastName, String email, String language,
			String timeZone, List<Long> groupIds, Boolean emailPassword) {
	}

	/**
	 * What Edit changes, {@code openapi-admin.yaml}'s {@code UserChange}, read as JSON so that a member left out (null
	 * here) is told apart from one sent as null ({@code NullNode}), and with the {@code username} it may not carry, so
	 * that one is {@code 422 not-editable} rather than an unknown member's {@code 400}.
	 */
	public record UserChange(JsonNode username, JsonNode firstName, JsonNode lastName, JsonNode email,
			JsonNode language, JsonNode timeZone) {
	}

	/**
	 * How a reset's temporary password reaches the person, {@code openapi-admin.yaml}'s {@code PasswordReset}.
	 *
	 * @param emailPassword true to email it; null when it wasn't sent
	 */
	public record PasswordReset(Boolean emailPassword) {
	}

	/**
	 * The groups a person is in, {@code openapi-admin.yaml}'s {@code UserGroups}.
	 *
	 * @param groupIds the groups; null when they weren't sent
	 */
	public record UserGroups(List<Long> groupIds) {
	}

	/**
	 * @param q part of a person's name, username or email; any case, the whitespace at either end left out
	 * @param groupId only the members of this group
	 * @param status only the people with this status
	 * @param sort the column to sort by, {@code name} by default
	 * @param order {@code asc}, the default, or {@code desc}
	 * @param offset how many people to skip
	 * @param limit the most to return
	 * @return a page of the people that match
	 */
	@GET
	public com.logicaldoc.healdan.api.model.Page<UserRow> list(@QueryParam("q") String q,
			@QueryParam("groupId") Long groupId, @QueryParam("status") String status, @QueryParam("sort") String sort,
			@QueryParam("order") String order, @QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		PeopleSort column = sort == null ? PeopleSort.NAME : PeopleSort.of(sort);
		if (column == null)
			throw ApiProblem.badRequest("Sort by name, username or lastSignIn.");
		boolean descending = ListOrder.descending(order);
		PersonStatus only = status == null ? null : PersonStatus.of(status);
		if (status != null && only == null)
			throw ApiProblem.badRequest("Give the status as active, disabled or blocked.");
		Paging paging = Paging.of(offset, limit);

		Page<PersonRow> page = service().list(new PeopleFilter(q, groupId, only), column, descending,
				paging.offset(), paging.limit(), caller());
		return new com.logicaldoc.healdan.api.model.Page<>(page.items().stream().map(UserRow::of).toList(),
				page.offset(), page.limit(), page.total());
	}

	/**
	 * Makes a password account, and answers it with its temporary password unless that was emailed. Checks, in
	 * order: the body and its groups ({@code 400}), the fields ({@code 422}), then a group that isn't one the groups
	 * list lists and Healdan's rules ({@code 409 refused}).
	 *
	 * @param body the new person
	 * @param uri the request's address, for the person's
	 * @return {@code 201} with the person, and their address as {@code Location}
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(NewUser body, @Context UriInfo uri) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new person as a JSON object.");
		checkGroupIds(body.groupIds());
		if (body.emailPassword() == null) {
			// The service checks the rest once it can make the account; until then, what's missing
			List<FieldError> fields = new ArrayList<>();
			missing(fields, "username", body.username(), UserForm.NO_USERNAME);
			missing(fields, "firstName", body.firstName(), UserForm.NO_FIRST_NAME);
			missing(fields, "lastName", body.lastName(), UserForm.NO_LAST_NAME);
			fields.add(new FieldError("emailPassword", "required",
					"Say whether to email the temporary password, or show it."));
			throw ApiProblem.validation(fields);
		}

		UserDraft draft = new UserDraft(body.username(), body.firstName(), body.lastName(), body.email(),
				body.language(), body.timeZone(), body.groupIds() != null ? body.groupIds() : List.of());
		User actor = caller();
		Created created = run("Can't add a person", () -> service().create(draft, body.emailPassword(), actor));
		long id = created.user().row().id();
		String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/admin/users/{id}").build(id)
				.toString();
		return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location)
				.entity(new CreatedUser(UserView.of(created.user()), created.temporaryPassword(), created.emailed(),
						PeopleView.notEmailed(created.notEmailed())))
				.build();
	}

	/**
	 * @return the languages, time zones and sign-in providers New User and Edit offer, and whether outgoing email is
	 *         set up
	 */
	@GET
	@Path("options")
	public UserOptionsView options() {
		return UserOptionsView.of(service().options(caller()));
	}

	/**
	 * @param id a person
	 * @return the person, for their page
	 */
	@GET
	@Path("{id: [0-9]+}")
	public UserView get(@PathParam("id") long id) {
		return view(id);
	}

	/**
	 * Changes a person's names, email, language and time zone; a member left out stays as it is. Checks, in order:
	 * the body ({@code 400}), the person ({@code 404}), the fields ({@code 422}, {@code username} among them, which
	 * never changes), then Healdan's rules ({@code 409 refused}).
	 *
	 * @param id a person
	 * @param body what changes
	 * @return the person
	 */
	@PATCH
	@Path("{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public UserView edit(@PathParam("id") long id, UserChange body) {
		if (body == null)
			throw ApiProblem.badRequest("Send what changes as a JSON object.");
		checkText(body.firstName(), "Give the first name as text.");
		checkText(body.lastName(), "Give the last name as text.");
		checkText(body.email(), "Give the email address as text, or null for none.");
		checkText(body.language(), "Give the language as text, or null for the default.");
		checkText(body.timeZone(), "Give the time zone as text, or null for none.");
		Person current = existing(id);

		PersonRow row = current.row();
		// A username sent, as anything, is refused with the other fields; the service never changes one
		String username = body.username() == null ? null
				: body.username().isTextual() ? body.username().textValue() : "";
		UserDraft draft = new UserDraft(username, merged(body.firstName(), row.firstName()),
				merged(body.lastName(), row.lastName()), merged(body.email(), row.email()),
				merged(body.language(), current.language()), merged(body.timeZone(), current.timeZone()), null);
		User actor = caller();
		run("Can't edit user #" + id, () -> service().update(id, draft, actor));
		return view(id);
	}

	/**
	 * Disables the account and signs the person out everywhere; one disabled already is answered as it is.
	 *
	 * @param id a person
	 * @return the person
	 */
	@POST
	@Path("{id: [0-9]+}/disable")
	public UserView disable(@PathParam("id") long id) {
		checkPerson(id);
		User actor = caller();
		run("Can't disable user #" + id, () -> service().disable(id, actor));
		return view(id);
	}

	/**
	 * Enables a disabled account; one enabled already is answered as it is.
	 *
	 * @param id a person
	 * @return the person
	 */
	@POST
	@Path("{id: [0-9]+}/enable")
	public UserView enable(@PathParam("id") long id) {
		checkPerson(id);
		User actor = caller();
		run("Can't enable user #" + id, () -> service().enable(id, actor));
		return view(id);
	}

	/**
	 * Gives the person a temporary password, which signs them out everywhere, and answers it unless it was emailed.
	 * Checks, in order: the body ({@code 400}), the person ({@code 404}), {@code emailPassword} ({@code 422}), then
	 * Healdan's rules ({@code 409 refused}).
	 *
	 * @param id a person
	 * @param body how the password reaches them
	 * @return the password, unless it was emailed
	 */
	@POST
	@Path("{id: [0-9]+}/reset-password")
	@Consumes(MediaType.APPLICATION_JSON)
	public TemporaryPassword resetPassword(@PathParam("id") long id, PasswordReset body) {
		if (body == null)
			throw ApiProblem.badRequest("Send whether to email the password as a JSON object.");
		checkPerson(id);
		if (body.emailPassword() == null)
			throw ApiProblem.validation(List.of(new FieldError("emailPassword", "required",
					"Say whether to email the temporary password, or show it.")));
		User actor = caller();
		ResetResult reset = run("Can't reset the password of user #" + id,
				() -> service().resetPassword(id, body.emailPassword(), actor));
		return new TemporaryPassword(reset.temporaryPassword(), reset.emailed(),
				PeopleView.notEmailed(reset.notEmailed()));
	}

	/**
	 * Closes the person's open sessions and revokes their device tokens; one's own is allowed, and closes one's own
	 * session too.
	 *
	 * @param id a person
	 * @return how many sessions closed and devices were revoked
	 */
	@POST
	@Path("{id: [0-9]+}/sign-out")
	public SignedOutView signOut(@PathParam("id") long id) {
		checkPerson(id);
		User actor = caller();
		SignedOut signedOut = run("Can't sign user #" + id + " out", () -> service().signOut(id, actor));
		return new SignedOutView(signedOut.sessions(), signedOut.devices());
	}

	/**
	 * Clears the failed sign-ins counted under the person's username, never an address's.
	 *
	 * @param id a person
	 * @return the person
	 */
	@POST
	@Path("{id: [0-9]+}/clear-block")
	public UserView clearBlock(@PathParam("id") long id) {
		checkPerson(id);
		User actor = caller();
		run("Can't clear the sign-in block of user #" + id, () -> service().clearBlock(id, actor));
		return view(id);
	}

	/**
	 * Sets the groups the person is in, besides their own, Everyone or {@code guest}, which they keep. Checks, in
	 * order: the body and its groups ({@code 400}), the person ({@code 404}), {@code groupIds} ({@code 422}), what
	 * changes nothing, answered as it is, then a group that isn't one the groups list lists and the rules
	 * ({@code 409 refused}).
	 *
	 * @param id a person
	 * @param body the groups
	 * @return the person, in their groups
	 */
	@PUT
	@Path("{id: [0-9]+}/groups")
	@Consumes(MediaType.APPLICATION_JSON)
	public UserView setGroups(@PathParam("id") long id, UserGroups body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the groups as a JSON object.");
		checkGroupIds(body.groupIds());
		checkPerson(id);
		if (body.groupIds() == null)
			throw ApiProblem.validation(List.of(new FieldError("groupIds", "required",
					"Say which groups the person is in, or give none for Everyone alone.")));
		User actor = caller();
		run("Can't set the groups of user #" + id, () -> service().setGroups(id, body.groupIds(), actor));
		return view(id);
	}

	/**
	 * @param id a person
	 * @return their device tokens that still work, newest first; never a token or its hash
	 */
	@GET
	@Path("{id: [0-9]+}/devices")
	public List<UserDevice> devices(@PathParam("id") long id) {
		checkPerson(id);
		return service().devices(id).stream().map(UserDevice::of).toList();
	}

	/**
	 * Revokes one of the person's device tokens.
	 *
	 * @param id a person
	 * @param tokenId one of their tokens that still works
	 * @return {@code 204}
	 */
	@DELETE
	@Path("{id: [0-9]+}/devices/{tokenId: [0-9]+}")
	public Response revokeDevice(@PathParam("id") long id, @PathParam("tokenId") long tokenId) {
		checkPerson(id);
		User actor = caller();
		if (!run("Can't revoke a device of user #" + id, () -> service().revokeDevice(id, tokenId, actor)))
			throw ApiProblem.notFound("That device isn't signed in as this person.");
		return Response.noContent().build();
	}

	/**
	 * @param id a person
	 * @return the documents they have checked out, the latest check-out first
	 */
	@GET
	@Path("{id: [0-9]+}/checkouts")
	public List<UserCheckout> checkouts(@PathParam("id") long id) {
		checkPerson(id);
		return service().checkouts(id).stream().map(UserCheckout::of).toList();
	}

	/**
	 * @return the person's page, as the reader would see it now
	 * @throws ApiProblem {@code 404 not-found} unless the account is a person of the caller's tenant
	 */
	static Person existing(long id) {
		Person person = service().person(id, caller());
		if (person == null)
			throw notFound(id);
		return person;
	}

	/**
	 * @throws ApiProblem {@code 404 not-found} unless the account is a person of the caller's tenant
	 */
	static void checkPerson(long id) {
		if (!service().isPerson(id, caller()))
			throw notFound(id);
	}

	private static UserView view(long id) {
		return UserView.of(existing(id));
	}

	private static ApiProblem notFound(long id) {
		return ApiProblem.notFound("There's no person %d.".formatted(id));
	}

	private static void missing(List<FieldError> fields, String field, String value, String detail) {
		if (value == null || value.isBlank())
			fields.add(new FieldError(field, "required", detail));
	}

	private static UserAdministration service() {
		return UserAdministration.get();
	}

	private static User caller() {
		return Caller.current().user();
	}
}
