package com.logicaldoc.healdan.matter;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.util.spring.Context;

/**
 * Creating and renaming clients. Admins only.
 */
@Component
public class ClientService {

	/** The longest client name the table holds */
	static final int MAX_NAME_LENGTH = 255;

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
	 *         positive or is taken, or the name is empty or too long
	 * @throws PersistenceException error at data layer
	 */
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
		return client;
	}

	/**
	 * @param clientId the id of the client
	 * @param name the new name; it is trimmed
	 * @param actor the user who asks
	 * @return the client
	 * @throws HealdanRuleException if the actor isn't an admin, the client
	 *         doesn't exist, or the name is empty or too long
	 * @throws PersistenceException error at data layer
	 */
	public Client rename(long clientId, String name, User actor) throws PersistenceException {
		requireAdmin(actor);
		String clean = cleanName(name);
		Client client = ClientDAO.get().findById(clientId);
		if (client == null)
			throw new HealdanRuleException("There is no such client.");

		client.setName(clean);
		ClientDAO.get().store(client);
		return client;
	}

	/**
	 * @return the number to pre-fill for a new client: the highest plus 1
	 * @throws PersistenceException error at data layer
	 */
	public long suggestNumber() throws PersistenceException {
		return ClientDAO.get().maxNumber() + 1;
	}

	private static void requireAdmin(User actor) throws HealdanRuleException {
		if (actor == null || !actor.isAdmin())
			throw new HealdanRuleException("Only an administrator can create or rename clients.");
	}

	/*
	 * A client name may contain '/': it never becomes a folder name
	 */
	private static String cleanName(String name) throws HealdanRuleException {
		String clean = name == null ? "" : name.strip();
		if (clean.isEmpty())
			throw new HealdanRuleException("The client name cannot be empty.");
		if (clean.length() > MAX_NAME_LENGTH)
			throw new HealdanRuleException(
					"The client name cannot be longer than %d characters.".formatted(MAX_NAME_LENGTH));
		return clean;
	}
}
