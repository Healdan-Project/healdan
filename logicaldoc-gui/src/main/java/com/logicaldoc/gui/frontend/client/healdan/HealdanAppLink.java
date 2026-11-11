// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan;

import com.logicaldoc.gui.common.client.util.AwesomeFactory;
import com.logicaldoc.gui.common.client.util.Util;
import com.logicaldoc.gui.common.client.util.WindowUtils;
import com.smartgwt.client.widgets.toolbar.ToolStrip;
import com.smartgwt.client.widgets.toolbar.ToolStripButton;

/**
 * The way from the old UI back to the User app (M6 spec 8.6): an
 * <b>Open in Healdan</b> button in the menu bar that goes to {@code /app/}.
 * English only, like the rest of Healdan's GUI text. The app is served at the
 * server's root only, so the address is the context path and {@code app/}.
 */
public final class HealdanAppLink {

	/** The button's text, as the User app's own links name it */
	public static final String LABEL = "Open in Healdan";

	private HealdanAppLink() {
	}

	/**
	 * Adds the button to the menu bar.
	 * 
	 * @param bar the menu bar
	 */
	public static void add(ToolStrip bar) {
		ToolStripButton button = new ToolStripButton();
		button.setAutoFit(true);
		button.setTitle(AwesomeFactory.getIconHtml("arrow-up-right-from-square", LABEL));
		button.setTooltip(LABEL);
		button.addClickHandler(event -> WindowUtils.openUrl(address(Util.contextPath())));
		bar.addButton(button);
	}

	/**
	 * @param contextPath the web page's address, ending in a slash, as
	 *        {@link Util#contextPath()} gives it
	 * @return the User app's address
	 */
	static String address(String contextPath) {
		return contextPath + "app/";
	}
}
