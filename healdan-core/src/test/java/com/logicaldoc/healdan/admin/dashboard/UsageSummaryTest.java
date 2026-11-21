// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.apache.logging.log4j.Level;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.admin.dashboard.Usage.Backend;
import com.logicaldoc.healdan.admin.dashboard.Usage.BusyMatter;
import com.logicaldoc.healdan.admin.dashboard.Usage.Day;
import com.logicaldoc.healdan.admin.dashboard.Usage.StoreUsage;
import com.logicaldoc.healdan.admin.dashboard.Usage.Total;
import com.logicaldoc.healdan.admin.storage.OverviewPort;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;
import com.logicaldoc.healdan.listing.CountingDataSource;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.spring.Context;

/**
 * The Dashboard's Usage (Admin app spec section 8.1): one summary in memory,
 * worked out on its own thread and read by requests without a query, that
 * counts a matter's documents through the client and matter stamped on them
 * (plan, correction 5).
 */
public class UsageSummaryTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private final ZoneId zone = ZoneId.systemDefault();

	private JdbcTemplate jdbc;

	/** The summary's today, at noon, so nothing here falls on a day's edge */
	private LocalDate today;

	private UsageSummary summary;

	@Before
	public void prepare() {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		today = LocalDate.now(zone);
		summary = UsageSummary.get();
		summary.clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone);
	}

	/** The firm: two Stores, two clients, three matters, and their documents, one of them in the Trash */
	private final class Firm {

		final Folder clientWork;

		final Folder firm;

		final Matter charter;

		final Matter tribunal;

		final Matter purchase;

		final List<Document> documents = new ArrayList<>();

		Firm() throws Exception {
			clientWork = fx.store("Client Work");
			firm = fx.store("Firm");
			com.logicaldoc.healdan.matter.Client ashcombe = fx.client(1001, "Ashcombe");
			com.logicaldoc.healdan.matter.Client beech = fx.client(1002, "Beech");
			charter = fx.matter(ashcombe, 1, "Charter Dispute", clientWork, "Documents");
			purchase = fx.matter(ashcombe, 2, "House Purchase", clientWork, "Documents");
			tribunal = fx.matter(beech, 1, "Employment Tribunal", firm, "Documents");

			// 1001.1: two today and one yesterday; 1002.1: one 6 days ago, inside the week, and one 7 days ago,
			// outside it; 1001.2: one 29 days ago, inside the 30, and one 30 days ago, outside them
			file(charter, 10, 0);
			file(charter, 20, 0);
			file(charter, 30, 1);
			file(tribunal, 40, 6);
			file(tribunal, 50, 7);
			file(purchase, 60, 29);
			file(purchase, 70, 30);
			// In the Trash: not counted
			Document gone = file(tribunal, 1000, 0);
			jdbc.update("update ld_document set ld_deleted = 1 where ld_id = ?", gone.getId());
			documents.remove(gone);

			// Created long ago, but for one client and two matters
			jdbc.update("update hd_client set ld_creation = ? where ld_id = ?", daysAgo(40), beech.getId());
			jdbc.update("update hd_matter set ld_creation = ? where ld_id = ?", daysAgo(10), tribunal.getId());
			jdbc.update("update hd_matter set ld_creation = ? where ld_id = ?", daysAgo(50), purchase.getId());
		}

		/**
		 * Files a document of a size into the matter's Documents folder, filed
		 * and last saved at 10:00 that many days ago
		 */
		Document file(Matter matter, int size, int daysAgo) throws Exception {
			Folder workspace = FolderDAO.get().findById(matter.getFolderId());
			Folder documentsFolder = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
			byte[] content = new byte[size];
			Arrays.fill(content, (byte) 'a');
			Document document = fx.upload(documentsFolder, "file-" + documents.size() + ".txt", content, fx.admin());
			HealdanFixtures.awaitVersionSaves();
			jdbc.update("update ld_document set ld_creation = ?, ld_date = ?, ld_filesize = ? where ld_id = ?",
					daysAgo(daysAgo), daysAgo(daysAgo), size, document.getId());
			documents.add(document);
			return document;
		}
	}

	/** 10:00 on the summary's day that many days before its today */
	private Timestamp daysAgo(int days) {
		return Timestamp.from(today.minusDays(days).atTime(10, 0).atZone(zone).toInstant());
	}

	// Until it's been worked out once, a request gets an empty one with no time, and asks no question of the database
	@Test
	public void testNothingWorkedOutYet() throws Exception {
		new Firm();

		Usage usage = summary.current();

		assertNull(usage.computedAt());
		assertNull(usage.documents());
		assertNull(usage.storage());
		assertNull(usage.matters());
		assertNull(usage.clients());
		assertNull(usage.people());
		assertEquals(List.of(), usage.filedPerDay());
		assertEquals(List.of(), usage.stores());
		assertEquals(List.of(), usage.busiestMatters());
		assertNull(summary.forMatter(1L));
	}

	@Test
	public void testTotals() throws Exception {
		Firm firm = new Firm();
		// The admin long ago, a clerk today, and neither a disabled person nor _system
		jdbc.update("update ld_user set ld_creation = ? where ld_username = 'admin'", daysAgo(100));
		fx.user("clerk");
		User gone = fx.user("gone");
		jdbc.update("update ld_user set ld_enabled = 0 where ld_id = ?", gone.getId());

		summary.refreshNow();
		Usage usage = summary.current();

		assertEquals(summary.clock.instant(), usage.computedAt());
		assertEquals(7, firm.documents.size());
		assertEquals(new Total(7, 4, 6), usage.documents());
		assertEquals(new Total(10 + 20 + 30 + 40 + 50 + 60 + 70, 10 + 20 + 30 + 40, 10 + 20 + 30 + 40 + 50 + 60),
				usage.storage());
		assertEquals(new Total(3, 1, 2), usage.matters());
		assertEquals(new Total(2, 1, 1), usage.clients());
		assertEquals(new Total(2, 1, 1), usage.people());
	}

	@Test
	public void testDocumentsPerDayForThirtyDays() throws Exception {
		new Firm();

		summary.refreshNow();
		List<Day> days = summary.current().filedPerDay();

		assertEquals(30, days.size());
		assertEquals(today.minusDays(29), days.get(0).date());
		assertEquals(today, days.get(29).date());
		for (int i = 1; i < days.size(); i++)
			assertEquals(days.get(i - 1).date().plusDays(1), days.get(i).date());
		long[] counts = days.stream().mapToLong(Day::count).toArray();
		long[] expected = new long[30];
		expected[29] = 2;
		expected[28] = 1;
		expected[23] = 1;
		expected[22] = 1;
		expected[0] = 1;
		assertEquals(Arrays.toString(expected), Arrays.toString(counts));

		// The last 7 and the last 30 add up to the totals' changes
		Usage usage = summary.current();
		assertEquals(usage.documents().last7Days(), days.subList(23, 30).stream().mapToLong(Day::count).sum());
		assertEquals(usage.documents().last30Days(), days.stream().mapToLong(Day::count).sum());
	}

	@Test
	public void testStorageByStore() throws Exception {
		Firm firm = new Firm();
		Folder empty = fx.store("Archive");

		summary.refreshNow();
		List<StoreUsage> stores = summary.current().stores();

		// Most bytes first; neither Default nor Templates; no backend without the storage port
		assertEquals(List.of(new StoreUsage(firm.clientWork.getId(), "Client Work", 5, 10 + 20 + 30 + 60 + 70, null),
				new StoreUsage(firm.firm.getId(), "Firm", 2, 40 + 50, null),
				new StoreUsage(empty.getId(), "Archive", 0, 0, null)), stores);
	}

	// With storage backends on, each Store says the backend its files are on
	@Test
	public void testStorageByStoreNamesTheBackends() throws Exception {
		Firm firm = new Firm();
		StorageOverview overview = new StorageOverview(true,
				List.of(new BackendInfo(1, "Original", "local", "/srv/store", false, 0, 0, BackendInfo.OK, null),
						new BackendInfo(7, "Wasabi EU", "s3", "https://s3.example.com/firm/", true, 0, 0,
								BackendInfo.OK, null)),
				List.of(new StoreBackendInfo(firm.clientWork.getId(), "Client Work", 7, "Wasabi EU", 0, 0, 0, 5, 999),
						new StoreBackendInfo(firm.firm.getId(), "Firm", 1, "Original", 0, 0, 0, 2, 99)),
				new StorageSettingsInfo(30, 10, 4), 0, 0, 0);
		summary.ports = () -> List.of(OverviewPort.of(() -> overview));

		summary.refreshNow();
		List<StoreUsage> stores = summary.current().stores();

		assertEquals(new Backend(7, "Wasabi EU", "s3", true), stores.get(0).backend());
		assertEquals(new Backend(1, "Original", "local", false), stores.get(1).backend());
		// The bytes are the documents', as the totals' are
		assertEquals(10 + 20 + 30 + 60 + 70, stores.get(0).bytes());

		// Storage backends off: no backend
		StorageOverview off = new StorageOverview(false, List.of(), List.of(), new StorageSettingsInfo(30, 10, 4), 0,
				0, 0);
		summary.ports = () -> List.of(OverviewPort.of(() -> off));
		summary.refreshNow();
		assertNull(summary.current().stores().get(0).backend());
	}

	// A storage port that can't answer leaves the backends out, and the rest whole
	@Test
	public void testAFailingStoragePortLeavesTheRest() throws Exception {
		new Firm();
		OverviewPort failing = () -> {
			throw new PersistenceException("The storage tables can't be read");
		};
		summary.ports = () -> List.of(failing);

		try (LogCapture log = LogCapture.of(UsageSummary.class)) {
			summary.refreshNow();
			assertTrue(log.lines().toString(), log.lines().stream().anyMatch(line -> line.startsWith("WARN ")));
		}

		Usage usage = summary.current();
		assertNotNull(usage.computedAt());
		assertEquals(7, usage.documents().total());
		assertEquals(2, usage.stores().size());
		assertNull(usage.stores().get(0).backend());
		assertNull(usage.stores().get(1).backend());
	}

	@Test
	public void testBusiestMattersForSevenDays() throws Exception {
		Firm firm = new Firm();

		summary.refreshNow();

		// Filed in the last 7 days, most first; 1001.2 filed nothing this week
		assertEquals(List.of(new BusyMatter(firm.charter.getId(), "1001.1", "Charter Dispute", 3),
				new BusyMatter(firm.tribunal.getId(), "1002.1", "Employment Tribunal", 1)),
				summary.current().busiestMatters());
	}

	// At most five, ties by client then matter number
	@Test
	public void testAtMostFiveBusiestMatters() throws Exception {
		Folder store = fx.store("Client Work");
		com.logicaldoc.healdan.matter.Client client = fx.client(7, "Busy");
		List<Matter> matters = new ArrayList<>();
		for (int number = 6; number >= 1; number--)
			matters.add(0, fx.matter(client, number, "Matter " + number, store, "Documents"));
		for (Matter matter : matters) {
			Folder workspace = FolderDAO.get().findById(matter.getFolderId());
			Folder folder = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
			fx.upload(folder, "one.txt", "one", fx.admin());
		}
		// Matter 7.4 the busiest
		Folder four = FolderDAO.get().findByName(FolderDAO.get().findById(matters.get(3).getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		fx.upload(four, "two.txt", "two", fx.admin());

		summary.refreshNow();

		assertEquals(List.of("7.4", "7.1", "7.2", "7.3", "7.5"),
				summary.current().busiestMatters().stream().map(BusyMatter::number).toList());
	}

	@Test
	public void testMatterStats() throws Exception {
		Firm firm = new Firm();

		summary.refreshNow();

		// 1002.1's two documents; the one in the Trash isn't counted
		assertEquals(new MatterStats(2, 40 + 50, daysAgo(6).toInstant()), summary.forMatter(firm.tribunal.getId()));
		assertEquals(new MatterStats(3, 10 + 20 + 30, daysAgo(0).toInstant()), summary.forMatter(firm.charter.getId()));

		// A matter with no documents, and an id that's no matter
		com.logicaldoc.healdan.matter.Client client = fx.client(1003, "Cedar");
		Matter quiet = fx.matter(client, 1, "Quiet", firm.firm, "Documents");
		summary.refreshNow();
		assertEquals(new MatterStats(0, 0, null), summary.forMatter(quiet.getId()));
		assertEquals(new MatterStats(0, 0, null), summary.forMatter(-99L));
	}

	// A document whose stamps were typed with spaces still counts for its matter
	@Test
	public void testStampsAreReadTrimmed() throws Exception {
		Firm firm = new Firm();
		Document spaced = firm.file(firm.tribunal, 5, 2);
		jdbc.update("update ld_document_ext set ld_stringvalue = ' 1002 ' where ld_docid = ? and ld_name = 'client'",
				spaced.getId());

		summary.refreshNow();

		assertEquals(3, summary.forMatter(firm.tribunal.getId()).documents());
		assertEquals(new BusyMatter(firm.tribunal.getId(), "1002.1", "Employment Tribunal", 2),
				summary.current().busiestMatters().get(1));
	}

	// Who's signed in now: people, each once, counted at each request from the server's own sessions
	@Test
	public void testSignedInNow() throws Exception {
		fx.user("clerk");
		fx.user("partner");
		SessionManager.get().newSession("clerk", HealdanFixtures.PASSWORD, (Client) null);
		SessionManager.get().newSession("clerk", HealdanFixtures.PASSWORD, (Client) null);
		Session partner = SessionManager.get().newSession("partner", HealdanFixtures.PASSWORD, (Client) null);

		// Before any summary too
		assertEquals(2, summary.current().signedIn());

		SessionManager.get().kill(partner.getSid());
		assertEquals(1, summary.current().signedIn());
	}

	// A request reads the summary in memory: the database is asked only when it's worked out
	// A reader's days are their time zone's (R90): 10:00 here is 23:00 the day before for a reader 11 hours behind
	@Test
	public void testTheReadersZoneCutsTheDays() throws Exception {
		new Firm();
		Instant noon = summary.clock.instant();
		ZoneId behind = ZoneOffset.ofTotalSeconds(zone.getRules().getOffset(noon).getTotalSeconds() - 11 * 3600);

		// Asked for the first time: the empty summary, and the zone's own comes with the next
		assertNull(summary.current(behind).computedAt());
		summary.refreshNow();
		Usage usage = summary.current(behind);

		List<Day> days = usage.filedPerDay();
		assertEquals(today, days.get(29).date());
		long[] expected = new long[30];
		expected[28] = 2;
		expected[27] = 1;
		expected[22] = 1;
		expected[21] = 1;
		assertEquals(Arrays.toString(expected), Arrays.toString(days.stream().mapToLong(Day::count).toArray()));
		assertEquals(3, usage.documents().last7Days());
		assertEquals(5, usage.documents().last30Days());

		// The server's own stays its own
		assertEquals(4, summary.current().documents().last7Days());
		assertEquals(6, summary.current().documents().last30Days());
	}

	@Test
	public void testASecondCurrentRunsNoQuery() throws Exception {
		new Firm();
		CountingDataSource counting = new CountingDataSource(Context.get(DataSource.class));
		UsageSummary own = new UsageSummary(counting.dataSource(), HealdanSettings.get());
		own.clock = summary.clock;

		own.refreshNow();
		assertFalse("The summary's own queries are counted", counting.queries().isEmpty());

		counting.reset();
		Usage first = own.current();
		Usage second = own.current();
		own.forMatter(1L);

		assertEquals(List.of(), counting.queries().stream().map(q -> q.sql).toList());
		assertEquals(first, second);
		assertEquals(7, second.documents().total());
	}

	/** A data source that fails every connection while broken */
	private static final class Breakable {

		volatile boolean broken;

		final DataSource dataSource;

		Breakable(DataSource target) {
			dataSource = (DataSource) Proxy.newProxyInstance(UsageSummaryTest.class.getClassLoader(),
					new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
						if (broken && method.getName().equals("getConnection"))
							throw new SQLException("The database can't be reached");
						try {
							return method.invoke(target, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});
		}
	}

	// A refresh that fails keeps the last summary, and says when it failed, until one works again
	@Test
	public void testAFailedRefreshKeepsTheLastSummary() throws Exception {
		Firm firm = new Firm();
		Breakable database = new Breakable(Context.get(DataSource.class));
		UsageSummary own = new UsageSummary(database.dataSource, HealdanSettings.get());
		Instant noon = summary.clock.instant();
		own.clock = Clock.fixed(noon, zone);

		// Before any summary: a failure, and still nothing worked out
		database.broken = true;
		assertThrows(RuntimeException.class, own::refreshNow);
		assertNull(own.current().computedAt());
		assertEquals(noon, own.current().failedAt());
		assertNull(own.current().documents());

		database.broken = false;
		own.refreshNow();
		Usage good = own.current();
		assertEquals(noon, good.computedAt());
		assertNull(good.failedAt());
		MatterStats charter = own.forMatter(firm.charter.getId());

		database.broken = true;
		own.clock = Clock.fixed(noon.plusSeconds(900), zone);
		assertThrows(RuntimeException.class, own::refreshNow);
		Usage kept = own.current();
		assertEquals(noon, kept.computedAt());
		assertEquals(noon.plusSeconds(900), kept.failedAt());
		assertEquals(good.documents(), kept.documents());
		assertEquals(good.stores(), kept.stores());
		assertEquals(charter, own.forMatter(firm.charter.getId()));

		database.broken = false;
		own.clock = Clock.fixed(noon.plusSeconds(1800), zone);
		own.refreshNow();
		assertEquals(noon.plusSeconds(1800), own.current().computedAt());
		assertNull(own.current().failedAt());
	}

	// Stopping the server mid-refresh is no fault: nothing is logged as an error
	@Test
	public void testStoppingMidRefreshLogsNoError() throws Exception {
		CountDownLatch connecting = new CountDownLatch(1);
		DataSource target = Context.get(DataSource.class);
		DataSource stalling = (DataSource) Proxy.newProxyInstance(UsageSummaryTest.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					if (method.getName().equals("getConnection")) {
						connecting.countDown();
						try {
							Thread.sleep(60000L);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new SQLException("Interrupted while connecting", e);
						}
					}
					try {
						return method.invoke(target, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		UsageSummary own = new UsageSummary(stalling, HealdanSettings.get());

		try (LogCapture log = LogCapture.of(UsageSummary.class, Level.DEBUG)) {
			own.started();
			assertTrue("The refresh didn't start", connecting.await(30, TimeUnit.SECONDS));
			own.stop();

			assertEquals(log.lines().toString(), List.of(),
					log.lines().stream().filter(line -> !line.startsWith("DEBUG ")).toList());
			assertTrue(log.lines().toString(), log.lines().stream().anyMatch(line -> line.startsWith("DEBUG ")));
		}
	}

	// It works itself out on its own thread, healdan-admin-summary, never on the caller's
	@Test
	public void testRefreshesOnItsOwnThread() throws Exception {
		new Firm();
		summary.clock = Clock.systemDefaultZone();
		try {
			summary.started();
			summary.started();

			long deadline = System.currentTimeMillis() + 30000L;
			while (summary.current().computedAt() == null) {
				assertTrue("The summary wasn't worked out", System.currentTimeMillis() < deadline);
				Thread.sleep(50L);
			}
			Instant first = summary.current().computedAt();
			assertEquals(7, summary.current().documents().total());

			List<Thread> threads = Thread.getAllStackTraces().keySet().stream()
					.filter(t -> t.getName().equals(UsageSummary.THREAD)).toList();
			assertEquals(threads.toString(), 1, threads.size());
			assertTrue(threads.get(0).isDaemon());

			// Not again within MAX_AGE
			Thread.sleep(200L);
			assertEquals(first, summary.current().computedAt());
		} finally {
			summary.stop();
		}
	}
}
