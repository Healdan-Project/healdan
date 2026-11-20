// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static com.logicaldoc.healdan.api.admin.ListOrder.date;
import static com.logicaldoc.healdan.api.admin.ListOrder.descending;
import static com.logicaldoc.healdan.api.admin.ListOrder.directed;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.dashboard.MatterStats;
import com.logicaldoc.healdan.admin.dashboard.UsageSummary;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.Paging;
import com.logicaldoc.healdan.api.admin.ClientView.ClientList;
import com.logicaldoc.healdan.api.admin.ClientView.NextNumber;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.listing.ListingSql;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.ClientService;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
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
 * {@code /v1/admin/clients}: the Clients list and a client's page (Admin app spec section 8.2), and creating and
 * renaming a client through {@link ClientService}, which records each change in the administration record. Nothing
 * deletes a client, and its number never changes.
 * <p>
 * A client's figures are its live matters', from the summary {@link UsageSummary} keeps in memory, so a request never
 * counts documents. Every matching client is read, then sorted and paged in Java, as a figure can't be sorted in SQL.
 */
@Component
@Path("/v1/admin/clients")
@AdminRoute(AdminArea.MATTERS)
@Produces(MediaType.APPLICATION_JSON)
public class AdminClientsResource {

	/** The clients {@code q} keeps, on the client {@code %1$s}: a number that starts with it, or a name that holds it */
	private static final String MATCHES = """
			(cast(%%1$s.number as String) like :prefix %1$s
			 or lower(%%1$s.name) like :text %1$s)""".formatted(ListingSql.LIKE_ESCAPE);

	/** The one client, {@code %1$s}, with the id */
	private static final String ONE = "%1$s.id = :id";

	/** The live matters, not deleted and their folders not in the Trash, and their clients' ids */
	private static final String LIVE_MATTERS = """
			select m.id, m.clientId from %s m, %s c
			 where c.id = m.clientId and m.deleted = 0 and c.deleted = 0
			   and m.folderId in (select f.id from Folder f where f.deleted = 0)""".formatted(
			Matter.class.getCanonicalName(), Client.class.getCanonicalName());

	/**
	 * A new client, {@code openapi-admin.yaml}'s {@code NewClient}.
	 *
	 * @param number its number; null when it wasn't sent
	 * @param name its name; null when it wasn't sent
	 */
	public record NewClient(Long number, String name) {
	}

	/**
	 * A client's new name, {@code openapi-admin.yaml}'s {@code NameChange}, read with the {@code number} it may not
	 * carry, so that one is {@code 422 not-editable} rather than an unknown member's {@code 400}.
	 *
	 * @param name the new name; null when it wasn't sent
	 * @param number whatever was sent as the number, a JSON {@code null} included; null when nothing was
	 */
	public record ClientChange(String name, JsonNode number) {
	}

	/** The columns the list sorts by, as {@code sort} names them */
	enum Sort {
		NUMBER("number"), NAME("name"), MATTERS("matters"), DOCUMENTS("documents"), LAST_ACTIVITY("lastActivity");

		private final String parameter;

		Sort(String parameter) {
			this.parameter = parameter;
		}

		/**
		 * @param parameter the {@code sort} query parameter, null for the default
		 * @return the column it names, {@link #NUMBER} by default
		 * @throws ApiProblem {@code 400 bad-request} for anything else, such as {@code Number}: the names are exact
		 */
		static Sort of(String parameter) {
			if (parameter == null)
				return NUMBER;
			for (Sort sort : values())
				if (sort.parameter.equals(parameter))
					return sort;
			throw ApiProblem.badRequest("Sort by number, name, matters, documents or lastActivity.");
		}

		/**
		 * @param descending true for the highest first
		 * @return the order of the clients: by this column, a client with nothing to sort by (a figure that's null)
		 *         last whichever the order, then by number
		 */
		Comparator<ClientView> order(boolean descending) {
			Comparator<ClientView> column = switch (this) {
			case NUMBER -> directed(Comparator.comparingLong(ClientView::number), descending);
			case NAME -> directed(Comparator.comparing((ClientView c) -> c.name().toLowerCase(Locale.ROOT)),
					descending);
			case MATTERS -> directed(Comparator.comparingLong(ClientView::matters), descending);
			case DOCUMENTS -> Comparator.comparing(ClientView::documents,
					Comparator.nullsLast(directed(Comparator.<Long>naturalOrder(), descending)));
			case LAST_ACTIVITY -> Comparator.comparing(ClientView::lastActivity,
					Comparator.nullsLast(directed(Comparator.<Date>naturalOrder(), descending)));
			};
			return column.thenComparingLong(ClientView::number);
		}
	}

	/**
	 * @param q a client's number, or part of its name; any case, the whitespace at either end left out
	 * @param sort the column to sort by, {@code number} by default
	 * @param order {@code asc}, the default, or {@code desc}
	 * @param offset how many clients to skip
	 * @param limit the most to return
	 * @return a page of the clients that match, with their figures, and when those were worked out
	 */
	@GET
	public ClientList list(@QueryParam("q") String q, @QueryParam("sort") String sort,
			@QueryParam("order") String order, @QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		Comparator<ClientView> comparator = Sort.of(sort).order(descending(order));
		Paging paging = Paging.of(offset, limit);

		String text = q != null ? q.strip().toLowerCase(Locale.ROOT) : "";
		Map<String, Object> params = new HashMap<>();
		if (!text.isEmpty()) {
			params.put("prefix", ListingSql.startingWith(text));
			params.put("text", ListingSql.containing(text));
		}
		Instant figuresAt = UsageSummary.get().current().computedAt();
		try {
			List<ClientView> clients = new ArrayList<>(views(text.isEmpty() ? null : MATCHES, params, figuresAt));
			clients.sort(comparator);
			Page<ClientView> page = paging.slice(clients);
			return new ClientList(page.items(), page.offset(), page.limit(), page.total(), date(figuresAt));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the clients", e);
		}
	}

