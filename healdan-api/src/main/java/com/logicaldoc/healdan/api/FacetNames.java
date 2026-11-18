// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.api.model.NamedFacetCount;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.security.FolderReads;

/**
 * The Client and Matter filters' options with the names people know them by.
 * The options are numbers from the properties of documents the caller can
 * read. A matter is named only if the caller can read its own folder, the rule
 * of {@code GET /matters/{id}}, and a client only through such a matter, as in
 * Recent Clients; any other option shows its number.
 */
final class FacetNames {

	/** As long a number as a {@code long} always holds */
	private static final Pattern NUMBER = Pattern.compile("\\d{1,18}");

	private final Map<String, Long> clientOptions;

	private final Map<String, Long> matterOptions;

	private final FolderReads reads;

	private final Map<String, Client> clientsByNumber = new HashMap<>();

	private final Map<Long, List<Matter>> mattersByClient = new HashMap<>();

	/**
	 * @param clientOptions each client number's count; null if not asked for
	 * @param matterOptions each matter's count, by client and matter number;
	 *        null if not asked for
	 * @param reads what the caller can read
	 * @throws PersistenceException error at data layer
	 */
	FacetNames(Map<String, Long> clientOptions, Map<String, Long> matterOptions, FolderReads reads)
			throws PersistenceException {
		this.clientOptions = clientOptions;
		this.matterOptions = matterOptions;
		this.reads = reads;

		Set<Long> numbers = new HashSet<>();
		if (clientOptions != null)
			clientOptions.keySet().forEach(value -> addNumber(numbers, value));
		if (matterOptions != null)
			matterOptions.keySet().forEach(value -> addNumber(numbers, StringUtils.substringBefore(value, ".")));

		// Two queries however many options there are
		Map<Long, Client> clientsById = new HashMap<>();
		for (Client client : ClientDAO.get().findByNumbers(numbers)) {
			clientsById.put(client.getId(), client);
			clientsByNumber.put(Long.toString(client.getNumber()), client);
		}
		for (Matter matter : MatterDAO.get().findLiveByClients(clientsById.keySet()))
			mattersByClient.computeIfAbsent(matter.getClientId(), id -> new ArrayList<>()).add(matter);
	}

	private static void addNumber(Set<Long> numbers, String value) {
		if (NUMBER.matcher(value).matches())
			numbers.add(Long.valueOf(value));
	}

	/**
	 * @return the client options in their order; null if not asked for
	 * @throws PersistenceException error at data layer
	 */
	List<NamedFacetCount> clients() throws PersistenceException {
		if (clientOptions == null)
			return null;
		List<NamedFacetCount> options = new ArrayList<>();
		for (Map.Entry<String, Long> option : clientOptions.entrySet()) {
			Client client = clientsByNumber.get(option.getKey());
			String name = client != null && readsAMatterOf(client)
					? MatterDisplay.client(client.getNumber(), client.getName())
					: option.getKey();
			options.add(new NamedFacetCount(option.getKey(), name, option.getValue()));
		}
		return options;
	}

	/**
	 * @return the matter options in their order; null if not asked for
	 * @throws PersistenceException error at data layer
	 */
	List<NamedFacetCount> matters() throws PersistenceException {
		if (matterOptions == null)
			return null;
		List<NamedFacetCount> options = new ArrayList<>();
		for (Map.Entry<String, Long> option : matterOptions.entrySet())
			options.add(new NamedFacetCount(option.getKey(), matterName(option.getKey()), option.getValue()));
		return options;
	}

	private String matterName(String value) throws PersistenceException {
		Client client = clientsByNumber.get(StringUtils.substringBefore(value, "."));
		if (client == null)
			return value;
		for (Matter matter : mattersByClient.getOrDefault(client.getId(), List.of()))
			if (MatterDisplay.number(client.getNumber(), matter.getNumber()).equals(value))
				return reads.readable(matter.getFolderId())
						? MatterDisplay.format(client.getNumber(), matter.getNumber(), matter.getName())
						: value;
		return value;
	}

	private boolean readsAMatterOf(Client client) throws PersistenceException {
		for (Matter matter : mattersByClient.getOrDefault(client.getId(), List.of()))
			if (reads.readable(matter.getFolderId()))
				return true;
		return false;
	}
}
