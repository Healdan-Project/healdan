package com.logicaldoc.gui.frontend.client.healdan.services;

import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwt.user.client.rpc.RemoteServiceRelativePath;
import com.google.gwt.user.client.rpc.ServiceDefTarget;
import com.logicaldoc.gui.common.client.LDRpcRequestBuilder;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIClient;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIHealdanSettings;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUILinkedAccount;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInOptions;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInProvider;

/**
 * Healdan's settings, its client list (spec 8.6) and its sign-in providers (M4
 * spec section 10.3). Everything but {@link #labels()} is for administrators
 * only. No answer ever holds a provider's client secret.
 */
@RemoteServiceRelativePath("healdansettings")
public interface HealdanSettingsService extends RemoteService {

	/**
	 * @return the settings, with the Stores and Classes to choose from
	 *
	 * @throws ServerException the user isn't an administrator, or an error
	 *         happened in the server application
	 */
	public GUIHealdanSettings load() throws ServerException;

	/**
	 * Checks every value, then saves them all, or nothing.
	 *
	 * @param settings the settings to save
	 *
	 * @return the settings as saved
	 *
	 * @throws ServerException the user isn't an administrator, a value is
	 *         refused (the message says why), or an error happened in the server
	 *         application
	 */
	public GUIHealdanSettings save(GUIHealdanSettings settings) throws ServerException;

	/**
	 * @return the matter label, singular and plural
	 *
	 * @throws ServerException an error happened in the server application
	 */
	public String[] labels() throws ServerException;

	/**
	 * @return every client, lowest number first
	 *
	 * @throws ServerException the user isn't an administrator, or an error
	 *         happened in the server application
	 */
	public List<GUIClient> listClients() throws ServerException;

	/**
	 * Creates a client, or renames one. A client's number never changes.
	 *
	 * @param client the client; an id of zero creates it
	 *
	 * @return the client as saved
	 *
	 * @throws ServerException the user isn't an administrator, the number or
	 *         name is refused (the message says why), or an error happened in
	 *         the server application
	 */
	public GUIClient saveClient(GUIClient client) throws ServerException;

	/**
	 * @return the number to suggest for a new client: the highest plus 1
	 *
	 * @throws ServerException the user isn't an administrator, or an error
	 *         happened in the server application
	 */
	public long suggestClientNumber() throws ServerException;

	/**
	 * @return the sign-in providers, the password link's label, the {@code admin}
	 *         alert, whether outgoing email is set up, and the groups a provider
	 *         group can be mapped to
	 *
	 * @throws ServerException the user isn't an administrator, or an error
	 *         happened in the server application
	 */
	public GUISignInOptions loadSignIn() throws ServerException;

	/**
	 * Starts a provider for the tab's Add: the preset's values, with the redirect
	 * address to register. Nothing is saved until {@link #saveProvider}.
	 *
	 * @param id the new provider's id
	 * @param preset the preset's key: {@code entra}, {@code authentik} or
	 *        {@code other}
	 *
	 * @return the provider to fill in, marked as being added
	 *
	 * @throws ServerException the user isn't an administrator, the id isn't one
	 *         or is already used (the message says why), or an error happened in
	 *         the server application
	 */
	public GUISignInProvider newProvider(String id, String preset) throws ServerException;

	/**
	 * Adds a provider or changes one, with its group map. Rows of deleted groups
	 * are dropped. The values are checked before anything is saved.
	 *
	 * @param provider the provider; one marked as being added is refused if a
	 *        provider already has its id
	 * @param secret a new client secret; null or blank keeps the one stored
	 *
	 * @return the provider as saved
	 *
	 * @throws ServerException the user isn't an administrator, a value is
	 *         refused (the message says why), or an error happened in the server
	 *         application
	 */
	public GUISignInProvider saveProvider(GUISignInProvider provider, String secret) throws ServerException;

	/**
	 * Removes a provider that has no linked accounts. The record of the
	 * accounts it once linked stays.
	 *
	 * @param id the provider's id
	 *
	 * @throws ServerException the user isn't an administrator, accounts are
	 *         still linked to it, or an error happened in the server application
	 */
	public void removeProvider(String id) throws ServerException;

	/**
	 * Puts the providers' buttons in a new order
	 *
	 * @param ids the providers' ids in the new order
	 *
	 * @throws ServerException the user isn't an administrator, the list names an
	 *         unknown provider or one twice, or an error happened in the server
	 *         application
	 */
	public void reorderProviders(String[] ids) throws ServerException;

	/**
	 * Checks a provider's saved settings against what the provider publishes
	 *
	 * @param id the provider's id
	 *
	 * @return the redirect address to register, first, then each problem found;
	 *         only the address if the check passed
	 *
	 * @throws ServerException the user isn't an administrator, there is no such
	 *         provider, or an error happened in the server application
	 */
	public String[] checkProvider(String id) throws ServerException;

	/**
	 * Saves the options that aren't a provider's
	 *
	 * @param passwordLabel the label of the link to sign in with a password
	 * @param adminAlert whether to email the recipients when {@code admin} signs
	 *        in with its password
	 * @param recipients the usernames to email
	 *
	 * @throws ServerException the user isn't an administrator, a value is
	 *         refused (the message says why), or an error happened in the server
	 *         application
	 */
	public void saveSignInOptions(String passwordLabel, boolean adminAlert, String[] recipients)
			throws ServerException;

	/**
	 * @param id a provider's id
	 *
	 * @return the accounts linked to it, by username
	 *
	 * @throws ServerException the user isn't an administrator, or an error
	 *         happened in the server application
	 */
	public GUILinkedAccount[] linkedAccounts(String id) throws ServerException;

	/**
	 * Unlinks an account from its provider: it goes back to a password account,
	 * with a temporary password that's emailed when outgoing email is set up
	 *
	 * @param userId the account's id
	 *
	 * @return the temporary password, if it wasn't emailed, for the
	 *         administrator to pass on; else null
	 *
	 * @throws ServerException the user isn't an administrator, the account isn't
	 *         linked, or an error happened in the server application
	 */
	public String unlink(long userId) throws ServerException;

	/**
	 * Unlinks every account linked to a provider, each as {@link #unlink} does
	 *
	 * @param id the provider's id
	 *
	 * @return {@code username: password} for each temporary password that
	 *         wasn't emailed
	 *
	 * @throws ServerException the user isn't an administrator, or an error
	 *         happened in the server application
	 */
	public String[] unlinkEveryone(String id) throws ServerException;

	/**
	 * @param id a provider's id
	 *
	 * @return how many accounts are linked to it
	 *
	 * @throws ServerException the user isn't an administrator, or an error
	 *         happened in the server application
	 */
	public int countLinked(String id) throws ServerException;

	public static class Instance {
		private static HealdanSettingsServiceAsync inst;

		private Instance() {
		}

		public static HealdanSettingsServiceAsync get() {
			if (inst == null) {
				inst = GWT.create(HealdanSettingsService.class);
				((ServiceDefTarget) inst).setRpcRequestBuilder(new LDRpcRequestBuilder());
			}
			return inst;
		}
	}
}
