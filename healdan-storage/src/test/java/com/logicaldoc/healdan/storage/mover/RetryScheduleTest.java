// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.healdan.storage.StoredFiles;

/**
 * A file's retries wait 1, 2, 4, 8, 16, 32, 60, 60 and 60 minutes, and the tenth failure marks it stuck (Global
 * Constraints' timings).
 */
public class RetryScheduleTest {

	private static final List<Integer> MINUTES = List.of(1, 2, 4, 8, 16, 32, 60, 60, 60);

	@Test
	public void testGaps() {
		Date now = new Date(1_000_000_000L);
		for (int failure = 1; failure <= MINUTES.size(); failure++) {
			Duration gap = Duration.ofMinutes(MINUTES.get(failure - 1));
			assertEquals("after failure " + failure, gap, RetrySchedule.gap(failure).orElseThrow());
			assertFalse(RetrySchedule.stuck(failure));
			assertEquals(new Date(now.getTime() + gap.toMillis()), RetrySchedule.nextTry(failure, now));
		}
	}

	@Test
	public void testStuckAtTheTenth() {
		assertEquals(10, RetrySchedule.STUCK_AT);
		assertEquals("the rows mark the same failure stuck", StoredFiles.STUCK_AT, RetrySchedule.STUCK_AT);
		assertTrue(RetrySchedule.stuck(10));
		assertTrue(RetrySchedule.gap(10).isEmpty());
		assertNull("a stuck file has no next try", RetrySchedule.nextTry(10, new Date()));
		assertTrue(RetrySchedule.stuck(11));
	}
}
