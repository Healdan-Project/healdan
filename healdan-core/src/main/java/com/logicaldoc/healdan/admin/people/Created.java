// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.util.Objects;

/**
 * A new password account, and its temporary password, which the person changes at their first sign-in. The password
 * is in this answer once, for the administrator to pass on, unless it was emailed; it's in no other answer, record,
 * log line or {@link #toString()}.
 *
 * @param user the new person, as their page shows them
 * @param temporaryPassword the temporary password; null when it was emailed
 * @param emailed whether the mail server took the email of the password
 * @param notEmailed why the password is shown rather than emailed; null when it was emailed
 */
public record Created(Person user, String temporaryPassword, boolean emailed, NotEmailed notEmailed) {

	public Created {
		Objects.requireNonNull(user, "user");
		if (emailed != (temporaryPassword == null) || emailed != (notEmailed == null))
			throw new IllegalArgumentException("A password is shown, with its reason, unless it was emailed");
	}

	@Override
	public String toString() {
		// Never the password
		return "Created[user=%d, emailed=%b, notEmailed=%s]".formatted(user.row().id(), emailed, notEmailed);
	}
}
