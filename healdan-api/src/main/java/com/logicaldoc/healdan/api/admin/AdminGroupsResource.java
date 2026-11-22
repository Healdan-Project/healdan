// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static com.logicaldoc.healdan.api.admin.PeopleRequests.checkText;
import static com.logicaldoc.healdan.api.admin.PeopleRequests.merged;
import static com.logicaldoc.healdan.api.admin.PeopleRequests.run;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.people.GroupAdministration;
import com.logicaldoc.healdan.admin.people.GroupDetail;
import com.logicaldoc.healdan.admin.people.GroupRow;
import com.logicaldoc.healdan.admin.people.MemberRow;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.Paging;
import com.logicaldoc.healdan.api.admin.PeopleView.GroupDetailView;
import com.logicaldoc.healdan.api.admin.PeopleView.GroupMember;
import com.logicaldoc.healdan.api.admin.PeopleView.GroupView;
import com.logicaldoc.healdan.api.model.Page;

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
 * {@code /v1/admin/groups}: the groups (Admin app spec sections 8.6 and 8.7), through {@link GroupAdministration},
 * which checks every change's fields and rules itself and records each change in the administration record: the
 * Groups list, which the forms that name a group, such as New Store's who can see it, read too; New Group, a group's
 * page, its rename and description, its delete, and its members, whose changes keep a person's groups' guards.
 * <p>
 * A group is a live group of the caller's tenant that isn't a person's own ({@code _user_...}), which the forms name
 * as people; anything else in the path is {@code 404}, and so is a {@code userId} that isn't a person. LogicalDOC's
 * {@code publisher} group has a negative id, so the paths take one. The checks come in the contract's order: the body
 * or the paging ({@code 400}), the group and the person ({@code 404}), the fields ({@code 422}), then the rules
 * ({@code 409 refused}).
 */
@Component
@Path("/v1/admin/groups")
@AdminRoute(AdminArea.PEOPLE)
@Produces(MediaType.APPLICATION_JSON)
public class AdminGroupsResource {

	/**
	 * A new group, {@code openapi-admin.yaml}'s {@code NewGroup}: each member null when it wasn't sent.
	 */
	public record NewGroup(String name, String description) {
	}

	/**
	 * A group's new name or description, {@code openapi-admin.yaml}'s {@code GroupChange}, read as JSON so that a
	 * member left out (null here) is told apart from one sent as null ({@code NullNode}).
	 */
	public record GroupChange(JsonNode name, JsonNode description) {
	}

	/**
	 * @param q part of a group's name; any case, the whitespace at either end left out, {@code %} and {@code _} as
	 *        they are; none or blank for every group
	 * @param offset how many groups to skip
	 * @param limit the most to return
	 * @return a page of the groups, by name, ignoring case, with how many there are once {@code q} has filtered them
	 */
	@GET
	public Page<GroupView> list(@QueryParam("q") String q, @QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		com.logicaldoc.healdan.admin.Page<GroupRow> page = service().list(q, paging.offset(), paging.limit(),
				caller());
		return new Page<>(page.items().stream().map(GroupView::of).toList(), page.offset(), page.limit(),
				page.total());
	}

	/**
	 * Makes a group with no members. Checks, in order: the body ({@code 400}), the fields ({@code 422}), then
	 * Healdan's rules ({@code 409 refused}).
	 *
	 * @param body the new group
	 * @param uri the request's address, for the group's
	 * @return {@code 201} with the group, and its address as {@code Location}
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(NewGroup body, @Context UriInfo uri) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new group as a JSON object.");
		User actor = caller();
		GroupDetail group = run("Can't create a group",
				() -> service().create(body.name(), body.description(), actor));
		String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/admin/groups/{id}")
				.build(group.row().id()).toString();
		return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location)
				.entity(GroupDetailView.of(group)).build();
	}

	/**
	 * @param id a group
	 * @return the group, for its page
	 */
	@GET
	@Path("{id: -?[0-9]+}")
	public GroupDetailView get(@PathParam("id") long id) {
		return view(id);
	}

