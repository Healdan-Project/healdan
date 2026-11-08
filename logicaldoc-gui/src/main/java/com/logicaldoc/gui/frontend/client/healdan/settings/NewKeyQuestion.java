// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.settings;

import com.logicaldoc.gui.common.client.util.LD;

/**
 * The question both Healdan Settings tabs ask when a typed secret was refused
 * because Healdan's secret key file is missing while values are sealed with
 * it, and nothing was saved. Only the administrator decides on a new key,
 * which leaves those values unreadable (M4 ruling R37), so the wording names
 * every kind of sealed secret, wherever it was typed.
 */
final class NewKeyQuestion {

	private NewKeyQuestion() {
	}

	/**
	 * Asks whether to make a new secret key
	 *
	 * @param typed what was typed, such as "client secret" or "S3 secret"
	 * @param sealed how many stored values are sealed with the missing key
	 * @param onYes what to do if the administrator makes a new key: save again
	 *        with {@code newKey} set
	 */
	static void ask(String typed, long sealed, Runnable onYes) {
		String values = sealed == 1 ? "1 stored value is" : sealed + " stored values are";
		LD.ask("Make a New Secret Key", "Healdan's secret key file is missing, and " + values + " sealed with it: "
				+ "client secrets, the S3 secrets of storage backends, and the refresh tokens of Healdan Helpers that "
				+ "signed in through a provider. If it went missing by mistake, restore it from a backup and save "
				+ "again: nothing needs restarting, and nothing is lost.<br><br>A new key keeps the " + typed
				+ " you typed, and nothing sealed before can be opened: you enter each other provider's client secret "
				+ "again on the Sign-In tab, and each other storage backend's S3 secret on the Storage tab (those "
				+ "backends fail until then); Healdan Helpers that signed in through a provider sign in again. Make a "
				+ "new key?", 480, yes -> {
					if (Boolean.TRUE.equals(yes))
						onYes.run();
				});
	}
}
