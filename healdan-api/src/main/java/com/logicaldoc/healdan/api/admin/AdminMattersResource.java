// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static com.logicaldoc.healdan.api.admin.ListOrder.date;
import static com.logicaldoc.healdan.api.admin.ListOrder.descending;
import static com.logicaldoc.healdan.api.admin.ListOrder.directed;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.healdan.admin.dashboard.MatterStats;
import com.logicaldoc.healdan.admin.dashboard.UsageSummary;
import com.logicaldoc.healdan.admin.index.IndexCounts;
import com.logicaldoc.healdan.admin.matters.MatterAdmin;
import com.logicaldoc.healdan.admin.matters.MatterCheck;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.FolderResource;
import com.logicaldoc.healdan.api.Paging;
import com.logicaldoc.healdan.api.admin.ClientView.NextNumber;
import com.logicaldoc.healdan.api.admin.MatterView.ClientBrief;
import com.logicaldoc.healdan.api.admin.MatterView.DeletedMatter;
import com.logicaldoc.healdan.api.admin.MatterView.MatterList;
import com.logicaldoc.healdan.api.admin.MatterView.MatterRow;
import com.logicaldoc.healdan.api.admin.MatterView.PersonBrief;
import com.logicaldoc.healdan.api.admin.MatterView.StoreBrief;
import com.logicaldoc.healdan.api.admin.MatterView.TemplateBrief;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.listing.ListingSql;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.matter.MatterNumbering;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.preview.PreviewJobs;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/**
 * {@code /v1/admin/matters}: the Matters list and a matter's page (Admin app spec section 8.3), New Matter with its
 * check, Edit, Move to Another Store and Delete. Creating and editing go through {@link MatterService}, which records
 * each change in the administration record; a move is recorded inside its transaction, and a delete straight after
 * it. A deleted matter's row stays, so its number stays taken, and it's restored from the User app's Trash.
 * <p>
 * The list and a matter are read in one query each, the matter with its client, Store, owner and template. A matter's
 * figures are the summary's that {@link UsageSummary} keeps in memory, so a request never counts documents but for a
 * matter's own page, which counts those that couldn't be indexed or previewed. Sorted by number, the list's own order,
 * the database pages it, so a page reads only its own matters; sorted by anything else, every matching matter is read,
 * then sorted and paged in Java, as a figure can't be sorted in SQL, nor a name in Java's case-folding or a person
 * named as {@link PersonNames} names them; ten thousand fit.
 */
@Component
@Path("/v1/admin/matters")
@AdminRoute(AdminArea.MATTERS)
@Produces(MediaType.APPLICATION_JSON)
public class AdminMattersResource {

	/** What the administration record calls a matter */
	private static final String MATTER = "matter";

	/**
	 * The live matters (not deleted, their folders not in the Trash, their clients not deleted), each with its client,
	 * the Store its folder is in, its owner (null when that account is deleted) and its template (null when it was made
	 * from none, or that folder isn't a live template now)
	 */
	private static final String LIVE = """
			select m.ld_id, m.hd_number, m.hd_name, m.hd_description, m.hd_folderid, m.hd_ownerid,
			       c.ld_id as clientid, c.hd_number as clientnumber, c.hd_name as clientname,
			       s.ld_id as storeid, s.ld_name as storename,
			       u.ld_id as ownerid, u.ld_username, u.ld_firstname, u.ld_name as lastname,
			       t.ld_id as templateid, t.ld_name as templatename
			  from hd_matter m
			  join hd_client c on c.ld_id = m.hd_clientid and c.ld_deleted = 0
			  join ld_folder f on f.ld_id = m.hd_folderid and f.ld_deleted = 0
			  join ld_folder s on s.ld_id = f.ld_parentid
			  left join ld_user u on u.ld_id = m.hd_ownerid and u.ld_deleted = 0
			  left join ld_folder t on t.ld_id = m.hd_templateid and t.ld_deleted = 0 and t.ld_parentid = :templates
			 where m.ld_deleted = 0""";

