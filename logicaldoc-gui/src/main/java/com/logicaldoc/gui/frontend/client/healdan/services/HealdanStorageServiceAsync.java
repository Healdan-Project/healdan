// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.services;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageEvent;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageOverview;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStuckFile;

public interface HealdanStorageServiceAsync {

	void load(AsyncCallback<GUIStorageOverview> callback);

	void testNew(GUIStorageBackend backend, String secret, AsyncCallback<String[]> callback);

	void addBackend(GUIStorageBackend backend, String secret, AsyncCallback<GUIStorageBackend> callback);

	void confirmRecoveryKey(long backendId, String lastGroup, AsyncCallback<Void> callback);

	void saveBackend(GUIStorageBackend backend, String secret, AsyncCallback<GUIStorageBackend> callback);

	void testBackend(long backendId, AsyncCallback<String[]> callback);

	void showRecoveryKey(long backendId, AsyncCallback<String> callback);

	void enterRecoveryKey(long backendId, String typed, AsyncCallback<Void> callback);

	void keyEvents(long backendId, AsyncCallback<GUIStorageEvent[]> callback);

	void removeBackend(long backendId, AsyncCallback<Void> callback);

	void previewStoreSwitch(long storeId, long backendId, AsyncCallback<long[]> callback);

	void setStoreBackend(long storeId, long backendId, AsyncCallback<Void> callback);

	void stuckFiles(long storeId, AsyncCallback<GUIStuckFile[]> callback);

	void retry(long storeId, AsyncCallback<Void> callback);

	void saveSettings(int holdDays, int cacheGb, int transfers, AsyncCallback<Void> callback);
}
