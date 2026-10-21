package com.logicaldoc.gui.frontend.client.healdan;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.common.client.log.GuiLog;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSettingsService;

/**
 * The configurable matter label (spec 5.1), loaded from the server once and
 * cached. Until it has loaded, the getters give "Matter" and "Matters".
 */
public final class HealdanLabels {

	private static String singular = "Matter";

	private static String plural = "Matters";

	private static boolean loaded = false;

	private HealdanLabels() {
	}

	/**
	 * Loads the labels, unless they already are
	 *
	 * @param then what to run once they are loaded, or have failed to load;
	 *        may be null
	 */
	public static void load(Runnable then) {
		if (loaded) {
			run(then);
			return;
		}

		HealdanSettingsService.Instance.get().labels(new AsyncCallback<String[]>() {
			@Override
			public void onFailure(Throwable caught) {
				GuiLog.serverError(caught);
				run(then);
			}

			@Override
			public void onSuccess(String[] labels) {
				set(labels[0], labels[1]);
				run(then);
			}
		});
	}

	/**
	 * Replaces the cached labels, e.g. after the settings were saved
	 *
	 * @param singular the singular label
	 * @param plural the plural label
	 */
	public static void set(String singular, String plural) {
		HealdanLabels.singular = singular;
		HealdanLabels.plural = plural;
		loaded = true;
	}

	/**
	 * @return the singular label, such as "Matter" or "Project"
	 */
	public static String singular() {
		return singular;
	}

	/**
	 * @return the plural label, such as "Matters" or "Projects"
	 */
	public static String plural() {
		return plural;
	}

	private static void run(Runnable then) {
		if (then != null)
			then.run();
	}
}
