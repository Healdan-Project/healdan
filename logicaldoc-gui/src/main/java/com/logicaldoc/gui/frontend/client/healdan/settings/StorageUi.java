// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.settings;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.smartgwt.client.types.HeaderControls;
import com.smartgwt.client.widgets.HTMLFlow;
import com.smartgwt.client.widgets.IButton;
import com.smartgwt.client.widgets.Window;
import com.smartgwt.client.widgets.events.ClickHandler;
import com.smartgwt.client.widgets.layout.HLayout;
import com.smartgwt.client.widgets.layout.VLayout;

/**
 * What the Storage tab and its dialogs share: their windows, buttons and
 * counts, and how a Test's answer is shown
 */
final class StorageUi {

	private StorageUi() {
	}

	/**
	 * Makes a window a modal dialog that sizes itself to its content and is
	 * gone once closed, so nothing typed into it stays in a hidden field
	 *
	 * @param window the window
	 * @param title its title, as HTML: escape any name in it
	 * @param width its width
	 */
	static void modal(Window window, String title, int width) {
		window.setAutoSize(true);
		setUp(window, title, width);
	}

	/**
	 * Makes a window a modal dialog of a fixed size, which the user can
	 * resize, and which is gone once closed
	 *
	 * @param window the window
	 * @param title its title, as HTML: escape any name in it
	 * @param width its width
	 * @param height its height
	 */
	static void modal(Window window, String title, int width, int height) {
		window.setHeight(height);
		window.setCanDragResize(true);
		setUp(window, title, width);
	}

	private static void setUp(Window window, String title, int width) {
		window.setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		window.setTitle(title);
		window.setIsModal(true);
		window.setShowModalMask(true);
		window.setWidth(width);
		window.centerInPage();
		window.addCloseClickHandler(click -> window.destroy());
	}

	/** @return a dialog's content: its members one under another, as high as they need */
	static VLayout content() {
		VLayout content = new VLayout();
		content.setMargin(10);
		content.setMembersMargin(8);
		content.setWidth100();
		content.setAutoHeight();
		return content;
	}

	static IButton button(String title, ClickHandler handler) {
		IButton button = new IButton(title);
		button.setAutoFit(true);
		button.addClickHandler(handler);
		return button;
	}

	static HLayout buttons(IButton... members) {
		HLayout buttons = new HLayout();
		buttons.setMembersMargin(5);
		buttons.setHeight(1);
		buttons.setMembers(members);
		return buttons;
	}

	/** "1 file" or "214 files" */
	static String files(long count) {
		return count == 1 ? "1 file" : count + " files";
	}

	/** Whether the backend's files are encrypted: null is the kind's default, on for S3 and off for a local folder */
	static boolean encrypted(GUIStorageBackend backend) {
		return backend.getEncrypted() != null ? backend.getEncrypted() : backend.isS3();
	}

	/**
	 * Shows a Test's answer: that it passed, or each problem, as text
	 *
	 * @param name the backend's name
	 * @param problems what Test found; none if it passed
	 */
	static void showTest(String name, String[] problems) {
		Window window = new Window();
		modal(window, "Test of " + SafeHtmlUtils.htmlEscape(name), 560);

		VLayout content = content();
		HTMLFlow result = new HTMLFlow(
				problems.length == 0 ? "<b>Test passed.</b>" : "Test found " + problemsHtml(problems));
		result.setWidth100();
		content.addMember(result);
		content.addMember(buttons(button("Close", click -> window.destroy())));

		window.addItem(content);
		window.show();
	}

	/** @return "one problem:" or "3 problems:", then each, escaped, in a list */
	static String problemsHtml(String[] problems) {
		StringBuilder html = new StringBuilder();
		html.append(problems.length == 1 ? "one problem:" : problems.length + " problems:").append("<ul>");
		for (String problem : problems)
			html.append("<li>").append(SafeHtmlUtils.htmlEscape(problem)).append("</li>");
		return html.append("</ul>").toString();
	}
}
