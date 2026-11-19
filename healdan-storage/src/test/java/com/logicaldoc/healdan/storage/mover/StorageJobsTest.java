// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.healdan.admin.jobs.JobView;
import com.logicaldoc.healdan.admin.jobs.Jobs;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.NightlyCheck;
import com.logicaldoc.healdan.storage.Purge;
import com.logicaldoc.healdan.storage.StorageAlert;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoreTracking;
import com.logicaldoc.util.spring.Context;

/**
 * The storage's background work reports to healdan-core's job status under its own names (Admin app spec section
 * 9.5): the mover's passes, the retests, the purge, the alert and the nightly check.
 */
public class StorageJobsTest extends AbstractStorageTestCase {

	private Jobs jobs;

	private StorageStartup startup;

	@Before
	public void prepare() {
		jobs = Context.get(Jobs.class);
	}

	@After
	public void stopEverything() {
		if (startup != null)
			startup.stop();
		Mover.get().stop();
	}

	@Test
	public void testListedAfterHealdanCoresJobs() {
		List<JobView> all = jobs.all();
		assertEquals(List.of("preview", "email-index", "trash-destroyer", "storage-mover", "storage-retest",
				"storage-purge", "storage-alert", "storage-nightly-check"),
				all.subList(0, 8).stream().map(JobView::name).toList());
		assertEquals(List.of("Storage Mover", "Storage Retests", "Storage Purge", "Storage Alert",
				"Storage Nightly Check"), all.subList(3, 8).stream().map(JobView::label).toList());
		assertEquals("IndexOptimizer", all.get(8).name());
		assertTrue(all.subList(3, 8).stream().allMatch(JobView::enabled));
		assertFalse(all.subList(3, 8).stream().anyMatch(JobView::logicalDocTask));
	}

	@Test
	public void testSwitchedOffWhereStorageIsntInUse() {
		// On a server that keeps its files in LogicalDOC's own store none of them runs, so none is on schedule
		List<StorageJob> storage = List.of(Mover.get(), Context.get(StorageStartup.RetestJob.class),
				Context.get(StorageStartup.PurgeJob.class), Context.get(StorageStartup.AlertJob.class),
				Context.get(StorageStartup.NightlyCheckJob.class));
		try {
			for (StorageJob job : storage)
				job.inUse = () -> false;
			List<JobView> all = jobs.all();
			assertEquals(List.of("storage-mover", "storage-retest", "storage-purge", "storage-alert",
					"storage-nightly-check"), all.subList(3, 8).stream().map(JobView::name).toList());
			assertFalse(all.subList(3, 8).stream().anyMatch(JobView::enabled));
			// healdan-core's own still run, as do LogicalDOC's tasks
			assertTrue(all.subList(0, 3).stream().allMatch(JobView::enabled));
			assertTrue(jobs.one("IndexerTask").enabled());
		} finally {
			for (StorageJob job : storage)
				job.inUse = StoreTracking::active;
		}
		assertTrue(jobs.all().subList(3, 8).stream().allMatch(JobView::enabled));
	}

	@Test
	public void testMoverRunOnceReports() {
		Instant before = Instant.now();
		Mover.get().runOnce();

		JobView pass = jobs.one(Mover.JOB);
		assertEquals("storage-mover", pass.name());
		assertFalse(pass.lastStart().isBefore(before));
		assertFalse(pass.lastEnd().isBefore(pass.lastStart()));
		assertEquals(JobView.SUCCEEDED, pass.result());
		assertFalse(pass.running());
		// Only the coordinator, which isn't started, can make a pass on demand
		assertFalse(pass.canRun());
	}

