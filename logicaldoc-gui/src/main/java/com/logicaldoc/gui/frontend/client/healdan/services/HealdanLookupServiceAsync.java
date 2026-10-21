package com.logicaldoc.gui.frontend.client.healdan.services;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIDocNumberResult;

public interface HealdanLookupServiceAsync {

	void lookup(String input, AsyncCallback<GUIDocNumberResult> callback);
}
