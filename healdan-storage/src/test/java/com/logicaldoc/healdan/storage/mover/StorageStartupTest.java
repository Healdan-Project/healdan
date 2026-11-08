// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.NightlyCheck;
import com.logicaldoc.healdan.storage.Purge;
import com.logicaldoc.healdan.storage.StorageAlert;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoredFiles;

/**
 * The nightly check runs at 03:17 server time, and never in the first 10 minutes after a start (Global Constraints'
 * timings); the alert is scheduled, and a run that throws leaves it scheduled.
 */
public class StorageStartupTest extends AbstractStorageTestCase {

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

	@Test
	public void testAlertIsScheduledAndSurvivesAFailingRun() throws Exception {
		AtomicInteger runs = new AtomicInteger();
		CountDownLatch enough = new CountDownLatch(3);
		StorageAlert failing = new StorageAlert(BackendHealth.get(), StoredFiles.get(), HealdanSettings.get()) {
			@Override
			public boolean run() {
				enough.countDown();
				// An exception, then an error the mail stack may throw, then ordinary runs
				int n = runs.incrementAndGet();
				if (n == 1)
					throw new IllegalStateException("The mail server is down");
				if (n == 2)
					throw new NoClassDefFoundError("jakarta/mail/Whatever");
				return false;
			}
		};
		StorageStartup startup = new StorageStartup(BackendHealth.get(), Mover.get(), Purge.get(),
				NightlyCheck.get(), failing, StorageSettings.get());
		startup.afterStart = Duration.ZERO;
		startup.alertEvery = Duration.ofMillis(50);
		try {
			startup.started();
			assertTrue("the alert ran three times, though two runs threw", enough.await(20, TimeUnit.SECONDS));
		} finally {
			startup.stop();
			Mover.get().stop();
		}
	}
}
