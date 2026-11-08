// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.settings;

import com.logicaldoc.gui.common.client.util.LD;

/**
 * The question both Healdan Settings tabs ask when a typed secret was refused
 * because Healdan's secret key file is missing while values are sealed with
 * it, and nothing was saved. Only the administrator decides on a new key,
 * which leaves those values unreadable (M4 ruling R37), so the wording names
 * every kind of sealed secret, and which of them must be entered again.
 */
final class NewKeyQuestion {

	/** The kind of secret the administrator typed, which the new key keeps */
	enum Secret {
		/** A sign-in provider's client secret, on the Sign-In tab */
		CLIENT,

		/** A storage backend's S3 secret, on the Storage tab */
		S3
	}

	private NewKeyQuestion() {
	}

	/**
	 * Asks whether to make a new secret key
	 *
	 * @param typed the kind of secret typed: the others of its kind, and every
	 *        secret of the other kind, are to be entered again
	 * @param sealed how many stored values are sealed with the missing key
	 * @param onYes what to do if the administrator makes a new key: save again
	 *        with {@code newKey} set
	 */
	static void ask(Secret typed, long sealed, Runnable onYes) {
		String values = sealed == 1 ? "1 stored value is" : sealed + " stored values are";
		boolean client = typed == Secret.CLIENT;
		LD.ask("Make a New Secret Key", "Healdan's secret key file is missing, and " + values + " sealed with it: "
				+ "client secrets, the S3 secrets of storage backends, and the refresh tokens of Healdan Helpers that "
				+ "signed in through a provider. If it went missing by mistake, restore it from a backup and save "
				+ "again: nothing needs restarting, and nothing is lost.<br><br>A new key keeps the "
				+ (client ? "client secret" : "S3 secret") + " you typed, and nothing sealed before can be opened: "
				+ "you enter " + (client ? "each other" : "each") + " provider's client secret again on the Sign-In "
				+ "tab, and " + (client ? "each" : "each other") + " storage backend's S3 secret on the Storage tab "
				+ "(those backends fail until then); Healdan Helpers that signed in through a provider sign in again. "
				+ "Make a new key?", 480, yes -> {
					if (Boolean.TRUE.equals(yes))
						onYes.run();
				});
	}
}
