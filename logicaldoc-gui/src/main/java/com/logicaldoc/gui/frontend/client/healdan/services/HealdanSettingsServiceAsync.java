package com.logicaldoc.gui.frontend.client.healdan.services;

import java.util.List;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIClient;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIHealdanSettings;

public interface HealdanSettingsServiceAsync {

	void load(AsyncCallback<GUIHealdanSettings> callback);

	void save(GUIHealdanSettings settings, AsyncCallback<GUIHealdanSettings> callback);

	void labels(AsyncCallback<String[]> callback);

	void listClients(AsyncCallback<List<GUIClient>> callback);

	void saveClient(GUIClient client, AsyncCallback<GUIClient> callback);

	void suggestClientNumber(AsyncCallback<Long> callback);
}
