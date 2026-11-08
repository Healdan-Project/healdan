// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.Test;

/**
 * The nightly check runs at 03:17 server time, and never in the first 10 minutes after a start (Global Constraints'
 * timings).
 */
public class StorageStartupTest {

	private static final ZoneId ZONE = ZoneId.of("Europe/London");

	private static ZonedDateTime at(int day, int hour, int minute) {
		return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZONE);
	}

	/** @return the first nightly check after a start at this time */
	private static ZonedDateTime firstAfterStart(ZonedDateTime start) {
		return StorageStartup.nightlyAfter(start.plus(StorageStartup.AFTER_START));
	}

	@Test
	public void testNightlyCheckAt0317() {
		assertEquals(Duration.ofMinutes(10), StorageStartup.AFTER_START);
		assertEquals("a start in the night", at(7, 3, 17), firstAfterStart(at(7, 1, 0)));
		assertEquals("a start in the day waits for the next night", at(8, 3, 17), firstAfterStart(at(7, 14, 30)));
		assertEquals("ten minutes before is outside the first ten", at(7, 3, 17), firstAfterStart(at(7, 3, 7)));
		assertEquals("seven minutes before is in them", at(8, 3, 17), firstAfterStart(at(7, 3, 10)));
		assertEquals("then each night", at(9, 3, 17), StorageStartup.nightlyAfter(at(8, 3, 17).plusSeconds(1)));
	}

	@Test
	public void testNightlyCheckAcrossTheClockChange() {
		// British Summer Time ends on 25 October 2026: the night has an hour more, and 03:17 is there once
		ZonedDateTime before = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, ZONE);
		ZonedDateTime next = StorageStartup.nightlyAfter(before);
		assertEquals(ZonedDateTime.of(2026, 10, 25, 3, 17, 0, 0, ZONE), next);
		assertEquals(3, next.getHour());
		assertEquals(17, next.getMinute());
	}
}
