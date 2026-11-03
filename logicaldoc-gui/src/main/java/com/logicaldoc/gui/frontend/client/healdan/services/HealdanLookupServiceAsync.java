// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.services;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIDocNumberResult;

public interface HealdanLookupServiceAsync {

	void lookup(String input, AsyncCallback<GUIDocNumberResult> callback);
}
