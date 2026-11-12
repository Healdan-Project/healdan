// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.util.spring.Context;

/**
 * Recent Clients (M6 spec section 6.1): the clients of the user's Recent
 * Matters and My Matters, since Healdan has no list of clients. Both lists
 * hold only matters the user can read, so a client appears only through one.
 */
@Component
public class RecentClients {

	/**
	 * A client in the list
	 *
	 * @param id the client
	 * @param number its number
	 * @param name its name
	 */
	public record ClientEntry(long id, long number, String name) {
	}

	/**
	 * @return the bean
	 */
	public static RecentClients get() {
		return Context.get(RecentClients.class);
	}

	/**
	 * @param userId the user
	 * @return each client of the user's recent matters and My Matters once, by
	 *         name (ignoring case), then by number
	 * @throws PersistenceException error at data layer
	 */
	public List<ClientEntry> list(long userId) throws PersistenceException {
		List<MatterEntry> matters = new ArrayList<>(RecentMatters.get().list(userId));
		matters.addAll(MyMatters.get().list(userId));

		Set<Long> seen = new HashSet<>();
		List<ClientEntry> clients = new ArrayList<>();
		for (MatterEntry entry : matters) {
			var matter = MatterDAO.get().findById(entry.matterId());
			if (matter == null || !seen.add(matter.getClientId()))
				continue;
			Client client = ClientDAO.get().findById(matter.getClientId());
			if (client != null)
				clients.add(new ClientEntry(client.getId(), client.getNumber(), client.getName()));
		}
		clients.sort(Comparator.comparing((ClientEntry c) -> c.name().toLowerCase(Locale.ROOT))
				.thenComparingLong(ClientEntry::number));
		return clients;
	}
}
