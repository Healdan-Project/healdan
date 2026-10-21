package com.logicaldoc.gui.frontend.client.healdan.services;

import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwt.user.client.rpc.RemoteServiceRelativePath;
import com.google.gwt.user.client.rpc.ServiceDefTarget;
import com.logicaldoc.gui.common.client.LDRpcRequestBuilder;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIValue;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIFolderRole;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIMatter;

/**
 * Matters: creating and editing them (spec 5.3), what the folder menu needs to
 * know, and My Matters and Recent Matters (spec 8.2). Creating, editing and
 * the lists the dialogs pick from are for administrators only.
 */
@RemoteServiceRelativePath("healdanmatter")
public interface HealdanMatterService extends RemoteService {

	/**
	 * @param folderId a folder the user can read
	 *
	 * @return what the folder is in Healdan's layout
	 *
	 * @throws ServerException the user can't read the folder, or an error
	 *         happened in the server application
	 */
	public GUIFolderRole describeFolder(long folderId) throws ServerException;

	/**
	 * @param clientId the client
	 *
	 * @return the number to suggest for the client's next matter
	 *
	 * @throws ServerException the user isn't an administrator, or an error
	 *         happened in the server application
	 */
	public long suggestMatterNumber(long clientId) throws ServerException;

	/**
	 * @return the Stores, by name: the folder id as code, the name as value
	 *
	 * @throws ServerException the user isn't an administrator, or an error
	 *         happened in the server application
	 */
	public List<GUIValue> listStores() throws ServerException;

	/**
	 * @return the workspace templates, by name: the folder id as code, the
	 *         name as value
	 *
	 * @throws ServerException the user isn't an administrator, or an error
	 *         happened in the server application
	 */
	public List<GUIValue> listTemplates() throws ServerException;

	/**
	 * Creates a matter and its workspace from a template, all or nothing.
	 *
	 * @param matter the client, number, name, description, Store, owner and
	 *        template
	 *
	 * @return the matter created
	 *
	 * @throws ServerException the user isn't an administrator, the matter is
	 *         refused (the message says why), or an error happened in the
	 *         server application
	 */
	public GUIMatter create(GUIMatter matter) throws ServerException;

	/**
	 * @param folderId a matter's workspace
	 *
	 * @return the matter
	 *
	 * @throws ServerException the user isn't an administrator, the folder
	 *         isn't a matter's workspace, or an error happened in the server
	 *         application
	 */
	public GUIMatter getByFolder(long folderId) throws ServerException;

	/**
	 * Changes a matter's name, description and owner. The client and number
	 * never change.
	 *
	 * @param matter the matter
	 *
	 * @return the matter as saved
	 *
	 * @throws ServerException the user isn't an administrator, the change is
	 *         refused (the message says why), or an error happened in the
	 *         server application
	 */
	public GUIMatter update(GUIMatter matter) throws ServerException;

	/**
	 * @return the user's My Matters, by display: each with its id, workspace,
	 *         display and client name
	 *
	 * @throws ServerException an error happened in the server application
	 */
	public List<GUIMatter> myMatters() throws ServerException;

	/**
	 * @return the matters the user changed a document in recently, newest
	 *         first: each with its id, workspace, display and client name
	 *
	 * @throws ServerException an error happened in the server application
	 */
	public List<GUIMatter> recentMatters() throws ServerException;

	/**
	 * Adds a matter to the user's My Matters, unless it's there already
	 *
	 * @param folderId a matter's workspace
	 *
	 * @throws ServerException the folder isn't a matter's workspace or the
	 *         user can't read it (the message says why), or an error happened
	 *         in the server application
	 */
	public void addToMyMatters(long folderId) throws ServerException;

	/**
	 * Removes a matter from the user's My Matters, if it's there. A folder
	 * that isn't a matter's workspace is left alone, with any CE bookmark on
	 * it
	 *
	 * @param folderId a matter's workspace
	 *
	 * @throws ServerException an error happened in the server application
	 */
	public void removeFromMyMatters(long folderId) throws ServerException;

	/**
	 * Task 25
	 *
	 * @param input what the user typed, such as {@code 998.1}
	 *
	 * @return the matters it names
	 *
	 * @throws ServerException an error happened in the server application
	 */
	public List<GUIMatter> resolve(String input) throws ServerException;

	public static class Instance {
		private static HealdanMatterServiceAsync inst;

		private Instance() {
		}

		public static HealdanMatterServiceAsync get() {
			if (inst == null) {
				inst = GWT.create(HealdanMatterService.class);
				((ServiceDefTarget) inst).setRpcRequestBuilder(new LDRpcRequestBuilder());
			}
			return inst;
		}
	}
}
