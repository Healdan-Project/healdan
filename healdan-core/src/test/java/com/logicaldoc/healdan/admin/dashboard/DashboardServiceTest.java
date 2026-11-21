// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.admin.dashboard.DashboardService.Dashboard;
import com.logicaldoc.healdan.admin.dashboard.DashboardService.Space;
import com.logicaldoc.healdan.admin.errors.CapturedError;
import com.logicaldoc.healdan.admin.errors.ErrorStore;
import com.logicaldoc.healdan.admin.jobs.JobStatus;
import com.logicaldoc.healdan.admin.storage.OverviewPort;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.preview.PreviewJobs;
import com.logicaldoc.healdan.preview.PreviewWorker;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.support.FakeProvider;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.trash.TrashStartup;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * The Dashboard's Needs Attention and Server Health (Admin app spec section 8.1): each code and tile from its own
 * source, each part read alone, and what's costly kept for a while. {@code healdan-core} has no storage module, so the
 * storage port is a fake (spec section 9.7), and so are the disks, which are the machine's own.
 */
public class DashboardServiceTest extends AbstractHealdanTestCase {

	private static final String CLASSIC = "/frontend.jsp";

	private static final String BACKENDS = "/admin/backends";

	/** The links to the Admin app's screens, by attention code and tile name; every other is the Classic Interface */
	private static final Map<String, String> LINKS = Map.of(AttentionItem.FILES_STUCK, "/admin/stores",
			AttentionItem.BACKEND_UNREACHABLE, BACKENDS, AttentionItem.DISK_LOW, BACKENDS, HealthTile.STORAGE_BACKENDS,
			BACKENDS, HealthTile.DISK, BACKENDS);

	private final HealdanFixtures fx = new HealdanFixtures();

	private DashboardService service;

	private JdbcTemplate jdbc;

	/** What the fake storage port answers */
	private StorageOverview overview;

	/** How full each folder's disk is, by the folder's setting; one disk, 40% full, when it isn't named */
	private final Map<String, Space> spaces = new LinkedHashMap<>();

	/** The settings a test changed, to put back */
	private final Map<String, String> changed = new LinkedHashMap<>();

	private FakeProvider fake;

	@Before
	public void prepare() {
		service = DashboardService.get();
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		overview = overview(List.of(backend(1, "Original", BackendInfo.OK)), List.of(), 0);
		service.ports = () -> List.of(OverviewPort.of(() -> overview));
		service.disks = folder -> {
			ContextProperties config = Context.get().getConfig();
			for (Map.Entry<String, Space> space : spaces.entrySet())
				if (folder.toString().equals(config.getProperty(space.getKey())))
					return space.getValue();
			return new Space("disk", 1000, 600);
		};
	}

	@After
	public void putBack() {
		ContextProperties config = Context.get().getConfig();
		for (Map.Entry<String, String> setting : changed.entrySet())
			if (setting.getValue() == null)
				config.remove(setting.getKey());
			else
				config.setProperty(setting.getKey(), setting.getValue());
		if (fake != null)
			fake.close();
		SignInTestHooks.reset();
	}

	/** Sets a setting in memory for this test only */
	private void set(String key, String value) {
		ContextProperties config = Context.get().getConfig();
		changed.putIfAbsent(key, config.getProperty(key));
		config.setProperty(key, value);
	}

	/** What the service reads now, every source asked afresh */
	private Dashboard read() {
		service.forget();
		return service.read();
	}

	private static StorageOverview overview(List<BackendInfo> backends, List<StoreBackendInfo> stores,
			long otherStuck) {
		return new StorageOverview(true, backends, stores, new StorageSettingsInfo(30, 10, 4), 0, 0, otherStuck);
	}

	private static BackendInfo backend(long id, String name, String status) {
		return new BackendInfo(id, name, id == 1 ? "local" : "s3", "/where", id != 1, 10, 1000, status, null);
	}

