package com.logicaldoc.gui.frontend.client.healdan.services;

import java.util.List;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.common.client.beans.GUIValue;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIFolderRole;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIMatter;

public interface HealdanMatterServiceAsync {

	void describeFolder(long folderId, AsyncCallback<GUIFolderRole> callback);

	void suggestMatterNumber(long clientId, AsyncCallback<Long> callback);

	void listStores(AsyncCallback<List<GUIValue>> callback);

	void listTemplates(AsyncCallback<List<GUIValue>> callback);

	void defaultStoreId(AsyncCallback<Long> callback);

	void create(GUIMatter matter, AsyncCallback<GUIMatter> callback);

	void getByFolder(long folderId, AsyncCallback<GUIMatter> callback);

	void update(GUIMatter matter, AsyncCallback<GUIMatter> callback);

	void myMatters(AsyncCallback<List<GUIMatter>> callback);

	void recentMatters(AsyncCallback<List<GUIMatter>> callback);

	void addToMyMatters(long folderId, AsyncCallback<Void> callback);

	void removeFromMyMatters(long folderId, AsyncCallback<Void> callback);

	void resolve(String input, AsyncCallback<List<GUIMatter>> callback);
}
