// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.storage;

import com.logicaldoc.healdan.HealdanRuleException;

/**
 * Healdan's secret key file is missing while stored values are sealed with it, so a typed S3 secret is refused and
 * nothing is written (M4 ruling R37), until the administrator confirms a new key: the add or the save again with
 * {@code newKey}. The key may be missing only for a while, and a new one leaves every other sealed value unreadable,
 * so it's the administrator's question. Its message is the Storage tab's sentence.
 */
public class SecretKeyMissingProblem extends HealdanRuleException {

	private static final long serialVersionUID = 1L;

	private final long sealed;

	/**
	 * @param sealed how many stored values are sealed with the missing key
	 */
	public SecretKeyMissingProblem(long sealed) {
		this(sealed, null);
	}

	/**
	 * @param sealed how many stored values are sealed with the missing key
	 * @param cause what found it missing; its message holds no secret
	 */
	public SecretKeyMissingProblem(long sealed, Throwable cause) {
		super(sentence(sealed), cause);
		this.sealed = sealed;
	}

	/**
	 * @return how many stored values are sealed with the missing key
	 */
	public long sealed() {
		return sealed;
	}

	/**
	 * @return what the administrator is told, which says how many values are sealed and what to do
	 */
	static String sentence(long sealed) {
		return ("Healdan's secret key file is missing, and %s sealed with it. Restore it from a backup and save again, "
				+ "or make a new key.").formatted(sealed == 1 ? "1 stored value is" : sealed + " stored values are");
	}
}