	/** The matters {@code q} keeps: a number that starts with it, or a matter's or a client's name that holds it */
	private static final String MATCHES = """
			((cast(c.hd_number as text) || '.' || cast(m.hd_number as text)) like :prefix %1$s
			 or lower(m.hd_name) like :text %1$s or lower(c.hd_name) like :text %1$s)"""
			.formatted(ListingSql.LIKE_ESCAPE);

	/**
	 * A new matter, {@code openapi-admin.yaml}'s {@code NewMatter}, and New Matter's draft, its {@code MatterDraft}:
	 * each member null when it wasn't sent.
	 */
	public record MatterBody(Long clientId, Long number, String name, String description, Long storeId, Long ownerId,
			Long templateId) {
	}

	/**
	 * What Edit changes, {@code openapi-admin.yaml}'s {@code MatterChange}, read as JSON so that a member left out
	 * (null here) is told apart from one sent as null ({@code NullNode}), and with the members it may not carry, so
	 * those are {@code 422 not-editable} rather than an unknown member's {@code 400}.
	 */
	public record MatterChange(JsonNode name, JsonNode description, JsonNode ownerId, JsonNode clientId,
			JsonNode number, JsonNode storeId, JsonNode templateId) {
	}

	/**
	 * Where a matter moves, {@code openapi-admin.yaml}'s {@code MatterMove}.
	 *
	 * @param storeId the Store; null when it wasn't sent
	 */
	public record MatterMove(Long storeId) {
	}

	/** A live matter as {@link #LIVE} reads it */
	private record Found(long id, long number, String name, String description, long folderId, long ownerId,
			ClientBrief client, StoreBrief store, PersonBrief owner, TemplateBrief template) {

		String display() {
			return MatterDisplay.format(client.number(), number, name);
		}
	}

	/** A matter with its figures, as the list sorts it */
	private record Listed(Found found, MatterStats stats) {
	}

	/** The columns the list sorts by, as {@code sort} names them */
	enum Sort {
		NUMBER("number"), NAME("name"), CLIENT("client"), STORE("store"), OWNER("owner"), TEMPLATE("template"),
		DOCUMENTS("documents"), BYTES("bytes"), LAST_ACTIVITY("lastActivity");

		private final String parameter;

		Sort(String parameter) {
			this.parameter = parameter;
		}

		/**
		 * @param parameter the {@code sort} query parameter, null for the default
		 * @return the column it names, {@link #NUMBER} by default
		 * @throws ApiProblem {@code 400 bad-request} for anything else: the names are exact
		 */
		static Sort of(String parameter) {
			if (parameter == null)
				return NUMBER;
			for (Sort sort : values())
				if (sort.parameter.equals(parameter))
					return sort;
			throw ApiProblem.badRequest(
					"Sort by number, name, client, store, owner, template, documents, bytes or lastActivity.");
		}

		/**
		 * @param descending true for the highest first
		 * @return the order of the matters: by this column, a matter with nothing to sort by (no owner or template, a
		 *         figure that's null) last whichever the order, then by number, client's first
		 */
		Comparator<Listed> order(boolean descending) {
			Comparator<Listed> byNumber = Comparator.comparingLong((Listed l) -> l.found().client().number())
					.thenComparingLong(l -> l.found().number());
			Comparator<Listed> column = switch (this) {
			case NUMBER -> directed(byNumber, descending);
			case NAME -> directed(Comparator.comparing((Listed l) -> lower(l.found().name())), descending);
			case CLIENT -> directed(Comparator.comparing((Listed l) -> lower(l.found().client().name()))
					.thenComparingLong(l -> l.found().client().number()), descending);
			case STORE -> directed(Comparator.comparing((Listed l) -> lower(l.found().store().name())), descending);
			case OWNER -> named(l -> l.found().owner() != null ? l.found().owner().name() : null, descending);
			case TEMPLATE -> named(l -> l.found().template() != null ? l.found().template().name() : null,
					descending);
			case DOCUMENTS -> figure(l -> l.stats() != null ? l.stats().documents() : null, descending);
			case BYTES -> figure(l -> l.stats() != null ? l.stats().bytes() : null, descending);
			case LAST_ACTIVITY -> Comparator.comparing(
					(Listed l) -> l.stats() != null ? l.stats().lastActivity() : null,
					Comparator.nullsLast(directed(Comparator.<Instant>naturalOrder(), descending)));
			};
			return column.thenComparing(byNumber);
		}