	/**
	 * Renames a group or changes its description; a member left out stays as it is. Checks, in order: the body
	 * ({@code 400}), the group ({@code 404}), the fields ({@code 422}), then a fixed group's new name and Healdan's
	 * rules ({@code 409 refused}).
	 *
	 * @param id a group
	 * @param body what changes
	 * @return the group
	 */
	@PATCH
	@Path("{id: -?[0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public GroupDetailView edit(@PathParam("id") long id, GroupChange body) {
		if (body == null)
			throw ApiProblem.badRequest("Send what changes as a JSON object.");
		checkText(body.name(), "Give the name as text.");
		checkText(body.description(), "Give the description as text, or null for none.");
		GroupRow current = existing(id).row();
		String name = merged(body.name(), current.name());
		String description = merged(body.description(), current.description());
		User actor = caller();
		run("Can't change group #" + id, () -> service().update(id, name, description, actor));
		return view(id);
	}

	/**
	 * Takes every member out of the group and deletes it.
	 *
	 * @param id a group
	 * @return {@code 204}
	 */
	@DELETE
	@Path("{id: -?[0-9]+}")
	public Response delete(@PathParam("id") long id) {
		checkGroup(id);
		User actor = caller();
		run("Can't delete group #" + id, () -> {
			service().delete(id, actor);
			return null;
		});
		return Response.noContent().build();
	}

	/**
	 * @param id a group
	 * @param q part of a member's name, username or email, as the Users list reads it
	 * @param offset how many members to skip
	 * @param limit the most to return
	 * @return a page of the people in the group, by name, ignoring case, then username
	 */
	@GET
	@Path("{id: -?[0-9]+}/members")
	public Page<GroupMember> members(@PathParam("id") long id, @QueryParam("q") String q,
			@QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		checkGroup(id);
		com.logicaldoc.healdan.admin.Page<MemberRow> page = service().members(id, q, paging.offset(),
				paging.limit(), caller());
		return new Page<>(page.items().stream().map(GroupMember::of).toList(), page.offset(), page.limit(),
				page.total());
	}

	/**
	 * Adds a person to the group; someone in it already changes nothing.
	 *
	 * @param id a group
	 * @param userId a person
	 * @return {@code 204}
	 */
	@PUT
	@Path("{id: -?[0-9]+}/members/{userId: [0-9]+}")
	public Response addMember(@PathParam("id") long id, @PathParam("userId") long userId) {
		checkGroup(id);
		AdminUsersResource.checkPerson(userId);
		User actor = caller();
		run("Can't add user #" + userId + " to group #" + id, () -> service().addMember(id, userId, actor));
		return Response.noContent().build();
	}

	/**
	 * Takes a person out of the group; someone not in it changes nothing.
	 *
	 * @param id a group
	 * @param userId a person
	 * @return {@code 204}
	 */
	@DELETE
	@Path("{id: -?[0-9]+}/members/{userId: [0-9]+}")
	public Response removeMember(@PathParam("id") long id, @PathParam("userId") long userId) {
		checkGroup(id);
		AdminUsersResource.checkPerson(userId);
		User actor = caller();
		run("Can't take user #" + userId + " out of group #" + id,
				() -> service().removeMember(id, userId, actor));
		return Response.noContent().build();
	}

	/**
	 * @return the group's page
	 * @throws ApiProblem {@code 404 not-found} unless it's a group the list lists
	 */
	private static GroupDetail existing(long id) {
		GroupDetail group = service().group(id, caller());
		if (group == null)
			throw notFound(id);
		return group;
	}

	/**
	 * @throws ApiProblem {@code 404 not-found} unless it's a group the list lists
	 */
	private static void checkGroup(long id) {
		if (!service().isGroup(id, caller()))
			throw notFound(id);
	}

	private static GroupDetailView view(long id) {
		return GroupDetailView.of(existing(id));
	}

	private static ApiProblem notFound(long id) {
		return ApiProblem.notFound("There's no group %d.".formatted(id));
	}

	private static GroupAdministration service() {
		return GroupAdministration.get();
	}

	private static User caller() {
		return Caller.current().user();
	}
}
