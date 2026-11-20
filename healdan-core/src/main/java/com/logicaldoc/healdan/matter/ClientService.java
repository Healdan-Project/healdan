// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.matter;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.util.StorableText;
import com.logicaldoc.util.spring.Context;

/**
 * Creating and renaming clients. Admins only. Each change is recorded in the
 * administration record inside the change's transaction, so a failed record
 * undoes the change. The record names the client as people see it, by number
 * and name ({@link MatterDisplay#client}), as a matter's record names the
 * matter.
 */
@Component
public class ClientService {

	/** The longest client name the table holds */
	static final int MAX_NAME_LENGTH = 255;

	/** What the administration record calls a client */
	private static final String CLIENT = "client";

	private final AdminEvents events;

	@Autowired
	public ClientService(AdminEvents events) {
		this.events = events;
	}

	/**
	 * @return the service bean
	 */
	public static ClientService get() {
		return Context.get(ClientService.class);
	}

	/**
	 * @param number the client number
	 * @param name the client name; it is trimmed
	 * @param actor the user who asks
	 * @return the new client
	 * @throws HealdanRuleException if the actor isn't an admin, the number is not
	 *         positive or is taken, or the name is empty, too long or holds a
	 *         character Healdan can't keep
	 * @throws PersistenceException error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public Client create(long number, String name, User actor) throws PersistenceException {
		requireAdmin(actor);
		if (number < 1)
			throw new HealdanRuleException("The client number must be a positive whole number.");
		String clean = cleanName(name);
		if (ClientDAO.get().findByNumber(number) != null)
			throw new HealdanRuleException("Client number %d is already used.".formatted(number));

		Client client = new Client();
		client.setNumber(number);
		client.setName(clean);
		ClientDAO.get().store(client);
		events.record(new AdminEvent(AdminArea.MATTERS, "client.created", CLIENT, client.getId(),
				MatterDisplay.client(number, clean),
				Map.of("number", new Change(null, String.valueOf(number)), "name", new Change(null, clean))), actor);
		return client;
	}

	/**
	 * A rename to the name the client already has changes nothing, and isn't
	 * recorded.
	 *
	 * @param clientId the id of the client
	 * @param name the new name; it is trimmed
	 * @param actor the user who asks
	 * @return the client
	 * @throws HealdanRuleException if the actor isn't an admin, the client
	 *         doesn't exist, or the name is empty, too long or holds a character
	 *         Healdan can't keep
	 * @throws PersistenceException error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public Client rename(long clientId, String name, User actor) throws PersistenceException {
		requireAdmin(actor);
		String clean = cleanName(name);
		Client client = ClientDAO.get().findById(clientId);
		if (client == null)
			throw new HealdanRuleException("There is no such client.");

		String before = client.getName();
		client.setName(clean);
		ClientDAO.get().store(client);
		if (!clean.equals(before))
			events.record(new AdminEvent(AdminArea.MATTERS, "client.renamed", CLIENT, client.getId(),
					MatterDisplay.client(client.getNumber(), clean), Map.of("name", new Change(before, clean))), actor);
		return client;
	}

	/**
	 * @return the number to pre-fill for a new client: the highest plus 1
	 * @throws PersistenceException error at data layer
	 */
	public long suggestNumber() throws PersistenceException {
		return ClientDAO.get().maxNumber() + 1;
	}

	/*
	 * FilingRules reloads the user's groups, which are transient, and counts a
	 * seed as an admin
	 */
	private static void requireAdmin(User actor) throws PersistenceException {
		if (!FilingRules.get().isAdmin(actor))
			throw new HealdanRuleException("Only an administrator can create or rename clients.");
	}

	/*
	 * A client name may contain '/': it never becomes a folder name. A NUL would
	 * fail the insert, and a lone surrogate isn't a character (StorableText)
	 */
	private static String cleanName(String name) throws HealdanRuleException {
		String clean = name == null ? "" : name.strip();
		if (clean.isEmpty())
			throw new HealdanRuleException("The client name cannot be empty.");
		if (clean.length() > MAX_NAME_LENGTH)
			throw new HealdanRuleException(
					"The client name cannot be longer than %d characters.".formatted(MAX_NAME_LENGTH));
		if (!StorableText.holds(clean))
			throw new HealdanRuleException("The client name cannot contain " + StorableText.CANT_KEEP + ".");
		return clean;
	}
}