		private static Comparator<Listed> named(Function<Listed, String> name, boolean descending) {
			return Comparator.comparing((Listed l) -> lower(name.apply(l)),
					Comparator.nullsLast(directed(Comparator.<String>naturalOrder(), descending)));
		}

		private static Comparator<Listed> figure(Function<Listed, Long> figure, boolean descending) {
			return Comparator.comparing(figure, Comparator.nullsLast(directed(Comparator.<Long>naturalOrder(),
					descending)));
		}

		private static String lower(String text) {
			return text != null ? text.toLowerCase(Locale.ROOT) : null;
		}
	}

	/**
	 * @param q a matter's number with its client's, such as {@code 1001.1}, from its start, or part of its name or its
	 *        client's; any case, the whitespace at either end left out
	 * @param clientId only this client's matters
	 * @param storeId only the matters in this Store
	 * @param ownerId only the matters this person owns
	 * @param sort the column to sort by, {@code number} by default
	 * @param order {@code asc}, the default, or {@code desc}
	 * @param offset how many matters to skip
	 * @param limit the most to return
	 * @return a page of the matters that match, with their figures, and when those were worked out
	 */
	@GET
	public MatterList list(@QueryParam("q") String q, @QueryParam("clientId") Long clientId,
			@QueryParam("storeId") Long storeId, @QueryParam("ownerId") Long ownerId, @QueryParam("sort") String sort,
			@QueryParam("order") String order, @QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		Sort column = Sort.of(sort);
		boolean descending = descending(order);
		Comparator<Listed> comparator = column.order(descending);
		Paging paging = Paging.of(offset, limit);

		StringBuilder where = new StringBuilder();
		MapSqlParameterSource params = new MapSqlParameterSource();
		String text = q != null ? q.strip().toLowerCase(Locale.ROOT) : "";
		if (!text.isEmpty()) {
			where.append(" and ").append(MATCHES);
			params.addValue("prefix", ListingSql.startingWith(text)).addValue("text", ListingSql.containing(text));
		}
		if (clientId != null) {
			where.append(" and m.hd_clientid = :clientId");
			params.addValue("clientId", clientId);
		}
		if (storeId != null) {
			where.append(" and f.ld_parentid = :storeId");
			params.addValue("storeId", storeId);
		}
		if (ownerId != null) {
			where.append(" and m.hd_ownerid = :ownerId");
			params.addValue("ownerId", ownerId);
		}

		UsageSummary summary = UsageSummary.get();
		Instant figuresAt = summary.current().computedAt();
		Page<Listed> page;
		if (column == Sort.NUMBER) {
			// A client's number and a matter's are unique together, and sort in SQL as in Java: only this page is read
			String direction = descending ? " desc" : "";
			long total = count(where.toString(), params);
			params.addValue("limit", paging.limit()).addValue("offset", paging.offset());
			List<Listed> matters = new ArrayList<>();
			for (Found found : find(where + " order by c.hd_number%1$s, m.hd_number%1$s".formatted(direction)
					+ ListingSql.PAGE, params))
				matters.add(new Listed(found, figuresAt != null ? summary.forMatter(found.id()) : null));
			page = new Page<>(matters, paging.offset(), paging.limit(), total);
		} else {
			List<Listed> matters = new ArrayList<>();
			for (Found found : find(where.toString(), params))
				matters.add(new Listed(found, figuresAt != null ? summary.forMatter(found.id()) : null));
			matters.sort(comparator);
			page = paging.slice(matters);
		}
		return new MatterList(page.items().stream().map(AdminMattersResource::row).toList(), page.offset(),
				page.limit(), page.total(), date(figuresAt));
	}

