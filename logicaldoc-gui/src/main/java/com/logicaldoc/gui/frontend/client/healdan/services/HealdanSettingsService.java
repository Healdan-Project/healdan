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

/**
 * Healdan's settings and its client list (spec 8.6). Everything but
 * {@link #labels()} is for administrators only.
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
