// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.matter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.security.FolderReads;

/**
 * The names people know clients and matters by, for the Client and Matter
 * filters' options. The options are numbers from the properties of documents
 * the caller can read. A matter is named only if the caller can read its own
 * folder, the rule of {@code GET /matters/{id}}, and a client only through such
 * a matter, as in Recent Clients; any other option keeps its number.
 */
public final class MatterNames {

	/** As long a number as a {@code long} always holds */
	private static final Pattern NUMBER = Pattern.compile("\\d{1,18}");

	private final Map<String, String> clients = new HashMap<>();

	private final Map<String, String> matters = new HashMap<>();

	/**
	 * @param clientOptions client numbers, such as {@code 998}
	 * @param matterOptions client and matter numbers, such as {@code 998.1}
	 * @param reads what the caller can read
	 * @throws PersistenceException error at data layer
	 */
	public MatterNames(Set<String> clientOptions, Set<String> matterOptions, FolderReads reads)
			throws PersistenceException {
		Set<Long> clientNumbers = new HashSet<>();
		Set<Long> matterNumbers = new HashSet<>();
		clientOptions.forEach(option -> addNumber(clientNumbers, option));
		for (String option : matterOptions) {
			addNumber(clientNumbers, StringUtils.substringBefore(option, "."));
			addNumber(matterNumbers, StringUtils.substringAfter(option, "."));
		}

		Map<Long, Client> found = new HashMap<>();
		Set<Long> offered = new HashSet<>();
		for (Client client : ClientDAO.get().findByNumbers(clientNumbers)) {
			found.put(client.getId(), client);
			if (clientOptions.contains(Long.toString(client.getNumber())))
				offered.add(client.getId());
		}

		for (long id : MatterDAO.get().findClientsWithReadableMatter(offered, reads.user())) {
			Client client = found.get(id);
			clients.put(Long.toString(client.getNumber()), MatterDisplay.client(client.getNumber(), client.getName()));
		}
		for (Matter matter : MatterDAO.get().findLiveByClientsAndNumbers(found.keySet(), matterNumbers)) {
			Client client = found.get(matter.getClientId());
			String number = MatterDisplay.number(client.getNumber(), matter.getNumber());
			if (matterOptions.contains(number) && reads.readable(matter.getFolderId()))
				matters.put(number, MatterDisplay.format(client.getNumber(), matter.getNumber(), matter.getName()));
		}
	}

	private static void addNumber(Set<Long> numbers, String value) {
		if (NUMBER.matcher(value).matches())
			numbers.add(Long.valueOf(value));
	}

	/**
	 * @param option one of the client options
	 * @return the client as people read it, or the option itself
	 */
	public String client(String option) {
		return clients.getOrDefault(option, option);
	}

	/**
	 * @param option one of the matter options
	 * @return the matter as people read it, or the option itself
	 */
	public String matter(String option) {
		return matters.getOrDefault(option, option);
	}
}