	@Test
	public void testTheCoordinatorsPassesReport() throws Exception {
		Instant before = Instant.now();
		Mover.get().start();

		JobView pass = await(Mover.JOB);
		assertFalse(pass.lastStart().isBefore(before));
		assertEquals(JobView.SUCCEEDED, pass.result());
		// The next sweep
		assertEquals(pass.lastStart().plusSeconds(StorageSettings.get().sweepSeconds()).toEpochMilli(),
				pass.nextRun().toEpochMilli(), 1000);
		assertTrue("a running coordinator makes a pass when woken", pass.canRun());
	}

	@Test
	public void testThePurgeReportsUnderItsName() throws Exception {
		startup = startup(Purge.get());
		CompletableFuture<ZonedDateTime> nightly = new CompletableFuture<>();
		startup.nightlyScheduled = nightly::complete;
		startup.started();

		JobView purge = await(StorageStartup.PURGE_JOB);
		assertEquals("storage-purge", purge.name());
		assertEquals(JobView.SUCCEEDED, purge.result());
		assertNull(purge.error());
		// Daily, from its start
		assertEquals(purge.lastStart().plus(Duration.ofDays(1)).toEpochMilli(), purge.nextRun().toEpochMilli(), 1000);

		// The alert runs with it, the nightly check is scheduled for 03:17, and the retests for their interval
		JobView alert = await(StorageStartup.ALERT_JOB);
		assertEquals(JobView.SUCCEEDED, alert.result());
		assertEquals(alert.lastStart().plus(Duration.ofHours(1)).toEpochMilli(), alert.nextRun().toEpochMilli(), 1000);
		ZonedDateTime at = nightly.get(20, TimeUnit.SECONDS);
		assertEquals(at.toInstant(), jobs.one(StorageStartup.NIGHTLY_JOB).nextRun());
		assertNull(jobs.one(StorageStartup.NIGHTLY_JOB).lastStart());
		long deadline = System.currentTimeMillis() + 20_000;
		while (jobs.one(StorageStartup.RETEST_JOB).nextRun() == null && System.currentTimeMillis() < deadline)
			Thread.sleep(20);
		assertNotNull(jobs.one(StorageStartup.RETEST_JOB).nextRun());
	}

	@Test
	public void testAPurgeThatFailsSaysWhy() throws Exception {
		startup = startup(new Purge() {
			@Override
			public void run() {
				throw new IllegalStateException("The held copies can't be listed");
			}
		});
		startup.started();

		JobView purge = await(StorageStartup.PURGE_JOB);
		assertEquals(JobView.FAILED, purge.result());
		assertEquals("IllegalStateException: The held copies can't be listed", purge.error());
		assertFalse(purge.running());
		// It runs again the next day all the same
		assertEquals(purge.lastStart().plus(Duration.ofDays(1)).toEpochMilli(), purge.nextRun().toEpochMilli(), 1000);
	}

	@Test
	public void testRetestsReport() throws Exception {
		// The shortest the setting allows; the setting is this test's database's, and the health this context's
		StorageSettings.get().setRetestSeconds(5);
		startup = startup(Purge.get());
		startup.afterStart = Duration.ofDays(2);
		startup.started();

		JobView retest = await(StorageStartup.RETEST_JOB);
		assertEquals(JobView.SUCCEEDED, retest.result());
		// The setting read again once the retest ended, as each round reads it
		assertEquals(retest.lastEnd().plusSeconds(5).toEpochMilli(), retest.nextRun().toEpochMilli(), 1000);
	}

	/** @return a start of the storage's work whose purge and alert run at once */
	private static StorageStartup startup(Purge purge) {
		StorageStartup startup = new StorageStartup(BackendHealth.get(), Mover.get(), purge, NightlyCheck.get(),
				StorageAlert.get(), StorageSettings.get());
		startup.afterStart = Duration.ZERO;
		return startup;
	}

	/** @return the job once a run of it has ended */
	private JobView await(String name) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 20_000;
		JobView view = jobs.one(name);
		while ((view.result() == null || view.running()) && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
			view = jobs.one(name);
		}
		assertNotNull(name + " ran", view.result());
		return view;
	}
}
