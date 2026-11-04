// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import com.logicaldoc.healdan.HealdanRuleException;

/**
 * The once-per-matter rule's refusal (M5 spec §6.4): the matter already holds a
 * copy of the message that the user can read. The message names the matter and
 * the copy's doc #, and the API answers it as {@code 409 already-filed} with
 * the copy.
 */
public class AlreadyFiledException extends HealdanRuleException {

	private static final long serialVersionUID = 1L;

	private final long copyId;

	/**
	 * @param message the refusal, for the user
	 * @param copyId the id of the copy the matter holds
	 */
	public AlreadyFiledException(String message, long copyId) {
		super(message);
		this.copyId = copyId;
	}

	/**
	 * @return the id of the copy the matter holds
	 */
	public long copyId() {
		return copyId;
	}
}