	private static List<String> codes(Dashboard dashboard) {
		return dashboard.attention().stream().map(AttentionItem::code).toList();
	}

	private static AttentionItem item(Dashboard dashboard, String code) {
		return dashboard.attention().stream().filter(item -> item.code().equals(code)).findFirst().orElse(null);
	}

	private static HealthTile tile(Dashboard dashboard, String name) {
		return dashboard.health().stream().filter(tile -> tile.name().equals(name)).findFirst().orElseThrow();
	}

	/** A document in a matter's folder, which the Indexer tried and couldn't index */
	private Document unindexable() throws Exception {
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder folder = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		Document document = fx.upload(folder, "scan.pdf", "not really a PDF", fx.admin());
		HealdanFixtures.awaitVersionSaves();
		jdbc.update("update ld_document set ld_indexed = ? where ld_id = ?", IndexingStatus.TO_INDEX.ordinal(),
				document.getId());
		DocumentHistory history = new DocumentHistory();
		history.setDocument(document);
		history.setEvent(DocumentEvent.INDEXED_ERROR);
		history.setComment("The file can't be read");
		history.setDate(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)));
		history.setSessionId("dashboard");
		DocumentHistoryDAO.get().store(history);
		return document;
	}

	@Test
	public void testAttentionCodes() throws Exception {
		// All is well: nothing, and everything checked
		Dashboard well = read();
		assertEquals(List.of(), well.attention());
		assertEquals(List.of(), well.unchecked());

		// files-stuck and backend-unreachable, from the storage port
		overview = overview(List.of(backend(1, "Original", BackendInfo.OK), backend(2, "Wasabi EU", BackendInfo.UNREACHABLE)),
				List.of(new StoreBackendInfo(10, "Client Work", 2, "Wasabi EU", 3, 300, 3, 40, 4000)), 0);
		// index-failed: a document whose latest try failed
		Document document = unindexable();
		// errors: one unresolved in the last 24 hours; one resolved, and one older, don't count
		ErrorStore errors = ErrorStore.get();
		Instant now = Instant.now();
		errors.add(new CapturedError(now.minus(2, ChronoUnit.HOURS), "ERROR", "com.example.Previews", "Timed out", null));
		errors.add(new CapturedError(now.minus(1, ChronoUnit.HOURS), "ERROR", "com.example.Fixed", "Was broken", null));
		long fixed = jdbc.queryForObject("select hd_id from hd_error where hd_logger = 'com.example.Fixed'", Long.class);
		errors.resolve(fixed, fx.admin());
		errors.add(new CapturedError(now.minus(25, ChronoUnit.HOURS), "ERROR", "com.example.Old", "Long ago", null));
		// previews-given-up: a preview given up on after its last try
		PreviewJobs previews = PreviewJobs.get();
		previews.add(document.getId(), "1.0", now);
		jdbc.update("update hd_preview_job set hd_attempts = ? where hd_docid = ?", PreviewJobs.MAX_ATTEMPTS,
				document.getId());
		// job-failed and job-overdue, as the jobs report them; the previews are overdue too, and counted once, as failed
		JobStatus.get().failed(PreviewWorker.JOB, new IllegalStateException("LibreOffice is gone"),
				now.minus(2, ChronoUnit.HOURS));
		JobStatus.get().scheduled(TrashStartup.JOB, now.minus(2, ChronoUnit.HOURS));
		// disk-low: the repository's disk more than 85% full; the index's and logs' disk isn't
		spaces.put("store.1.dir", new Space("repository disk", 1000, 140));

		Dashboard dashboard = read();

		assertEquals(AttentionItem.CODES, codes(dashboard));
		assertEquals(List.of(), dashboard.unchecked());

		AttentionItem stuck = item(dashboard, AttentionItem.FILES_STUCK);
		assertEquals(3, stuck.count());
		assertEquals("Client Work, moving to Wasabi EU", stuck.detail());
		AttentionItem down = item(dashboard, AttentionItem.BACKEND_UNREACHABLE);
		assertEquals(1, down.count());
		assertEquals("Wasabi EU can't be reached", down.detail());
		assertEquals(1, item(dashboard, AttentionItem.INDEX_FAILED).count());
		AttentionItem unresolved = item(dashboard, AttentionItem.ERRORS);
		assertEquals(1, unresolved.count());
		assertEquals("The latest seen 2 hours ago", unresolved.detail());
		assertEquals(1, item(dashboard, AttentionItem.PREVIEWS_GIVEN_UP).count());
		AttentionItem failed = item(dashboard, AttentionItem.JOB_FAILED);
		assertEquals(1, failed.count());
		assertEquals("Previews", failed.detail());
		AttentionItem overdue = item(dashboard, AttentionItem.JOB_OVERDUE);
		assertEquals(1, overdue.count());
		assertEquals("Trash Destroyer", overdue.detail());
		AttentionItem disk = item(dashboard, AttentionItem.DISK_LOW);
		assertEquals(1, disk.count());
		assertEquals("Repository 86% full", disk.detail());

		// Stuck files are fixed on the Stores screen and the storage's on the Backends screen, both the Admin app's;
		// the rest on the Classic Interface's still. Every line says something
		for (AttentionItem item : dashboard.attention()) {
			assertEquals(item.code(), LINKS.getOrDefault(item.code(), CLASSIC), item.link());
			assertFalse(item.code(), item.detail().isBlank());
		}
	}

	// A backend failing for another reason is one Healdan can't use too, and stuck files in several places are named
	@Test
	public void testSeveralBackendsAndStores() {
		overview = overview(
				List.of(backend(1, "Original", BackendInfo.OK), backend(2, "Wasabi EU", BackendInfo.UNREACHABLE),
						backend(3, "Backup", BackendInfo.FAILING), backend(4, "New", BackendInfo.UNCHECKED)),
				List.of(new StoreBackendInfo(10, "Client Work", 2, "Wasabi EU", 3, 300, 3, 40, 4000),
						new StoreBackendInfo(11, "Firm", 3, "Backup", 1, 100, 1, 5, 500),
						new StoreBackendInfo(12, "Quiet", 1, "Original", 0, 0, 0, 5, 500)),
				2);

		Dashboard dashboard = read();

		AttentionItem stuck = item(dashboard, AttentionItem.FILES_STUCK);
		assertEquals(6, stuck.count());
		assertEquals("Client Work (3), Firm (1) and no Store (2)", stuck.detail());
		AttentionItem down = item(dashboard, AttentionItem.BACKEND_UNREACHABLE);
		assertEquals(2, down.count());
		assertEquals("Wasabi EU can't be reached and Backup is failing", down.detail());
		HealthTile backends = tile(dashboard, HealthTile.STORAGE_BACKENDS);
		assertEquals(HealthTile.ATTENTION, backends.status());
		assertEquals("1 of 4 reachable, 1 not checked yet, 6 files stuck", backends.detail());
		// A Store's among them: they're retried on the Stores' pages
		assertEquals("/admin/stores", stuck.link());
	}

	// Files with no Store alone are the Backends page's Other Files, retried there
	@Test
	public void testStuckFilesWithNoStoreLinkToTheBackends() {
		overview = overview(List.of(backend(1, "Original", BackendInfo.OK)),
				List.of(new StoreBackendInfo(12, "Quiet", 1, "Original", 0, 0, 0, 5, 500)), 2);

		AttentionItem stuck = item(read(), AttentionItem.FILES_STUCK);
		assertEquals(2, stuck.count());
		assertEquals("Files with no Store", stuck.detail());
		assertEquals(BACKENDS, stuck.link());
	}

	@Test
	public void testTenTilesInOrder() throws Exception {
		Dashboard dashboard = read();

		assertEquals(HealthTile.NAMES, dashboard.health().stream().map(HealthTile::name).toList());
		for (HealthTile tile : dashboard.health()) {
			assertNull(tile.name() + ": " + tile.problem(), tile.problem());
			assertNotNull(tile.name(), tile.detail());
			assertTrue(tile.name(), Set.of(HealthTile.OK, HealthTile.ATTENTION, HealthTile.OFF).contains(tile.status()));
			assertEquals(tile.name(), LINKS.getOrDefault(tile.name(), CLASSIC), tile.link());
		}

		HealthTile server = tile(dashboard, HealthTile.SERVER);
		assertEquals(HealthTile.OK, server.status());
		assertTrue(server.detail(), server.detail().matches("Up .+, Healdan " + Context.get().getConfig()
				.getProperty("product.release").replace(".", "\\.")));

		// One disk: its bytes and its line
		HealthTile disk = tile(dashboard, HealthTile.DISK);
		assertEquals(HealthTile.OK, disk.status());
		assertEquals(Long.valueOf(400), disk.used());
		assertEquals(Long.valueOf(1000), disk.total());
		assertEquals("400 B of 1,000 B used", disk.detail());

		HealthTile memory = tile(dashboard, HealthTile.MEMORY);
		assertEquals(HealthTile.OK, memory.status());
		assertTrue(memory.used() > 0);
		assertTrue(memory.total() >= memory.used());
		assertTrue(memory.detail(), memory.detail().endsWith(" used"));

		// No provider, and outgoing email isn't set up in the tests
		HealthTile signIn = tile(dashboard, HealthTile.SIGN_IN);
		assertEquals(HealthTile.OFF, signIn.status());
		assertEquals("No sign-in provider is on", signIn.detail());
		HealthTile email = tile(dashboard, HealthTile.OUTGOING_EMAIL);
		assertEquals(HealthTile.OFF, email.status());
		assertEquals("Not set up", email.detail());

		HealthTile backends = tile(dashboard, HealthTile.STORAGE_BACKENDS);
		assertEquals(HealthTile.OK, backends.status());
		assertEquals("1 of 1 reachable, no files stuck", backends.detail());
		HealthTile index = tile(dashboard, HealthTile.SEARCH_INDEX);
		assertEquals(HealthTile.OK, index.status());
		assertEquals("None waiting, none failed", index.detail());
		HealthTile previews = tile(dashboard, HealthTile.PREVIEWS);
		assertEquals(HealthTile.OK, previews.status());
		assertEquals("None waiting, none given up", previews.detail());
		HealthTile jobs = tile(dashboard, HealthTile.BACKGROUND_JOBS);
		assertEquals(HealthTile.OK, jobs.status());
		assertTrue(jobs.detail(), jobs.detail().contains(" on schedule"));

		// Only the Disk, the Memory and the Database have bytes
		for (HealthTile tile : dashboard.health())
			if (!Set.of(HealthTile.DISK, HealthTile.MEMORY, HealthTile.DATABASE).contains(tile.name())) {
				assertNull(tile.name(), tile.used());
				assertNull(tile.name(), tile.total());
			}
	}

	@Test
	public void testTilesThatNeedAttention() throws Exception {
		unindexable();
		JobStatus.get().failed(PreviewWorker.JOB, new IllegalStateException("LibreOffice is gone"), null);
		JobStatus.get().scheduled(TrashStartup.JOB, Instant.now().minus(2, ChronoUnit.HOURS));
		// The repository's disk is the fullest, so the tile shows its bytes, and names the index's and logs' too
		spaces.put("store.1.dir", new Space("repository disk", 1000, 100));
		spaces.put("index.dir", new Space("other disk", 2000, 1000));
		spaces.put("conf.logdir", new Space("other disk", 2000, 1000));

		Dashboard dashboard = read();

		HealthTile index = tile(dashboard, HealthTile.SEARCH_INDEX);
		assertEquals(HealthTile.ATTENTION, index.status());
		assertEquals("None waiting, 1 failed", index.detail());
		HealthTile jobs = tile(dashboard, HealthTile.BACKGROUND_JOBS);
		assertEquals(HealthTile.ATTENTION, jobs.status());
		assertTrue(jobs.detail(), jobs.detail().startsWith("Previews failed, Trash Destroyer overdue"));
		HealthTile disk = tile(dashboard, HealthTile.DISK);
		assertEquals(HealthTile.ATTENTION, disk.status());
		assertEquals(Long.valueOf(900), disk.used());
		assertEquals(Long.valueOf(1000), disk.total());
		assertEquals("Repository: 900 B of 1,000 B used; index and logs: 1,000 B of 2 KB used", disk.detail());
	}

	@Test
	public void testAFailingPartDoesNotFailTheRest() throws Exception {
		OverviewPort failing = () -> {
			throw new PersistenceException("hd_storage_file is gone");
		};
		service.ports = () -> List.of(failing);
		JobStatus.get().failed(PreviewWorker.JOB, new IllegalStateException("LibreOffice is gone"), null);

		Dashboard dashboard = read();

		HealthTile storage = tile(dashboard, HealthTile.STORAGE_BACKENDS);
		assertEquals(HealthTile.ATTENTION, storage.status());
		assertEquals(DashboardService.UNREADABLE, storage.problem());
		assertNull(storage.detail());
		assertNull(storage.used());
		assertNull(storage.total());
		assertEquals(BACKENDS, storage.link());

		List<HealthTile> others = new ArrayList<>(dashboard.health());
		others.remove(storage);
		assertEquals(9, others.size());
		for (HealthTile tile : others) {
			assertNull(tile.name(), tile.problem());
			assertNotNull(tile.name(), tile.detail());
		}
		// Its codes couldn't be checked; the others were
		assertEquals(List.of(AttentionItem.FILES_STUCK, AttentionItem.BACKEND_UNREACHABLE), dashboard.unchecked());
		assertEquals(List.of(AttentionItem.JOB_FAILED), codes(dashboard));
		// Nothing of the error's own reaches the answer
		assertFalse(dashboard.toString().contains("hd_storage_file"));
	}

	@Test
	public void testASourceThatCantBeReadIsUnchecked() throws Exception {
		ErrorStore.get().add(new CapturedError(Instant.now().minusSeconds(60), "ERROR", "com.example.Previews",
				"Timed out", null));
		jdbc.update("alter table hd_preview_job rename to hd_preview_job_away");
		Dashboard dashboard;
		try {
			dashboard = read();
		} finally {
			jdbc.update("alter table hd_preview_job_away rename to hd_preview_job");
		}

		assertEquals(List.of(AttentionItem.PREVIEWS_GIVEN_UP), dashboard.unchecked());
		assertEquals(List.of(AttentionItem.ERRORS), codes(dashboard));
		HealthTile previews = tile(dashboard, HealthTile.PREVIEWS);
		assertEquals(HealthTile.ATTENTION, previews.status());
		assertEquals(DashboardService.UNREADABLE, previews.problem());
		assertEquals(9, dashboard.health().stream().filter(tile -> tile.problem() == null).count());
		assertFalse(dashboard.toString().contains("hd_preview_job"));
	}

	// A source still being worked out when the request stops waiting is unchecked, and says so, and shows next time
	@Test
	public void testASlowSourceIsStillBeingWorkedOut() throws Exception {
		Duration wait = Duration.ofMillis(300);
		service.longestWait = wait;
		CountDownLatch slow = new CountDownLatch(1);
		service.ports = () -> {
			try {
				// Bounded, so a failed assertion below never leaves the thread parked
				slow.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return List.of(OverviewPort.of(() -> overview));
		};
		try {
			long started = System.nanoTime();
			Dashboard waited = read();

			assertTrue(System.nanoTime() - started >= wait.toNanos());
			// Only the storage port is held; another source may be slower than the short wait too, on a busy machine
			assertTrue(waited.unchecked().toString(), waited.unchecked()
					.containsAll(List.of(AttentionItem.FILES_STUCK, AttentionItem.BACKEND_UNREACHABLE)));
			assertEquals(DashboardService.STILL_WORKING, tile(waited, HealthTile.STORAGE_BACKENDS).problem());
		} finally {
			slow.countDown();
		}
		Dashboard next = awaitDashboard(d -> d.unchecked().isEmpty());
		assertNull(tile(next, HealthTile.STORAGE_BACKENDS).problem());
	}

	/** @return the service's answer, without forgetting, once it's as wanted, or the last after 10 seconds */
	private Dashboard awaitDashboard(Function<Dashboard, Boolean> wanted) throws InterruptedException {
		long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		Dashboard dashboard = service.read();
		while (!wanted.apply(dashboard) && System.nanoTime() < until) {
			Thread.sleep(20);
			dashboard = service.read();
		}
		return dashboard;
	}

	// Storage backends off: nothing to check and nothing stuck, unless they should be on and the port is missing
	@Test
	public void testStorageOff() throws Exception {
		service.ports = List::of;
		Dashboard noPort = read();
		assertEquals(List.of(), noPort.unchecked());
		HealthTile off = tile(noPort, HealthTile.STORAGE_BACKENDS);
		assertEquals(HealthTile.OFF, off.status());
		assertEquals("Not in use on this server", off.detail());

		set("store", DashboardService.HEALDAN_STORAGE);
		Dashboard missing = read();
		assertEquals(List.of(AttentionItem.FILES_STUCK, AttentionItem.BACKEND_UNREACHABLE), missing.unchecked());
		assertEquals(DashboardService.UNREADABLE, tile(missing, HealthTile.STORAGE_BACKENDS).problem());

		// The port says they're off, as on an FSStore server
		StorageOverview offOverview = new StorageOverview(false, List.of(), List.of(), new StorageSettingsInfo(30, 10, 4),
				0, 0, 0);
		service.ports = () -> List.of(OverviewPort.of(() -> offOverview));
		Dashboard portOff = read();
		assertEquals(List.of(), portOff.unchecked());
		assertEquals(HealthTile.OFF, tile(portOff, HealthTile.STORAGE_BACKENDS).status());
	}

	@Test
	public void testDatabaseTile() throws Exception {
		HealthTile database = tile(read(), HealthTile.DATABASE);

		assertEquals(HealthTile.OK, database.status());
		assertNull(database.problem());
		assertNull(database.total());
		assertTrue(database.detail(), database.detail().matches("PostgreSQL \\d+\\.\\d+, .+"));
		long size = jdbc.queryForObject("select pg_database_size(current_database())", Long.class);
		assertTrue(database.used() + " against " + size, database.used() > 0);
		// The size moves only as the database is written to
		assertTrue(database.used() + " against " + size, Math.abs(database.used() - size) <= size / 10);
		assertTrue(database.detail(), database.detail().endsWith(", " + Words.size(database.used())));
	}

	// Each enabled provider is asked whether it answers, at most every five minutes
	@Test
	public void testSignInTile() throws Exception {
		Instant start = Instant.now();
		service.clock = Clock.fixed(start, ZoneOffset.UTC);
		fake = FakeProvider.start();
		fake.asProvider("fake");
		String label = SignInProviders.get().find("fake").orElseThrow().label();
		int before = fake.discoveryRequests();

		HealthTile answering = tile(read(), HealthTile.SIGN_IN);
		assertEquals(HealthTile.OK, answering.status());
		assertEquals(label + " answering", answering.detail());
		assertEquals(before + 1, fake.discoveryRequests());

		// Asked again only five minutes on
		service.clock = Clock.fixed(start.plus(DashboardService.SIGN_IN_MAX_AGE).minusSeconds(1), ZoneOffset.UTC);
		assertEquals(HealthTile.OK, tile(read(), HealthTile.SIGN_IN).status());
		assertEquals(before + 1, fake.discoveryRequests());

		fake.discoveryStatus(503);
		service.clock = Clock.fixed(start.plus(DashboardService.SIGN_IN_MAX_AGE), ZoneOffset.UTC);
		HealthTile silent = tile(read(), HealthTile.SIGN_IN);
		assertEquals(before + 2, fake.discoveryRequests());
		assertEquals(HealthTile.ATTENTION, silent.status());
		assertEquals(label + " isn't answering", silent.detail());
		assertNull(silent.problem());
	}

	// Set up as OutgoingEmail says, and a send failed if CE's mail sender logged an error nobody resolved
	@Test
	public void testOutgoingEmailTile() throws Exception {
		set("default.smtp.host", "mail.example.com");
		set("default.smtp.sender", "office@example.com");

		HealthTile setUp = tile(read(), HealthTile.OUTGOING_EMAIL);
		assertEquals(HealthTile.OK, setUp.status());
		assertEquals("Set up, no failed sends in the last 24 hours", setUp.detail());

		ErrorStore errors = ErrorStore.get();
		errors.add(new CapturedError(Instant.now().minus(2, ChronoUnit.HOURS), "ERROR", DashboardService.MAIL_LOGGER,
				"Couldn't connect to host", null));
		HealthTile failed = tile(read(), HealthTile.OUTGOING_EMAIL);
		assertEquals(HealthTile.ATTENTION, failed.status());
		assertEquals("A send failed 2 hours ago", failed.detail());

		long id = jdbc.queryForObject("select hd_id from hd_error where hd_logger = ?", Long.class,
				DashboardService.MAIL_LOGGER);
		errors.resolve(id, fx.admin());
		assertEquals(HealthTile.OK, tile(read(), HealthTile.OUTGOING_EMAIL).status());
	}

	// What's costly is kept: a second read within the minute answers from it, without asking again
	@Test
	public void testASecondReadComesFromWhatsKept() throws Exception {
		Instant start = Instant.now();
		service.clock = Clock.fixed(start, ZoneOffset.UTC);
		// Counted on the refreshes' threads, read on this one
		AtomicInteger asked = new AtomicInteger();
		service.ports = () -> {
			asked.incrementAndGet();
			return List.of(OverviewPort.of(() -> overview));
		};
		assertEquals(List.of(), read().attention());
		assertEquals(1, asked.get());

		// News for two sources: a document that couldn't be indexed, and files stuck on the storage port
		unindexable();
		overview = overview(List.of(backend(1, "Original", BackendInfo.OK), backend(2, "Wasabi EU", BackendInfo.OK)),
				List.of(new StoreBackendInfo(10, "Client Work", 2, "Wasabi EU", 3, 300, 3, 40, 4000)), 0);
		Dashboard kept = service.read();
		assertEquals(List.of(), kept.attention());
		assertEquals("None waiting, none failed", tile(kept, HealthTile.SEARCH_INDEX).detail());
		assertEquals("1 of 1 reachable, no files stuck", tile(kept, HealthTile.STORAGE_BACKENDS).detail());
		assertEquals(1, asked.get());
		// As of when what's kept was read
		assertEquals(start, kept.readAt());

		// A minute on, the next read gets what's kept and asks again on its own thread; a later one has the news,
		// the storage port's own among it, so its one more ask is done
		service.clock = Clock.fixed(start.plus(DashboardService.MAX_AGE), ZoneOffset.UTC);
		Dashboard fresh = awaitDashboard(
				d -> codes(d).equals(List.of(AttentionItem.FILES_STUCK, AttentionItem.INDEX_FAILED)));
		assertEquals(List.of(AttentionItem.FILES_STUCK, AttentionItem.INDEX_FAILED), codes(fresh));
		assertEquals(2, asked.get());
		// The database's, kept for five minutes, is the oldest answer
		assertEquals(start, fresh.readAt());
	}
}
