// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanStartListener;
import com.logicaldoc.healdan.admin.dashboard.Usage.Backend;
import com.logicaldoc.healdan.admin.dashboard.Usage.BusyMatter;
import com.logicaldoc.healdan.admin.dashboard.Usage.Day;
import com.logicaldoc.healdan.admin.dashboard.Usage.StoreUsage;
import com.logicaldoc.healdan.admin.dashboard.Usage.Total;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

import jakarta.annotation.PreDestroy;

/**
 * The Dashboard's Usage (Admin app spec section 8.1), and each matter's figures for the Clients and Matters lists: one
 * summary in memory, so a request costs almost nothing. It is worked out on a thread of its own,
 * {@value #THREAD}, at the start and then {@link #MAX_AGE} after the last one ended, never on a request's thread: a
 * request gets the last one, or {@link Usage}'s empty one until the first is made. One that fails keeps the last
 * one, with {@code failedAt}, until one works again. Tests call {@link #refreshNow()} themselves, as no test makes a
 * real start.
 * <p>
 * The last 7 and 30 days, and each day filed, start at midnight in the reader's time zone, as the User app's Today
 * and date ranges do (R90): a summary is kept for the server's zone and for the {@value #ZONES} zones asked for most
 * recently ({@link #current(ZoneId)}). A zone asked for the first time gets the empty summary, and has its own made at
 * once on the summary's thread, then with every other.
 * <p>
 * A matter's documents are counted through the client and matter numbers stamped on them, in {@code ld_document_ext}
 * (plan, correction 5), so no folder is walked; a Store's are its matters'. It reads with plain SQL that MariaDB and
 * PostgreSQL both take, through the {@code DataSource} it's given. The storage port, when there is one, names each
 * Store's backend; a port that fails leaves the backends out and the rest whole.
 */
@Component
public class UsageSummary implements HealdanStartListener {

	private static final Logger log = LoggerFactory.getLogger(UsageSummary.class);

	/** The longest a summary is kept before the next is worked out */
	static final Duration MAX_AGE = Duration.ofMinutes(15);

	/** The thread that works it out */
	static final String THREAD = "healdan-admin-summary";

	/** How many busiest matters there are at most */
	public static final int BUSIEST = 5;

	/** The days of documents filed per day, today included */
	static final int DAYS = 30;

	/** The days of "the last 7 days", today included */
	static final int WEEK = 7;

	/** The most time zones, besides the server's, a summary is kept for: those asked for most recently */
	static final int ZONES = 4;

	/** A live document: not deleted, not a shortcut */
	private static final String LIVE_DOCUMENT = "d.ld_deleted = 0 and d.ld_docref is null and d.ld_tenantid = :tenant";

	private static final String DOCUMENTS = """
			select count(*) as total,
			       sum(case when d.ld_creation >= :week then 1 else 0 end) as thisweek,
			       sum(case when d.ld_creation >= :month then 1 else 0 end) as thismonth,
			       sum(coalesce(d.ld_filesize, 0)) as bytes,
			       sum(case when d.ld_creation >= :week then coalesce(d.ld_filesize, 0) else 0 end) as weekbytes,
			       sum(case when d.ld_creation >= :month then coalesce(d.ld_filesize, 0) else 0 end) as monthbytes
			  from ld_document d
			 where %s""".formatted(LIVE_DOCUMENT);

	/**
	 * The documents filed on each of the {@link #DAYS} days, {@code day0} to {@code day29}: each day from its
	 * {@code :dayN} to the next, as the reader's zone cuts them, which the date a time is stored with can't
	 */
	private static final String PER_DAY = perDay();

	/** By the client and matter stamped on each document, as M6's Matter filter reads them */
	private static final String PER_MATTER = """
			select c.ld_stringvalue as client, m.ld_stringvalue as matter, count(*) as documents,
			       sum(coalesce(d.ld_filesize, 0)) as bytes,
			       max(coalesce(d.ld_date, d.ld_creation)) as lastactivity,
			       sum(case when d.ld_creation >= :week then 1 else 0 end) as thisweek
			  from ld_document d
			  join ld_document_ext c on c.ld_docid = d.ld_id and c.ld_name = :clientname
			  join ld_document_ext m on m.ld_docid = d.ld_id and m.ld_name = :mattername
			 where %s and c.ld_stringvalue is not null and m.ld_stringvalue is not null
			 group by c.ld_stringvalue, m.ld_stringvalue""".formatted(LIVE_DOCUMENT);