	/**
	 * @return the number to suggest for a new client: the highest in use plus 1
	 */
	@GET
	@Path("next-number")
	public NextNumber nextNumber() {
		try {
			return new NextNumber(ClientService.get().suggestNumber());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't suggest a client number", e);
		}
	}

	/**
	 * @param id the client's id
	 * @return the client, as the list gives it
	 */
	@GET
	@Path("{id: [0-9]+}")
	public ClientView get(@PathParam("id") long id) {
		try {
			return view(id);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read client #" + id, e);
		}
	}

	/**
	 * Creates a client with the number and name given; {@link ClientService#create} refuses a number that's taken
	 * or below 1, and a name that's too long, as {@code 409 refused}, and one that loses a race for its number to
	 * another create is refused as a used number ({@link NumberRaces}).
	 *
	 * @param body the new client
	 * @param uri the request's address, for the new client's
	 * @return {@code 201} with the client, and its address as {@code Location}
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(NewClient body, @Context UriInfo uri) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new client as a JSON object.");
		List<FieldError> fields = new ArrayList<>();
		if (body.number() == null)
			fields.add(new FieldError("number", "required", "Give the client a number."));
		if (body.name() == null || body.name().isBlank())
			fields.add(new FieldError("name", "required", "Give the client a name."));
		if (!fields.isEmpty())
			throw ApiProblem.validation(fields);

		User actor = Caller.current().user();
		try {
			// A refusal of the service's is a veto, which the ProblemMapper answers 409 refused, wrapped or not; so is
			// a create that loses a race for its number to another
			Client client = NumberRaces.create(() -> ClientService.get().create(body.number(), body.name(), actor),
					() -> ClientService.get().checkAvailable(body.number()));
			String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/admin/clients/{id}")
					.build(client.getId()).toString();
			return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location)
					.entity(view(client.getId())).build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't create a client", e);
		}
	}

	/**
	 * Renames a client, through {@link ClientService#rename}. Only the name can change: a body with {@code number}
	 * is {@code 422 not-editable}, whatever the number.
	 *
	 * @param id the client's id
	 * @param body the new name
	 * @return the client, renamed
	 */
	@PATCH
	@Path("{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public ClientView rename(@PathParam("id") long id, ClientChange body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the client's new name as a JSON object.");
		try {
			existing(id);
			List<FieldError> fields = new ArrayList<>();
			if (body.number() != null)
				fields.add(new FieldError("number", "not-editable", "A client's number never changes."));
			if (body.name() == null || body.name().isBlank())
				fields.add(new FieldError("name", "required", "Give the client a name."));
			if (!fields.isEmpty())
				throw ApiProblem.validation(fields);

			ClientService.get().rename(id, body.name(), Caller.current().user());
			return view(id);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't rename client #" + id, e);
		}
	}

	/**
	 * @return the client
	 * @throws ApiProblem {@code 404 not-found} if there's no such client
	 */
	private static Client existing(long id) throws PersistenceException {
		Client client = ClientDAO.get().findById(id);
		if (client == null)
			throw ApiProblem.notFound("There's no client %d.".formatted(id));
		return client;
	}

	/**
	 * @return the client, with its figures as the summary has them now
	 */
	private static ClientView view(long id) throws PersistenceException {
		List<ClientView> found = views(ONE, Map.of("id", id), UsageSummary.get().current().computedAt());
		if (found.isEmpty())
			throw ApiProblem.notFound("There's no client %d.".formatted(id));
		return found.get(0);
	}

	/**
	 * @param condition what keeps a client, on the client {@code %1$s}, or null for every client
	 * @param params its parameters
	 * @param figuresAt when the summary was worked out, or null if it hasn't been: then there are no figures
	 * @return the clients it keeps, with their live matters and their figures, in no particular order
	 */
	private static List<ClientView> views(String condition, Map<String, Object> params, Instant figuresAt)
			throws PersistenceException {
		UsageSummary summary = UsageSummary.get();
		Map<Long, List<Long>> matters = new HashMap<>();
		String live = condition != null ? LIVE_MATTERS + " and " + condition.formatted("c") : LIVE_MATTERS;
		for (Object[] row : MatterDAO.get().findByQuery(live, params, null))
			matters.computeIfAbsent((Long) row[1], client -> new ArrayList<>()).add((Long) row[0]);

		List<ClientView> views = new ArrayList<>();
		for (Client client : ClientDAO.get().findByWhere(condition != null ? condition.formatted("_entity") : null,
				params, null, null)) {
			List<Long> ids = matters.getOrDefault(client.getId(), List.of());
			Long documents = null;
			Instant lastActivity = null;
			if (figuresAt != null) {
				// Its live matters' figures: their documents summed, and the latest of their activity
				long total = 0;
				for (long matterId : ids) {
					MatterStats stats = summary.forMatter(matterId);
					if (stats == null)
						continue;
					total += stats.documents();
					if (stats.lastActivity() != null
							&& (lastActivity == null || stats.lastActivity().isAfter(lastActivity)))
						lastActivity = stats.lastActivity();
				}
				documents = total;
			}
			views.add(new ClientView(client.getId(), client.getNumber(), client.getName(), ids.size(), documents,
					date(lastActivity)));
		}
		return views;
	}
}
