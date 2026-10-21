package com.logicaldoc.gui.frontend.client.healdan.services;

import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwt.user.client.rpc.RemoteServiceRelativePath;
import com.google.gwt.user.client.rpc.ServiceDefTarget;
import com.logicaldoc.gui.common.client.LDRpcRequestBuilder;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISearchFolderRule;

/**
 * Search folders (spec 5.4): their rules, and the documents they list
 */
@RemoteServiceRelativePath("healdansearchfolder")
public interface HealdanSearchFolderService extends RemoteService {

	/**
	 * @param folderId a search folder the user can read
	 *
	 * @return its name and rule
	 *
	 * @throws ServerException the user can't read it, it isn't a search
	 *         folder, or an error happened in the server application
	 */
	public GUISearchFolderRule get(long folderId) throws ServerException;

	/**
	 * Creates a search folder
	 *
	 * @param parentId where; the user needs the add permission there
	 * @param rule its name and rule
	 *
	 * @return the new search folder's id
	 *
	 * @throws ServerException the search folder is refused (the message says
	 *         why), or an error happened in the server application
	 */
	public long create(long parentId, GUISearchFolderRule rule) throws ServerException;

	/**
	 * Changes a search folder's name and rule
	 *
	 * @param rule the search folder, its new name and rule
	 *
	 * @throws ServerException the change is refused (the message says why), or
	 *         an error happened in the server application
	 */
	public void update(GUISearchFolderRule rule) throws ServerException;

	/**
	 * @param folderId a search folder
	 *
	 * @return the ids of the documents it lists for the user, newest change
	 *         first, at most 1000
	 *
	 * @throws ServerException an error happened in the server application
	 */
	public List<Long> evaluate(long folderId) throws ServerException;

	/**
	 * @return the Class options
	 *
	 * @throws ServerException an error happened in the server application
	 */
	public List<String> classes() throws ServerException;

	/**
	 * @param docClass a Class
	 *
	 * @return the Subclass options that belong to it
	 *
	 * @throws ServerException an error happened in the server application
	 */
	public List<String> subclasses(String docClass) throws ServerException;

	public static class Instance {
		private static HealdanSearchFolderServiceAsync inst;

		private Instance() {
		}

		public static HealdanSearchFolderServiceAsync get() {
			if (inst == null) {
				inst = GWT.create(HealdanSearchFolderService.class);
				((ServiceDefTarget) inst).setRpcRequestBuilder(new LDRpcRequestBuilder());
			}
			return inst;
		}
	}
}
