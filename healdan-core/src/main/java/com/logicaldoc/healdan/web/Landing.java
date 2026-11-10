// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import com.logicaldoc.healdan.setting.HealdanSettings;

import jakarta.servlet.http.HttpServletRequest;

/** Where a plain sign-in lands, by the <code>ui.start</code> setting (M6 spec 8.4) */
public final class Landing {

	public static final String APP = "/app/";

	public static final String CLASSIC = "/frontend.jsp";

	private Landing() {
	}

	/** @return {@link #APP} or {@link #CLASSIC}, with no context path */
	public static String page() {
		return "classic".equals(HealdanSettings.get().uiStart()) ? CLASSIC : APP;
	}

	/** @return the context path and {@link #page()} */
	public static String start(HttpServletRequest request) {
		return request.getContextPath() + page();
	}
}
