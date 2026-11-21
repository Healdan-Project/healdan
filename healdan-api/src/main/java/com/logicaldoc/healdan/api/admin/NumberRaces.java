// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.sql.SQLException;

import org.apache.commons.lang3.exception.ExceptionUtils;

import com.logicaldoc.core.PersistenceException;

/**
 * A create that loses a race for its number. A client's or a matter's create checks that its number is free, then
 * inserts; of two creates of one number at the same moment, both can pass the check, and the second insert then fails
 * on the table's unique index ({@code hd_client}'s number, {@code hd_matter}'s client and number), its transaction
 * rolled back. Such a create is answered as one whose number was used already, {@code 409 refused} with the same
 * sentence, not as a failure: the check is asked again once the first has committed, and its refusal is the answer.
 */
final class NumberRaces {

	/** PostgreSQL's {@code unique_violation} */
	static final String UNIQUE_VIOLATION = "23505";

	private NumberRaces() {
	}

	/** The create */
	@FunctionalInterface
	interface Create<T> {
		T run() throws PersistenceException;
	}

	/** The check of the number, which refuses one that's used */
	@FunctionalInterface
	interface Check {
		void run() throws PersistenceException;
	}

	/**
	 * @param create the create, whose own check comes first
	 * @param check the check again, which refuses the used number with a {@code HealdanRuleException}
	 * @return what the create made
	 * @throws PersistenceException the create's failure or refusal; for one that failed on a unique index, the check's
	 *         refusal, when the number is used now
	 */
	static <T> T create(Create<T> create, Check check) throws PersistenceException {
		try {
			return create.run();
		} catch (PersistenceException | RuntimeException e) {
			if (!uniqueViolation(e))
				throw e;
			// The create's transaction has rolled back, and the one that won has committed: the check sees its row
			check.run();
			throw e;
		}
	}

	/**
	 * @return whether the failure, or one of its causes, is the database's refusal of a key a unique index holds
	 *         already
	 */
	static boolean uniqueViolation(Throwable failure) {
		return ExceptionUtils.getThrowableList(failure).stream()
				.anyMatch(cause -> cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState()));
	}
}
