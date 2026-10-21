package com.logicaldoc.gui.frontend.client.healdan.services;

import java.util.List;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISearchFolderRule;

public interface HealdanSearchFolderServiceAsync {

	void get(long folderId, AsyncCallback<GUISearchFolderRule> callback);

	void create(long parentId, GUISearchFolderRule rule, AsyncCallback<Long> callback);

	void update(GUISearchFolderRule rule, AsyncCallback<Void> callback);

	void evaluate(long folderId, AsyncCallback<List<Long>> callback);

	void classes(AsyncCallback<List<String>> callback);

	void subclasses(String docClass, AsyncCallback<List<String>> callback);
}