	/** Every matter not deleted, with its client's number, and the Store and state of its folder */
	private static final String MATTERS = """
			select m.ld_id, m.hd_number, m.hd_name, m.ld_creation, c.hd_number as clientnumber,
			       f.ld_parentid as storeid, f.ld_deleted as folderdeleted
			  from hd_matter m
			  join hd_client c on c.ld_id = m.hd_clientid
			  join ld_folder f on f.ld_id = m.hd_folderid
			 where m.ld_deleted = 0""";

	private static final String CLIENTS = """
			select count(*) as total,
			       sum(case when ld_creation >= :week then 1 else 0 end) as thisweek,
			       sum(case when ld_creation >= :month then 1 else 0 end) as thismonth
			  from hd_client
			 where ld_deleted = 0""";

	private static final String PEOPLE = """
			select count(*) as total,
			       sum(case when ld_creation >= :week then 1 else 0 end) as thisweek,
			       sum(case when ld_creation >= :month then 1 else 0 end) as thismonth
			  from ld_user
			 where ld_deleted = 0 and ld_enabled = 1 and ld_type <> :system and ld_tenantid = :tenant""";

	/** The workspaces directly under the root, of which every one but Default and Templates is a Store */
	private static final String STORES = """
			select f.ld_id as id, f.ld_name as name
			  from ld_folder f
			 where f.ld_parentid = :root and f.ld_id <> :root and f.ld_type = :workspace and f.ld_deleted = 0
			   and f.ld_tenantid = :tenant""";

	private final NamedParameterJdbcTemplate jdbc;

	private final HealdanSettings settings;

	/** Each time zone's last summary, the server's among them, and each matter's figures by its id; replaced whole */
	private volatile Map<ZoneId, Snapshot> snapshots = Map.of();

	/** The zones asked for besides the server's, the one asked for least recently first; guarded by itself */
	private final Map<ZoneId, Boolean> wanted = new LinkedHashMap<>(16, 0.75f, true);

	/** Whether a refresh for a zone asked for the first time waits on the summary's thread */
	private final AtomicBoolean queued = new AtomicBoolean();

	private ScheduledExecutorService scheduler;

	/** Set while the thread is being stopped, which interrupts a refresh */
	private volatile boolean stopping;

	/** Held while a summary is worked out, so two never run at once */
	private final Object refreshing = new Object();

	/** What "now" is; tests fix it */
	volatile Clock clock = Clock.systemDefaultZone();

	/** The storage ports, of which there's one with healdan-storage, else none; tests give their own */
	volatile Supplier<List<StorageAdministration>> ports = StorageAdministration::ports;

	@Autowired
	public UsageSummary(DataSource dataSource, HealdanSettings settings) {
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
		this.settings = settings;
	}

	/**
	 * @return the bean
	 */
	public static UsageSummary get() {
		return Context.get(UsageSummary.class);
	}

	/**
	 * Reads the server's time zone's summary in memory, with no query.
	 *
	 * @return as {@link #current(ZoneId)} gives it for the server's zone
	 */
	public Usage current() {
		return current(null);
	}

	/**
	 * Reads the summary in memory, with no query: what a request calls.
	 *
	 * @param zone the time zone whose days the summary is cut in, the reader's; null for the server's
	 * @return the zone's last summary, or one with no {@code computedAt}, null totals and empty lists if none has been
	 *         worked out yet, as for a zone asked for the first time, whose own this starts; {@code signedIn} is counted
	 *         now, from the server's sessions in memory
	 */
	public Usage current(ZoneId zone) {
		ZoneId key = zone != null ? zone.normalized() : serverZone();
		Snapshot last = snapshots.get(key);
		want(key, last == null);
		return (last != null ? last : Snapshot.NONE).usage().signedIn(signedIn());
	}

	/**
	 * Reads a matter's figures from the summary in memory, with no query.
	 *
	 * @param matterId the matter's id
	 * @return its figures as last worked out, {@link MatterStats#NONE} for a matter with no documents, or one the
	 *         summary doesn't know; null if no summary has been worked out yet
	 */
	public MatterStats forMatter(long matterId) {
		// A matter's figures have no days, so the server's zone's summary has them
		Snapshot last = snapshots.get(serverZone());
		if (last == null || last.usage().computedAt() == null)
			return null;
		return last.matters().getOrDefault(matterId, MatterStats.NONE);
	}