	/**
	 * @param clientId the client the matter is for
	 * @return the number to suggest for its next matter: the highest in use plus 1, the client's, or the install's when
	 *         numbers are system-wide, deleted matters included
	 */
	@GET
	@Path("next-number")
	public NextNumber nextNumber(@QueryParam("clientId") Long clientId) {
		if (clientId == null)
			throw ApiProblem.validation(List.of(new FieldError("clientId", "required", "Say which client it's for.")));
		try {
			if (ClientDAO.get().findById(clientId) == null)
				throw ApiProblem.notFound("There's no client %d.".formatted(clientId));
			return new NextNumber(MatterNumbering.get().suggest(clientId));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't suggest a matter number", e);
		}
	}

	/**
	 * New Matter's check: what creating the draft would refuse, which writes nothing ({@link MatterAdmin#check}). Any
	 * member may be missing or null.
	 *
	 * @param body the form as it stands
	 * @return the problems, none when nothing is wrong
	 */
	@POST
	@Path("check")
	@Consumes(MediaType.APPLICATION_JSON)
	public MatterCheck check(MatterBody body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the draft as a JSON object.");
		try {
			return MatterAdmin.get().check(new MatterAdmin.Draft(body.clientId(), body.number(), body.name(),
					body.description(), body.storeId(), body.ownerId(), body.templateId()), Caller.current().user());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't check a new matter", e);
		}
	}

	/**
	 * @param id the matter's id
	 * @return the matter, for its page
	 */
	@GET
	@Path("{id: [0-9]+}")
	public MatterView get(@PathParam("id") long id) {
		return view(existing(id));
	}

	/**
	 * Creates a matter and its workspace from a template, through {@link MatterService#create}, which records it, and
	 * whose refusals are {@code 409 refused}. One that loses a race for its number to another create is refused as a
	 * used number ({@link NumberRaces}).
	 *
	 * @param body the new matter
	 * @param uri the request's address, for the new matter's
	 * @return {@code 201} with the matter, and its address as {@code Location}
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(MatterBody body, @jakarta.ws.rs.core.Context UriInfo uri) {
		if (body == null)
			throw ApiProblem.badRequest("Send the new matter as a JSON object.");
		List<FieldError> fields = new ArrayList<>();
		required(fields, "clientId", body.clientId(), "Choose the client.");
		required(fields, "number", body.number(), "Give the matter a number.");
		if (body.name() == null || body.name().isBlank())
			fields.add(new FieldError("name", "required", "Give the matter a name."));
		required(fields, "storeId", body.storeId(), "Choose the Store.");
		required(fields, "ownerId", body.ownerId(), "Choose the owner.");
		required(fields, "templateId", body.templateId(), "Choose the template.");
		if (!fields.isEmpty())
			throw ApiProblem.validation(fields);

		User actor = Caller.current().user();
		NewMatter request = new NewMatter(body.clientId(), body.number(), body.name(), body.description(),
				body.storeId(), body.ownerId(), body.templateId());
		try {
			// A refusal of the service's is a veto, which the ProblemMapper answers 409 refused, wrapped or not
			Matter matter = NumberRaces.create(() -> MatterService.get().create(request, actor),
					() -> MatterNumbering.get().checkAvailable(body.clientId(), body.number()));
			String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/admin/matters/{id}")
					.build(matter.getId()).toString();
			return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location)
					.entity(view(existing(matter.getId()))).build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't create a matter", e);
		}
	}

	/**
	 * Changes the matter's name, description and owner, through {@link MatterService#update}, which records what
	 * changed. A member left out stays as it is.
	 *
	 * @param id the matter's id
	 * @param body what changes
	 * @return the matter, changed
	 */
	@PATCH
	@Path("{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public MatterView edit(@PathParam("id") long id, MatterChange body) {
		if (body == null)
			throw ApiProblem.badRequest("Send what changes as a JSON object.");
		if (body.name() != null && !body.name().isNull() && !body.name().isTextual())
			throw ApiProblem.badRequest("Give the name as text.");
		if (body.description() != null && !body.description().isNull() && !body.description().isTextual())
			throw ApiProblem.badRequest("Give the description as text, or null for none.");
		if (body.ownerId() != null && !body.ownerId().isNull()
				&& !(body.ownerId().isIntegralNumber() && body.ownerId().canConvertToLong()))
			throw ApiProblem.badRequest("Give the owner as a user's id.");

		Found found = existing(id);
		List<FieldError> fields = new ArrayList<>();
		notEditable(fields, "clientId", body.clientId(), "A matter's client never changes.");
		notEditable(fields, "number", body.number(), "A matter's number never changes.");
		notEditable(fields, "storeId", body.storeId(), "Move to Another Store changes a matter's Store.");
		notEditable(fields, "templateId", body.templateId(), "A matter's template never changes.");
		if (body.name() != null && (body.name().isNull() || body.name().textValue().isBlank()))
			fields.add(new FieldError("name", "required", "Give the matter a name."));
		if (body.ownerId() != null && body.ownerId().isNull())
			fields.add(new FieldError("ownerId", "required", "Choose the owner."));
		if (!fields.isEmpty())
			throw ApiProblem.validation(fields);

		String name = body.name() != null ? body.name().textValue() : found.name();
		String description = body.description() != null ? body.description().textValue() : found.description();
		long ownerId = body.ownerId() != null ? body.ownerId().longValue() : found.ownerId();
		try {
			MatterService.get().update(id, name, description, ownerId, Caller.current().user());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't edit matter #" + id, e);
		}
		return view(existing(id));
	}

