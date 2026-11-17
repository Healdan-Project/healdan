// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.logicaldoc.healdan.AbstractHealdanTestCase;

/**
 * The nightly job runs at 02:47 server time, and never in the first 10
 * minutes after a start (spec 5.4); what was removed is taken the first night
 * after the set days have passed.
 */
public class TrashStartupTest extends AbstractHealdanTestCase {

	private static final ZoneId ZONE = ZoneId.of("Europe/London");

	private static ZonedDateTime at(int month, int day, int hour, int minute) {
		return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZONE);
	}

	/** @return the first run after a start at this time */
	private static ZonedDateTime firstAfterStart(ZonedDateTime start) {
		return TrashStartup.nightlyAfter(start.plus(TrashStartup.AFTER_START));
	}

	@Test
	public void testNightlyAt0247() {
		assertEquals(Duration.ofMinutes(10), TrashStartup.AFTER_START);
		assertEquals("a start in the night", at(11, 7, 2, 47), firstAfterStart(at(11, 7, 1, 0)));
		assertEquals("a start in the day waits for the next night", at(11, 8, 2, 47),
				firstAfterStart(at(11, 7, 14, 30)));
		assertEquals("ten minutes before is outside the first ten", at(11, 7, 2, 47),
				firstAfterStart(at(11, 7, 2, 37)));
		assertEquals("seven minutes before is in them", at(11, 8, 2, 47), firstAfterStart(at(11, 7, 2, 40)));
		assertEquals("then each night", at(11, 9, 2, 47), TrashStartup.nightlyAfter(at(11, 8, 2, 47).plusSeconds(1)));
	}

	@Test
	public void testWaitsOutTheStartsDelay() throws Exception {
		TrashStartup startup = new TrashStartup(TrashDestroyer.get());
		startup.afterStart = Duration.ofDays(2);
		CompletableFuture<ZonedDateTime> first = new CompletableFuture<>();
		startup.nightlyScheduled = first::complete;
		ZonedDateTime before = ZonedDateTime.now();
		try {
			startup.started();

			ZonedDateTime at = first.get(20, TimeUnit.SECONDS);
			assertFalse("not within the delay after the start: " + at, at.isBefore(before.plus(startup.afterStart)));
			assertTrue("the first 02:47 after it: " + at, at.isBefore(before.plus(startup.afterStart).plusDays(1)));
			assertEquals(TrashStartup.NIGHTLY, at.toLocalTime());
			assertEquals(ZoneId.systemDefault(), at.getZone());
		} finally {
			startup.stop();
		}
	}

	@Test
	public void testAcrossTheClockChange() {
		// British Summer Time starts on 29 March 2026: 01:00 GMT becomes 02:00 BST, and the night has an hour less
		ZonedDateTime before = at(3, 28, 12, 0);
		ZonedDateTime next = TrashStartup.nightlyAfter(before);
		assertEquals(at(3, 29, 2, 47), next);
		assertEquals(2, next.getHour());
		assertEquals(47, next.getMinute());
		assertEquals(ZoneOffset.ofHours(1), next.getOffset());
		assertEquals("23 hours after the night before", Duration.ofHours(23),
				Duration.between(TrashStartup.nightlyAfter(at(3, 27, 12, 0)), next));
		// A start in the hour the clocks skip still runs that night
		assertEquals(at(3, 29, 2, 47), firstAfterStart(ZonedDateTime.of(2026, 3, 29, 0, 55, 0, 0, ZONE)));
		assertEquals(at(3, 30, 2, 47), TrashStartup.nightlyAfter(next.plusSeconds(1)));
	}

	@Test
	public void testDestroyAfterIsTheNightAfterTheDays() {
		assertEquals("in the day: the night after the days", at(12, 2, 2, 47),
				TrashStartup.destroyAfter(at(11, 1, 14, 0).toInstant(), 30, ZONE));
		assertEquals("in the small hours: that night", at(12, 1, 2, 47),
				TrashStartup.destroyAfter(at(11, 1, 1, 0).toInstant(), 30, ZONE));
		assertEquals("at 02:47 itself, the days aren't more than past until the next night", at(12, 2, 2, 47),
				TrashStartup.destroyAfter(at(11, 1, 2, 47).toInstant(), 30, ZONE));
		// The days are whole days of 24 hours, as the job counts them: across the clock change in March, 01:00 GMT
		// thirty days on is 02:00 BST, before that night's run, and 02:00 GMT is 03:00 BST, after it
		assertEquals(at(3, 31, 2, 47), TrashStartup.destroyAfter(
				ZonedDateTime.of(2026, 3, 1, 1, 0, 0, 0, ZONE).toInstant(), 30, ZONE));
		assertEquals(at(4, 1, 2, 47), TrashStartup.destroyAfter(
				ZonedDateTime.of(2026, 3, 1, 2, 0, 0, 0, ZONE).toInstant(), 30, ZONE));

		// It is the night the job takes it: that night's run destroys it, and the run the night before doesn't
		for (ZonedDateTime removed : List.of(at(11, 1, 14, 0), at(11, 1, 1, 0), at(11, 1, 2, 47), at(3, 1, 1, 0),
				at(3, 1, 2, 0), at(10, 10, 2, 46)))
			for (int days : List.of(1, 7, 30, 365)) {
				Instant removedAt = removed.toInstant();
				ZonedDateTime night = TrashStartup.destroyAfter(removedAt, days, ZONE);
				assertEquals(TrashStartup.NIGHTLY, night.toLocalTime());
				assertTrue(removed + " " + days, removedAt.isBefore(TrashDestroyer.cutoff(night.toInstant(), days)));
				assertFalse(removed + " " + days,
						removedAt.isBefore(TrashDestroyer.cutoff(night.minusDays(1).toInstant(), days)));
			}
	}
}