	/**
	 * Works the summary out now, for the server's time zone and each asked for, on the calling thread, which is never a
	 * request's: the summary's own thread and tests call it. If that fails, each zone's last summary stays, with
	 * {@code failedAt} the time it failed.
	 *
	 * @throws RuntimeException what failed, a {@code DataAccessException} say
	 */
	public void refreshNow() {
		synchronized (refreshing) {
			List<ZoneId> zones = zones();
			try {
				Instant now = clock.instant();
				Map<Long, Backend> backends = backends();
				Map<ZoneId, Snapshot> made = new HashMap<>();
				for (ZoneId zone : zones)
					made.put(zone, refresh(now, zone, backends));
				snapshots = Map.copyOf(made);
			} catch (RuntimeException | Error e) {
				// Each last good summary stays, saying when this one failed
				Instant failed = clock.instant();
				Map<ZoneId, Snapshot> kept = new HashMap<>();
				for (ZoneId zone : zones) {
					Snapshot last = snapshots.getOrDefault(zone, Snapshot.NONE);
					kept.put(zone, new Snapshot(last.usage().failedAt(failed), last.matters()));
				}
				snapshots = Map.copyOf(kept);
				throw e;
			}
		}
	}

	/** @return the server's time zone, the key of its summary */
	private ZoneId serverZone() {
		return clock.getZone().normalized();
	}

	/** @return the server's time zone, then those asked for */
	private List<ZoneId> zones() {
		List<ZoneId> zones = new ArrayList<>();
		zones.add(serverZone());
		synchronized (wanted) {
			for (ZoneId zone : wanted.keySet())
				if (!zones.contains(zone))
					zones.add(zone);
		}
		return zones;
	}

	/**
	 * Keeps a zone among those a summary is made for, the {@link #ZONES} asked for most recently, and makes its first at
	 * once on the summary's thread when it has none
	 */
	private void want(ZoneId zone, boolean missing) {
		if (zone.equals(serverZone()))
			return;
		synchronized (wanted) {
			wanted.put(zone, Boolean.TRUE);
			while (wanted.size() > ZONES)
				wanted.remove(wanted.keySet().iterator().next());
		}
		if (missing)
			refreshSoon();
	}

	/** Works the summary out on its own thread as soon as it's free, once however many zones ask meanwhile */
	private synchronized void refreshSoon() {
		if (scheduler == null || !queued.compareAndSet(false, true))
			return;
		try {
			scheduler.execute(() -> {
				queued.set(false);
				refreshSafely();
			});
		} catch (RejectedExecutionException e) {
			// Stopping
			queued.set(false);
		}
	}