	/**
	 * Moves the matter's folder, with everything in it, into another Store ({@code FolderChanges.moveMatter}),
	 * recording it inside the move's transaction. A move into the Store it's in changes nothing.
	 *
	 * @param id the matter's id
	 * @param body the Store
	 * @return the matter, in its new Store
	 */
	@POST
	@Path("{id: [0-9]+}/move")
	@Consumes(MediaType.APPLICATION_JSON)
	public MatterView move(@PathParam("id") long id, MatterMove body) {
		if (body == null)
			throw ApiProblem.badRequest("Say which Store to move the matter into with storeId.");
		Found found = existing(id);
		if (body.storeId() == null)
			throw ApiProblem.validation(List.of(new FieldError("storeId", "required", "Choose the Store.")));

		Caller caller = Caller.current();
		Context.get(FolderResource.class).changes().moveMatter(found.folderId(), body.storeId(), caller,
				(from, to) -> AdminEvents.get().record(new AdminEvent(AdminArea.MATTERS, "matter.moved", MATTER, id,
						found.display(), Map.of("store", new Change(from.getName(), to.getName()))), caller.user()));
		return view(existing(id));
	}

	/**
	 * Moves the matter's folder, and everything live in it, to the caller's Trash ({@code FolderChanges.deleteMatter}),
	 * and records it once it has. The matter's row stays.
	 *
	 * @param id the matter's id
	 * @return the Trash item the delete made, with what it holds
	 */
	@DELETE
	@Path("{id: [0-9]+}")
	public DeletedMatter delete(@PathParam("id") long id) {
		Found found = existing(id);
		Caller caller = Caller.current();
		TrashItem item = Context.get(FolderResource.class).changes().deleteMatter(found.folderId(), caller);
		AdminEvents.get().recordAfter(new AdminEvent(AdminArea.MATTERS, "matter.deleted", MATTER, id, found.display(),
				null), caller.user());
		return new DeletedMatter(item.id(), item.contents().folders(), item.contents().documents());
	}

	/**
	 * @return the live matter
	 * @throws ApiProblem {@code 404 not-found} if there's none: no such matter, or its folder is in the Trash
	 */
	private static Found existing(long id) {
		List<Found> found = find(" and m.ld_id = :id", new MapSqlParameterSource("id", id));
		if (found.isEmpty())
			throw ApiProblem.notFound("There's no matter %d.".formatted(id));
		return found.get(0);
	}

