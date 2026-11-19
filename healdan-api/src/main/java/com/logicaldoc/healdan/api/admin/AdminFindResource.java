// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.admin.FoundView.FoundClient;
import com.logicaldoc.healdan.api.admin.FoundView.FoundMatter;
import com.logicaldoc.healdan.api.admin.FoundView.FoundPerson;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.listing.ListingSql;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.ClientMatterRef;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /v1/admin/find?q=}: the top bar's Find a Client, Matter or Person (Admin app spec section 7.1), at most
 * {@value #MAX_EACH} of each. Matters are read from {@link MatterDAO} directly (plan, correction 3).
 */
@Component
@Path("/v1/admin/find")
@AdminRoute(AdminArea.DASHBOARD)
@Produces(MediaType.APPLICATION_JSON)
public class AdminFindResource {

	/** The most of each kind */
	static final int MAX_EACH = 5;

	private static final String MATCHES = "like :q escape '!'";

	/**
	 * The live matters (not deleted, their folders not in the Trash) that match, with their clients, by client number
	 * then matter number
	 */
	private static final String MATTERS = """
			select m from %s m, %s c
			 where c.id = m.clientId and m.deleted = 0 and c.deleted = 0
			   and m.folderId in (select f.id from Folder f where f.deleted = 0)
			   and (%%s)
			 order by c.number, m.number, m.id""".formatted(Matter.class.getCanonicalName(),
			Client.class.getCanonicalName());

	/**
	 * @param q what to look for: a client's name or number, a matter's name or number ({@code 1001.1}), or a
	 *        username or a person's name; any case, the whitespace at either end left out
	 * @return what was found
	 */
	@GET
	public FoundView find(@QueryParam("q") String q) {
		String text = q != null ? q.strip() : "";
		if (text.isEmpty())
			throw ApiProblem.validation(List.of(new FieldError("q", "required", "Say what to look for.")));

		String like = "%" + escape(text.toLowerCase(Locale.ROOT)) + "%";
		Optional<ClientMatterRef> ref = ClientMatterRef.parse(text);
		try {
			return new FoundView(clients(like, ref), matters(like, ref), people(like));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't find clients, matters and people", e);
		}
	}

	/**
	 * @return the clients whose name contains the text, or whose number is the bare number it is, by number
	 */
	private static List<FoundClient> clients(String like, Optional<ClientMatterRef> ref) throws PersistenceException {
		Map<String, Object> params = new HashMap<>();
		params.put("q", like);
		String where = "lower(_entity.name) " + MATCHES;
		if (ref.isPresent() && ref.get().isNumberOnly()) {
			where += " or _entity.number = :number";
			params.put("number", ref.get().client());
		}
		return ClientDAO.get().findByWhere(where, params, "_entity.number asc, _entity.id asc", MAX_EACH).stream()
				.map(AdminFindResource::client).toList();
	}

	/**
	 * @return the live matters whose name contains the text, the matter it names as {@code 1001.1}, and the matters
	 *         of the client it names as a bare number
	 */
	private static List<FoundMatter> matters(String like, Optional<ClientMatterRef> ref) throws PersistenceException {
		Map<String, Object> params = new HashMap<>();
		params.put("q", like);
		String match = "lower(m.name) " + MATCHES;
		if (ref.isPresent()) {
			params.put("client", ref.get().client());
			if (ref.get().isNumberOnly()) {
				match += " or c.number = :client";
			} else {
				match += " or (c.number = :client and m.number = :matter)";
				params.put("matter", ref.get().matter());
			}
		}
		List<Matter> matters = MatterDAO.get().findByQuery(MATTERS.formatted(match), params, Matter.class, MAX_EACH);
		Map<Long, Client> clients = new HashMap<>();
		for (Matter matter : matters)
			if (!clients.containsKey(matter.getClientId()))
				clients.put(matter.getClientId(), ClientDAO.get().findById(matter.getClientId()));
		return matters.stream().map(matter -> {
			Client client = clients.get(matter.getClientId());
			return new FoundMatter(matter.getId(), MatterDisplay.number(client.getNumber(), matter.getNumber()),
					matter.getName(), client(client));
		}).toList();
	}

	/**
	 * @return the people of the caller's tenant whose username, first or last name, or "first last" contains the
	 *         text, disabled ones too; never a system account such as {@code _system}
	 */
	private static List<FoundPerson> people(String like) throws PersistenceException {
		String where = """
				_entity.tenantId = :tenantId and _entity.type <> :system
				and (lower(_entity.username) %1$s or lower(_entity.firstName) %1$s or lower(_entity.name) %1$s
				or lower(concat(_entity.firstName, ' ', _entity.name)) %1$s)""".formatted(MATCHES);
		Map<String, Object> params = Map.of("tenantId", Caller.current().user().getTenantId(), "system",
				User.Type.SYSTEM, "q", like);
		String order = "%s, lower(_entity.firstName), %s, lower(_entity.name), _entity.id"
				.formatted(ListingSql.nullsLast("_entity.firstName"), ListingSql.nullsLast("_entity.name"));
		return UserDAO.get().findByWhere(where, params, order, MAX_EACH).stream()
				.map(u -> new FoundPerson(u.getId(), u.getUsername(), u.getFullName(), u.isEnabled())).toList();
	}

	private static FoundClient client(Client client) {
		return new FoundClient(client.getId(), client.getNumber(), client.getName());
	}

	/**
	 * @return the text with LIKE's wildcards, and the escape character, taken literally
	 */
	private static String escape(String text) {
		return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}
}