	/** @return the zone's summary at a time, its days the zone's, with each Store's backend as given */
	private Snapshot refresh(Instant now, ZoneId zone, Map<Long, Backend> backends) {
		LocalDate today = now.atZone(zone).toLocalDate();
		LocalDate firstDay = today.minusDays(DAYS - 1L);
		// The windows start at the start of their first day, the zone's
		Instant week = today.minusDays(WEEK - 1L).atStartOfDay(zone).toInstant();
		Instant month = firstDay.atStartOfDay(zone).toInstant();
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("week", Timestamp.from(week))
				.addValue("month", Timestamp.from(month)).addValue("tenant", Tenant.DEFAULT_ID)
				.addValue("clientname", HealdanConstants.ATTR_CLIENT)
				.addValue("mattername", HealdanConstants.ATTR_MATTER).addValue("system", User.Type.SYSTEM.ordinal())
				.addValue("root", Folder.ROOTID).addValue("workspace", Folder.TYPE_WORKSPACE);
		for (int i = 0; i <= DAYS; i++)
			params.addValue("day" + i, Timestamp.from(firstDay.plusDays(i).atStartOfDay(zone).toInstant()));

		// The documents, and their storage
		Total[] documents = new Total[2];
		jdbc.query(DOCUMENTS, params, rs -> {
			documents[0] = new Total(rs.getLong("total"), rs.getLong("thisweek"), rs.getLong("thismonth"));
			documents[1] = new Total(rs.getLong("bytes"), rs.getLong("weekbytes"), rs.getLong("monthbytes"));
		});

		long[] filed = new long[DAYS];
		jdbc.query(PER_DAY, params, rs -> {
			for (int i = 0; i < DAYS; i++)
				filed[i] = rs.getLong("day" + i);
		});
		List<Day> days = new ArrayList<>();
		for (int i = 0; i < DAYS; i++)
			days.add(new Day(firstDay.plusDays(i), filed[i]));

		// Each matter's figures, by the numbers stamped on its documents
		Map<Stamp, Figures> stamped = new HashMap<>();
		jdbc.query(PER_MATTER, params, rs -> {
			Stamp stamp = Stamp.of(rs.getString("client"), rs.getString("matter"));
			if (stamp == null)
				return;
			Timestamp last = rs.getTimestamp("lastactivity");
			stamped.merge(stamp, new Figures(rs.getLong("documents"), rs.getLong("bytes"),
					last != null ? last.toInstant() : null, rs.getLong("thisweek")), Figures::plus);
		});

		List<MatterRow> matterRows = jdbc.query(MATTERS, params,
				(rs, i) -> new MatterRow(rs.getLong("ld_id"), rs.getLong("clientnumber"), rs.getLong("hd_number"),
						rs.getString("hd_name"), rs.getTimestamp("ld_creation").toInstant(), rs.getLong("storeid"),
						rs.getInt("folderdeleted") == 0));
		Map<Long, MatterStats> matterStats = new HashMap<>();
		Map<Long, Figures> byStore = new HashMap<>();
		List<Busy> busy = new ArrayList<>();
		long liveMatters = 0;
		long mattersThisWeek = 0;
		long mattersThisMonth = 0;
		for (MatterRow matter : matterRows) {
			Figures figures = stamped.get(new Stamp(matter.client(), matter.number()));
			if (figures != null)
				matterStats.put(matter.id(),
						new MatterStats(figures.documents(), figures.bytes(), figures.lastActivity()));
			if (!matter.live())
				continue;
			if (figures != null && figures.week() > 0)
				busy.add(new Busy(matter, figures.week()));
			liveMatters++;
			if (!matter.created().isBefore(week))
				mattersThisWeek++;
			if (!matter.created().isBefore(month))
				mattersThisMonth++;
			if (figures != null)
				byStore.merge(matter.store(), figures, Figures::plus);
		}
		// Most filed first, then by client and matter number
		busy.sort(Comparator.comparingLong(Busy::filed).reversed()
				.thenComparingLong((Busy b) -> b.matter().client()).thenComparingLong(b -> b.matter().number()));
		List<BusyMatter> busiest = new ArrayList<>();
		for (Busy b : busy.subList(0, Math.min(BUSIEST, busy.size()))) {
			MatterRow m = b.matter();
			busiest.add(new BusyMatter(m.id(), MatterDisplay.number(m.client(), m.number()), m.name(), b.filed()));
		}

		Total clients = total(CLIENTS, params);
		Total people = total(PEOPLE, params);
		List<StoreUsage> stores = stores(params, byStore, backends);

		Usage usage = new Usage(now, null, documents[0], documents[1],
				new Total(liveMatters, mattersThisWeek, mattersThisMonth), clients, people, 0, days, stores, busiest);
		return new Snapshot(usage, Map.copyOf(matterStats));
	}

	/** @return the query of {@link #PER_DAY} */
	private static String perDay() {
		StringBuilder columns = new StringBuilder();
		for (int i = 0; i < DAYS; i++)
			columns.append(i == 0 ? "" : ", ").append(
					"sum(case when d.ld_creation >= :day%d and d.ld_creation < :day%d then 1 else 0 end) as day%d"
							.formatted(i, i + 1, i));
		return "select " + columns + " from ld_document d where " + LIVE_DOCUMENT + " and d.ld_creation >= :day0";
	}

	private Total total(String sql, MapSqlParameterSource params) {
		return jdbc.queryForObject(sql, params,
				(rs, i) -> new Total(rs.getLong("total"), rs.getLong("thisweek"), rs.getLong("thismonth")));
	}

	/**
	 * @return the Stores, but Default and Templates, most bytes first, then by name, each with what its matters hold
	 *         and the backend the storage port says it's on
	 */
	private List<StoreUsage> stores(MapSqlParameterSource params, Map<Long, Figures> byStore,
			Map<Long, Backend> backends) {
		Long templates = settings.templatesStoreId();
		List<StoreUsage> stores = new ArrayList<>();
		jdbc.query(STORES, params, rs -> {
			long id = rs.getLong("id");
			if (id == Folder.DEFAULTWORKSPACEID || Long.valueOf(id).equals(templates))
				return;
			Figures figures = byStore.getOrDefault(id, Figures.NONE);
			stores.add(new StoreUsage(id, rs.getString("name"), figures.documents(), figures.bytes(),
					backends.get(id)));
		});
		stores.sort(Comparator.comparingLong(StoreUsage::bytes).reversed()
				.thenComparing(StoreUsage::name, String.CASE_INSENSITIVE_ORDER).thenComparingLong(StoreUsage::id));
		return stores;
	}

