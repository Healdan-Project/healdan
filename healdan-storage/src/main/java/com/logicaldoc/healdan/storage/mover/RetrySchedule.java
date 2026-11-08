// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.logicaldoc.healdan.storage.StoredFiles;

/**
 * How long a file whose move failed waits before the next try (spec section 7): 1, 2, 4, 8, 16, 32, 60, 60 and 60
 * minutes after its first nine failures in a row. The tenth marks it stuck, and it waits for Retry. A backend that
 * can't be reached counts no failure.
 */
public final class RetrySchedule {

	/** The failure that marks a file stuck, as the rows count it */
	public static final int STUCK_AT = StoredFiles.STUCK_AT;

	private static final List<Duration> GAPS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2),
			Duration.ofMinutes(4), Duration.ofMinutes(8), Duration.ofMinutes(16), Duration.ofMinutes(32),
			Duration.ofMinutes(60), Duration.ofMinutes(60), Duration.ofMinutes(60));

	private RetrySchedule() {
	}

	/**
	 * @param failures the failures in a row, this one included: 1 after the first
	 * @return the wait before the next try; none once the file is stuck
	 */
	public static Optional<Duration> gap(int failures) {
		if (failures < 1)
			throw new IllegalArgumentException("A gap follows a failure, not " + failures);
		return stuck(failures) ? Optional.empty() : Optional.of(GAPS.get(failures - 1));
	}

	/**
	 * @param failures the failures in a row, this one included
	 * @return whether the file is stuck now
	 */
	public static boolean stuck(int failures) {
		return failures >= STUCK_AT;
	}

	/**
	 * @param failures the failures in a row, this one included
	 * @param now when this one happened
	 * @return when the file may be tried again; null once it is stuck
	 */
	public static Date nextTry(int failures, Date now) {
		return gap(failures).map(gap -> new Date(now.getTime() + gap.toMillis())).orElse(null);
	}
}
