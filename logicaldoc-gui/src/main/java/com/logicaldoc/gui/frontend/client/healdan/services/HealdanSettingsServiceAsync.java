package com.logicaldoc.gui.frontend.client.healdan.services;

import java.util.List;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIClient;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIHealdanSettings;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUILinkedAccount;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInOptions;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInProvider;

public interface HealdanSettingsServiceAsync {

	void load(AsyncCallback<GUIHealdanSettings> callback);

	void save(GUIHealdanSettings settings, AsyncCallback<GUIHealdanSettings> callback);

	void labels(AsyncCallback<String[]> callback);

	void listClients(AsyncCallback<List<GUIClient>> callback);

	void saveClient(GUIClient client, AsyncCallback<GUIClient> callback);

	void suggestClientNumber(AsyncCallback<Long> callback);

	void loadSignIn(AsyncCallback<GUISignInOptions> callback);

	void newProvider(String id, String preset, AsyncCallback<GUISignInProvider> callback);

	void saveProvider(GUISignInProvider provider, String secret, AsyncCallback<GUISignInProvider> callback);

	void removeProvider(String id, AsyncCallback<Void> callback);

	void reorderProviders(String[] ids, AsyncCallback<Void> callback);

	void checkProvider(String id, AsyncCallback<String[]> callback);

	void saveSignInOptions(String passwordLabel, boolean adminAlert, String[] recipients,
			AsyncCallback<Void> callback);

	void linkedAccounts(String id, AsyncCallback<GUILinkedAccount[]> callback);

	void unlink(long userId, AsyncCallback<String> callback);

	void unlinkEveryone(String id, AsyncCallback<String[]> callback);

	void countLinked(String id, AsyncCallback<Integer> callback);
}