	/**
	 * @return each Store's backend by the Store's id, as the storage port says; empty without a port, with storage
	 *         backends off, or when the port fails, which is logged
	 */
	private Map<Long, Backend> backends() {
		Map<Long, Backend> byStore = new HashMap<>();
		for (StorageAdministration port : ports.get()) {
			try {
				StorageOverview overview = port.overview();
				if (!overview.on())
					continue;
				Map<Long, BackendInfo> backends = new HashMap<>();
				for (BackendInfo backend : overview.backends())
					backends.put(backend.id(), backend);
				for (StoreBackendInfo store : overview.stores()) {
					BackendInfo backend = backends.get(store.backendId());
					if (backend != null)
						byStore.put(store.storeId(),
								new Backend(backend.id(), backend.name(), backend.kind(), backend.encrypted()));
				}
			} catch (PersistenceException | RuntimeException e) {
				log.warn("The usage summary can't name the Stores' backends: {}", e.getMessage(), e);
			}
		}
		return byStore;
	}

	/**
	 * @return how many people have a session open on this server, each once; system accounts aren't people
	 */
	private static int signedIn() {
		Set<Long> people = new HashSet<>();
		for (Session session : SessionManager.get().getSessions()) {
			User user = session.getUser();
			if (session.isOpen() && user != null && user.getType() != User.Type.SYSTEM)
				people.add(user.getId());
		}
		return people.size();
	}

	/**
	 * Works the summary out at once on its own thread, then again {@link #MAX_AGE} after each one ended. Once only.
	 */
	@Override
	public synchronized void started() {
		if (scheduler != null)
			return;
		stopping = false;
		scheduler = Executors.newSingleThreadScheduledExecutor(work -> {
			Thread thread = new Thread(work, THREAD);
			thread.setDaemon(true);
			return thread;
		});
		scheduler.scheduleWithFixedDelay(this::refreshSafely, 0, MAX_AGE.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Works the summary out, keeping the last one if that fails, so the schedule goes on
	 */
	private void refreshSafely() {
		try {
			refreshNow();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable e) {
			// Stopping interrupts a refresh: no fault, so no error for the Errors screen to count
			if (stopping || Thread.currentThread().isInterrupted())
				log.debug("The usage summary stopped while it was being worked out: {}", e.getMessage());
			else
				// An Error such as a class missing from a plugin's zip must not end the schedule either
				log.error("The Dashboard's usage summary couldn't be worked out: {}", e.getMessage(), e);
		}
	}

	/**
	 * Stops the thread.
	 */
	@PreDestroy
	public void stop() {
		ScheduledExecutorService running;
		synchronized (this) {
			running = scheduler;
			scheduler = null;
			stopping = running != null;
		}
		if (running == null)
			return;
		running.shutdownNow();
		try {
			if (!running.awaitTermination(10, TimeUnit.SECONDS))
				log.warn("The usage summary's thread didn't stop within 10 seconds");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The summary and each matter's figures, replaced whole */
	private record Snapshot(Usage usage, Map<Long, MatterStats> matters) {

		static final Snapshot NONE = new Snapshot(Usage.NONE, Map.of());
	}

	/** A client number and a matter number, as stamped on a document */
	private record Stamp(long client, long matter) {

		/**
		 * @return the numbers, read trimmed; null if either isn't one
		 */
		static Stamp of(String client, String matter) {
			if (client == null || matter == null)
				return null;
			try {
				return new Stamp(Long.parseLong(client.strip()), Long.parseLong(matter.strip()));
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}

	/** What some documents add up to */
	private record Figures(long documents, long bytes, Instant lastActivity, long week) {

		static final Figures NONE = new Figures(0, 0, null, 0);

		Figures plus(Figures other) {
			Instant last = lastActivity == null ? other.lastActivity
					: other.lastActivity == null || lastActivity.isAfter(other.lastActivity) ? lastActivity
							: other.lastActivity;
			return new Figures(documents + other.documents, bytes + other.bytes, last, week + other.week);
		}
	}

	/** A matter, as the summary reads it */
	private record MatterRow(long id, long client, long number, String name, Instant created, long store,
			boolean live) {
	}

	/** A matter documents were filed in in the last 7 days, and how many */
	private record Busy(MatterRow matter, long filed) {
	}
}
