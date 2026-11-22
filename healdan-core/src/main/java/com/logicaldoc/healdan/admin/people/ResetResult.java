// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

/**
 * What Reset Password did: the person's new temporary password, which they change at their next sign-in. The password
 * is in this answer once, for the administrator to pass on, unless it was emailed; it's in no other answer, record,
 * log line or {@link #toString()}.
 *
 * @param temporaryPassword the temporary password; null when it was emailed
 * @param emailed whether the mail server took the email of the password
 * @param notEmailed why the password is shown rather than emailed; null when it was emailed
 */
public record ResetResult(String temporaryPassword, boolean emailed, NotEmailed notEmailed) {

	public ResetResult {
		if (emailed != (temporaryPassword == null) || emailed != (notEmailed == null))
			throw new IllegalArgumentException("A password is shown, with its reason, unless it was emailed");
	}

	@Override
	public String toString() {
		// Never the password
		return "ResetResult[emailed=%b, notEmailed=%s]".formatted(emailed, notEmailed);
	}
}
