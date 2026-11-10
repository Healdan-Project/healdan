// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.List;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.api.model.ClientEntryView;
import com.logicaldoc.healdan.navigation.RecentClients;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Clients (M6 spec section 9.1). Healdan has no list of clients, so the only
 * one is the caller's recent ones.
 */
@Component
@Path("/v1/clients")
@Produces(MediaType.APPLICATION_JSON)
public class ClientResource {

	/**
	 * @return the clients of the caller's Recent Matters and My Matters, each
	 *         once, by name; a client appears only through a matter the caller
	 *         can read
	 */
	@GET
	@Path("recent")
	public List<ClientEntryView> recent() {
		try {
			return RecentClients.get().list(Caller.current().user().getId()).stream()
					.map(c -> new ClientEntryView(c.number(), c.name())).toList();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list Recent Clients", e);
		}
	}
}