	/**
	 * @param where what keeps a matter, after {@link #LIVE}'s own conditions, from {@code " and "}
	 * @param params its parameters
	 * @return the live matters it keeps, in no particular order
	 */
	private static List<Found> find(String where, MapSqlParameterSource params) {
		return new NamedParameterJdbcTemplate(Context.get(DataSource.class)).query(LIVE + where, withTemplates(params),
				(rs, i) -> found(rs));
	}

	/**
	 * @param where what keeps a matter, as {@link #find} takes it
	 * @param params its parameters
	 * @return how many live matters it keeps
	 */
	private static long count(String where, MapSqlParameterSource params) {
		Long total = new NamedParameterJdbcTemplate(Context.get(DataSource.class))
				.queryForObject("select count(*) from (" + LIVE + where + ") live", withTemplates(params), Long.class);
		return total != null ? total : 0;
	}

	/**
	 * @return the parameters with {@code :templates}, the Templates Store, whose live folders {@link #LIVE} takes as
	 *         templates
	 */
	private static MapSqlParameterSource withTemplates(MapSqlParameterSource params) {
		Long templates = HealdanSettings.get().templatesStoreId();
		// No folder has the id -1, so with no Templates Store no matter has a template
		return params.addValue("templates", templates != null ? templates : -1L);
	}

	private static Found found(ResultSet rs) throws SQLException {
		long ownerId = rs.getLong("ownerid");
		PersonBrief owner = rs.wasNull() ? null
				: new PersonBrief(ownerId, rs.getString("ld_username"), PersonNames.of(rs.getString("ld_firstname"),
						rs.getString("lastname"), rs.getString("ld_username")));
		long templateId = rs.getLong("templateid");
		TemplateBrief template = rs.wasNull() ? null : new TemplateBrief(templateId, rs.getString("templatename"));
		return new Found(rs.getLong("ld_id"), rs.getLong("hd_number"), rs.getString("hd_name"),
				rs.getString("hd_description"), rs.getLong("hd_folderid"), rs.getLong("hd_ownerid"),
				new ClientBrief(rs.getLong("clientid"), rs.getLong("clientnumber"), rs.getString("clientname")),
				new StoreBrief(rs.getLong("storeid"), rs.getString("storename")), owner, template);
	}

	private static MatterRow row(Listed listed) {
		Found m = listed.found();
		MatterStats stats = listed.stats();
		return new MatterRow(m.id(), MatterDisplay.number(m.client().number(), m.number()), m.name(), m.client(),
				m.store(), m.owner(), m.template(), stats != null ? stats.documents() : null,
				stats != null ? stats.bytes() : null, stats != null ? date(stats.lastActivity()) : null, m.folderId());
	}

	/**
	 * @return the matter for its page, with its figures as the summary has them now, and its documents that couldn't
	 *         be indexed or previewed, counted now
	 */
	private static MatterView view(Found m) {
		UsageSummary summary = UsageSummary.get();
		Instant figuresAt = summary.current().computedAt();
		MatterStats stats = figuresAt != null ? summary.forMatter(m.id()) : null;
		long clientNumber = m.client().number();
		return new MatterView(m.id(), MatterDisplay.number(clientNumber, m.number()), m.name(), m.description(),
				m.client(), m.store(), m.owner(), m.template(), stats != null ? stats.documents() : null,
				stats != null ? stats.bytes() : null, stats != null ? date(stats.lastActivity()) : null,
				date(figuresAt), m.folderId(), IndexCounts.get().failedIn(clientNumber, m.number()),
				PreviewJobs.get().countGivenUpIn(clientNumber, m.number()));
	}

	private static void required(List<FieldError> fields, String field, Object value, String detail) {
		if (value == null)
			fields.add(new FieldError(field, "required", detail));
	}

	/** A member sent, even as null, that the route may not change */
	private static void notEditable(List<FieldError> fields, String field, JsonNode value, String detail) {
		if (value != null)
			fields.add(new FieldError(field, "not-editable", detail));
	}

}
