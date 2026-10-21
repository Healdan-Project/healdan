package com.logicaldoc.gui.frontend.client.healdan.services;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwt.user.client.rpc.RemoteServiceRelativePath;
import com.google.gwt.user.client.rpc.ServiceDefTarget;
import com.logicaldoc.gui.common.client.LDRpcRequestBuilder;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIDocNumberResult;

/**
 * Doc # lookups (spec 6.5), for the search box
 */
@RemoteServiceRelativePath("healdanlookup")
public interface HealdanLookupService extends RemoteService {

	/**
	 * @param input what the user typed, such as {@code #1234.2}
	 *
	 * @return the document and version found, or a message saying why there is
	 *         none; a document the user can't read is reported as one that
	 *         doesn't exist
	 *
	 * @throws ServerException an error happened in the server application
	 */
	public GUIDocNumberResult lookup(String input) throws ServerException;

	public static class Instance {
		private static HealdanLookupServiceAsync inst;

		private Instance() {
		}

		public static HealdanLookupServiceAsync get() {
			if (inst == null) {
				inst = GWT.create(HealdanLookupService.class);
				((ServiceDefTarget) inst).setRpcRequestBuilder(new LDRpcRequestBuilder());
			}
			return inst;
		}
	}
}
