package com.logicaldoc.gui.frontend.client.healdan;

import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.common.client.beans.GUIDocument;
import com.logicaldoc.gui.common.client.healdan.HealdanOpenInApp;
import com.logicaldoc.gui.common.client.log.GuiLog;
import com.logicaldoc.gui.common.client.util.Util;
import com.logicaldoc.gui.common.client.util.WindowUtils;
import com.logicaldoc.gui.common.client.widgets.ToastNotification;
import com.logicaldoc.gui.frontend.client.services.DocumentService;

/**
 * Opens a document in the helper app (M3 spec 8): finds its doc # from CE's id
 * and opens a {@code healdan:} link, which carries no credentials. The browser
 * gives no sign whether the helper took the link, so the notice says what to do
 * if nothing opened. English only, like the rest of Healdan's GUI text.
 */
public final class HealdanDocumentOpener {

	/** The docs page that explains installing the helper */
	public static final String INSTALL_URL = "https://docs.healdan.com/user/office/installing-the-helper/";

	/** The notice shown after the link is opened */
	public static final String NOTICE = "If nothing opened, <a href=\"" + INSTALL_URL
			+ "\" target=\"_blank\">install the Healdan Helper</a>.";

	private static boolean registered = false;

	private HealdanDocumentOpener() {
	}

	/** Hands the web page's Open in App item to this class; safe to call again */
	public static void register() {
		if (registered)
			return;
		registered = true;
		HealdanOpenInApp.setOpener(HealdanDocumentOpener::open);
	}

	private static void open(long docId) {
		DocumentService.Instance.get().getById(docId, new AsyncCallback<GUIDocument>() {
			@Override
			public void onFailure(Throwable caught) {
				GuiLog.serverError(caught);
			}

			@Override
			public void onSuccess(GUIDocument document) {
				if (document == null || document.getCustomId() == null || document.getCustomId().isEmpty())
					return;
				openLink(HealdanOpenInApp.link(document.getCustomId(), Window.Location.getHost()));
			}
		});
	}

	private static void openLink(String link) {
		Util.uninstallCloseWindowAlert();
		try {
			WindowUtils.openUrl(link);
			ToastNotification.showNotification(NOTICE);
		} finally {
			Util.installCloseWindowAlert();
		}
	}
}
