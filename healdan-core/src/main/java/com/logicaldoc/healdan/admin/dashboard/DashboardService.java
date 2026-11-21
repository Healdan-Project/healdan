// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.communication.EMailSender;
import com.logicaldoc.healdan.admin.dashboard.Cached.Outcome;
import com.logicaldoc.healdan.admin.errors.ErrorGroup;
import com.logicaldoc.healdan.admin.errors.ErrorStore;
import com.logicaldoc.healdan.admin.index.IndexCounts;
import com.logicaldoc.healdan.admin.jobs.JobView;
import com.logicaldoc.healdan.admin.jobs.Jobs;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;
import com.logicaldoc.healdan.preview.PreviewJobs;
import com.logicaldoc.healdan.signin.OutgoingEmail;
import com.logicaldoc.healdan.signin.ProviderClient;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.web.Landing;
import com.logicaldoc.util.spring.Context;

/**
 * The Dashboard's Needs Attention and Server Health (Admin app spec section 8.1): eight attention codes and ten tiles,
 * each from its own source.
 * <ul>
 * <li><b>Storage</b> ({@code files-stuck}, {@code backend-unreachable}, the Storage Backends tile): the storage port
 * ({@link StorageAdministration}, spec section 9.7), whose stuck files are its Stores' and those with no Store, and
 * whose backends that are {@code unreachable} or {@code failing} are ones Healdan can't use. With no port, as in
 * {@code healdan-core}'s tests, there's nothing to check unless CE's {@code store} is {@code HealdanStorage}; with the
 * port, nothing while it says storage backends are off.</li>
 * <li><b>The Search Index</b> ({@code index-failed}, the Search Index tile): {@link IndexCounts#counts()}.</li>
 * <li><b>Errors</b> ({@code errors}): {@link ErrorStore#unresolved} the last {@link #ERRORS_WINDOW}.</li>
 * <li><b>Previews</b> ({@code previews-given-up}, the Previews tile): {@link PreviewJobs}' counts.</li>
 * <li><b>Background jobs</b> ({@code job-failed}, {@code job-overdue}, the Background Jobs tile): {@link Jobs#all()},
 * a job switched on whose last run failed, and one {@link JobView#overdue overdue} that didn't, so none is counted
 * twice. A job switched off is neither.</li>
 * <li><b>Disks</b> ({@code disk-low}, the Disk tile): the volumes of the repository ({@code store.1.dir}), the index
 * ({@code index.dir}) and the logs ({@code conf.logdir}), each once, and one more than {@value #DISK_LOW_PERCENT}%
 * used, of its total, is low. Used is the total less what the server can still write.</li>
 * <li><b>The tiles</b>: the Server's time up from the JVM and Healdan's version from CE's {@code product.release},
 * which {@code SystemInfo} reports (without its host name lookup); the Database's product and version from JDBC and
 * its size by its own query (PostgreSQL's {@code pg_database_size}, MariaDB's tables of the connection's catalog);
 * the Memory's heap from {@link Runtime}; Sign-In's enabled providers, each asked whether it answers
 * ({@link ProviderClient#answers}) at most every {@link #SIGN_IN_MAX_AGE}; and Outgoing Email set up as
 * {@link OutgoingEmail#isSetUp()} says, its last send failed if CE's mail sender's logger ({@link #MAIL_LOGGER})
 * logged an error in the last {@link #ERRORS_WINDOW} that nobody has resolved.</li>
 * </ul>
 * Each source is {@link Cached kept} for {@link #MAX_AGE} ({@link #DATABASE_MAX_AGE} for the database), and worked
 * out again on a thread of its own, so a request waits for none but one with no answer yet, at most {@link #WAIT}.
 * Each part is read alone: a source that can't be read puts its codes in {@code unchecked} and gives its tiles
 * {@code problem}, a fixed line, and the rest are whole.
 */
@Component
public class DashboardService {

	private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

	/** How long each source's answer is kept, and its failure */
	static final Duration MAX_AGE = Duration.ofMinutes(1);

	/** How long the database's product, version and size are kept, as its size takes a walk of its files */
	static final Duration DATABASE_MAX_AGE = Duration.ofMinutes(5);

	/** How long each sign-in provider's answer is kept, so it's asked at most this often */
	static final Duration SIGN_IN_MAX_AGE = Duration.ofMinutes(5);

	/** The longest a request waits for sources with no answer yet; then they're still being worked out */
	static final Duration WAIT = Duration.ofSeconds(5);

	/** How far back errors count */
	static final Duration ERRORS_WINDOW = Duration.ofHours(24);

	/** A disk more used than this, in percent of its total, is low */
	public static final int DISK_LOW_PERCENT = 85;

	/** CE's {@code store} with Healdan's storage backends ({@code healdan-storage}'s {@code HealdanStorage}) */
	static final String HEALDAN_STORAGE = "com.logicaldoc.healdan.storage.HealdanStorage";

	/** The logger of CE's mail sender, whose errors are failed sends */
	static final String MAIL_LOGGER = EMailSender.class.getName();

	/** How many of the mail sender's latest faults are read, to find one nobody has resolved */
	private static final int MAIL_ERRORS_READ = 5;

	/** A tile's problem when its source couldn't be read; the log says why */
	static final String UNREADABLE = "Couldn't be read. The server log names the fault.";

	/** A tile's problem when its source is still being worked out */
	static final String STILL_WORKING = "Still being worked out. It shows at the next refresh.";

	/**
	 * The screens the Dashboard links to (spec section 8.1): the Admin app's address once a screen has shipped, and
	 * the Classic Interface until then. Each phase that ships a screen changes its row; Phase 2 shipped Clients, Matters
	 * and Workspace Templates, and Phase 3 Stores and Backends.
	 */
	enum Screen {
		DASHBOARD("/admin/"),
		CLIENTS("/admin/clients"),
		MATTERS("/admin/matters"),
		TEMPLATES("/admin/templates"),
		STORES("/admin/stores"),
		BACKENDS("/admin/backends"),
		USERS(Landing.CLASSIC),
		SIGN_IN_PROVIDERS(Landing.CLASSIC),
		SETTINGS(Landing.CLASSIC),
		ERRORS(Landing.CLASSIC),
		SERVER_LOG(Landing.CLASSIC),
		AUDIT_TRAIL(Landing.CLASSIC),
		BACKGROUND_JOBS(Landing.CLASSIC),
		SEARCH_INDEX(Landing.CLASSIC);

		final String link;

		Screen(String link) {
			this.link = link;
		}
	}

	/**
	 * The Dashboard's first two parts, {@code openapi-admin.yaml}'s {@code Dashboard}.
	 *
	 * @param attention what needs acting on, each code at most once, in {@link AttentionItem#CODES}' order
	 * @param unchecked the codes whose source couldn't be read, in that order
	 * @param health the ten tiles, in {@link HealthTile#NAMES}' order
	 * @param readAt when the oldest answer it gives was read: a source is kept for a while, so what the page shows is
	 *        as of then, not as of the request
	 */
	public record Dashboard(List<AttentionItem> attention, List<String> unchecked, List<HealthTile> health,
			Instant readAt) {

		public Dashboard {
			attention = List.copyOf(attention);
			unchecked = List.copyOf(unchecked);
			health = List.copyOf(health);
		}
	}

	/** Where a folder is, and how much room it has: what {@link #disks} measures */
	@FunctionalInterface
	interface Disks {

		/**
		 * @param folder a folder, which may not exist yet
		 * @return its volume's space
		 * @throws IOException if it can't be measured
		 */
		Space space(Path folder) throws IOException;
	}

	/**
	 * @param volume what tells one volume from another, equal for two folders on one
	 * @param total its bytes
	 * @param usable the bytes the server can still write
	 */
	record Space(Object volume, long total, long usable) {
	}

	/**
	 * A volume the server writes to.
	 *
	 * @param roles what the server keeps on it: {@code repository}, {@code index}, {@code logs}
	 */
	record Volume(List<String> roles, long total, long usable) {

		long used() {
			return Math.max(0, total - usable);
		}

		/** @return whether it's more used than {@link #DISK_LOW_PERCENT} */
		boolean low() {
			return total > 0 && used() * 100 > total * DISK_LOW_PERCENT;
		}

		/** @return how full it is, from 0 to 1 */
		double share() {
			return total > 0 ? (double) used() / total : 0;
		}

		/** @return how full it is, in percent rounded up, so a disk more than 85% full never reads 85% */
		long percent() {
			return total > 0 ? (used() * 100 + total - 1) / total : 0;
		}
	}

	/** The storage port's answer: on, with the overview, or off */
	record Storage(boolean on, StorageOverview overview) {

		static final Storage OFF = new Storage(false, null);
	}

	record Previews(long waiting, long givenUp) {
	}

	/** @param latest when the latest of them was last seen; null if none */
	record Errors(long count, Instant latest) {
	}

	/** @param size its bytes; null for a product with no query for it */
	record Database(String product, String version, Long size) {
	}

	/** An enabled sign-in provider, and whether it answered */
	record Provider(String label, boolean answering) {
	}

	/** @param failedAt when CE's mail sender last logged a fault nobody has resolved; null if none */
	record Mail(boolean setUp, Instant failedAt) {
	}

	/** A provider's answer, with the issuer it was asked at */
	private record Answer(String issuer, boolean answering, Instant at) {
	}

	/** What "now" is; tests fix it */
	volatile Clock clock = Clock.systemUTC();

	/** The storage ports, of which there's one with healdan-storage, else none; tests give their own */
	volatile Supplier<List<StorageAdministration>> ports = StorageAdministration::ports;

	/** How folders are measured; tests give their own */
	volatile Disks disks = DashboardService::measure;

	/** The longest a request waits for sources with no answer it can give, {@link #WAIT}; tests shorten it */
	volatile Duration longestWait = WAIT;

	private final JdbcTemplate jdbc;

	private final ErrorStore errors;

	private final SignInProviders providers;

	private final ProviderClient providerClient;

	/** Each enabled provider's last answer, by its id */
	private final Map<String, Answer> answers = new ConcurrentHashMap<>();

	private final Cached<Storage> storage;

	private final Cached<IndexCounts.Counts> indexCounts;

	private final Cached<Previews> previewCounts;

	private final Cached<Errors> errorCounts;

	private final Cached<List<JobView>> jobViews;

	private final Cached<List<Volume>> volumes;

	private final Cached<Database> database;

	private final Cached<List<Provider>> signIn;

	private final Cached<Mail> mail;

	@Autowired
	public DashboardService(DataSource dataSource, IndexCounts index, PreviewJobs previews, ErrorStore errors,
			Jobs jobs, SignInProviders providers, ProviderClient providerClient) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.errors = errors;
		this.providers = providers;
		this.providerClient = providerClient;
		Supplier<Instant> now = () -> clock.instant();
		storage = new Cached<>("the storage backends", MAX_AGE, this::readStorage, now);
		indexCounts = new Cached<>("the search index's counts", MAX_AGE, index::counts, now);
		previewCounts = new Cached<>("the previews' counts", MAX_AGE,
				() -> new Previews(previews.countWaiting(), previews.countGivenUp()), now);
		errorCounts = new Cached<>("the errors", MAX_AGE, this::readErrors, now);
		jobViews = new Cached<>("the background jobs", MAX_AGE, jobs::all, now);
		volumes = new Cached<>("the disks", MAX_AGE, this::readVolumes, now);
		database = new Cached<>("the database", DATABASE_MAX_AGE, this::readDatabase, now);
		signIn = new Cached<>("the sign-in providers", MAX_AGE, this::readSignIn, now);
		mail = new Cached<>("outgoing email", MAX_AGE, this::readMail, now);
	}

	/**
	 * @return the bean
	 */
	public static DashboardService get() {
		return Context.get(DashboardService.class);
	}

	/**
	 * @return what needs attention, each code at most once, in {@link AttentionItem#CODES}' order
	 */
	public List<AttentionItem> attention() {
		return read().attention();
	}

	/**
	 * @return the ten tiles, in {@link HealthTile#NAMES}' order
	 */
	public List<HealthTile> health() {
		return read().health();
	}

	/**
	 * Reads every source once, from what's kept where it can, and makes the attention list, the codes that couldn't
	 * be checked and the tiles. It never throws for a source.
	 *
	 * @return the Dashboard's first two parts
	 */
	public Dashboard read() {
		// Asked together, so the first read after a while waits for the slowest, not for all one by one
		CompletableFuture<Outcome<Storage>> storageAsked = storage.outcome();
		CompletableFuture<Outcome<IndexCounts.Counts>> indexAsked = indexCounts.outcome();
		CompletableFuture<Outcome<Previews>> previewsAsked = previewCounts.outcome();
		CompletableFuture<Outcome<Errors>> errorsAsked = errorCounts.outcome();
		CompletableFuture<Outcome<List<JobView>>> jobsAsked = jobViews.outcome();
		CompletableFuture<Outcome<List<Volume>>> volumesAsked = volumes.outcome();
		CompletableFuture<Outcome<Database>> databaseAsked = database.outcome();
		CompletableFuture<Outcome<List<Provider>>> signInAsked = signIn.outcome();
		CompletableFuture<Outcome<Mail>> mailAsked = mail.outcome();

		long deadline = System.nanoTime() + longestWait.toNanos();
		Outcome<Storage> storageNow = await(storageAsked, deadline);
		Outcome<IndexCounts.Counts> indexNow = await(indexAsked, deadline);
		Outcome<Previews> previewsNow = await(previewsAsked, deadline);
		Outcome<Errors> errorsNow = await(errorsAsked, deadline);
		Outcome<List<JobView>> jobsNow = await(jobsAsked, deadline);
		Outcome<List<Volume>> volumesNow = await(volumesAsked, deadline);
		Outcome<Database> databaseNow = await(databaseAsked, deadline);
		Outcome<List<Provider>> signInNow = await(signInAsked, deadline);
		Outcome<Mail> mailNow = await(mailAsked, deadline);
		Instant now = clock.instant();

		List<AttentionItem> attention = new ArrayList<>();
		List<String> unchecked = new ArrayList<>();
		attend(AttentionItem.FILES_STUCK, Screen.STORES, storageNow, DashboardService::filesStuck, attention,
				unchecked);
		attend(AttentionItem.BACKEND_UNREACHABLE, Screen.BACKENDS, storageNow, DashboardService::backendsDown, attention,
				unchecked);
		attend(AttentionItem.INDEX_FAILED, Screen.SEARCH_INDEX, indexNow,
				counts -> found(counts.failed(), "The Indexer tries them again on its schedule"), attention,
				unchecked);
		attend(AttentionItem.ERRORS, Screen.ERRORS, errorsNow, e -> found(e.count(),
				e.latest() != null ? "The latest seen " + Words.ago(e.latest(), now) : "In the last 24 hours"),
				attention, unchecked);
		attend(AttentionItem.PREVIEWS_GIVEN_UP, Screen.BACKGROUND_JOBS, previewsNow,
				p -> found(p.givenUp(), "Each is tried again when its file is replaced, or the timeout raised"),
				attention, unchecked);
		attend(AttentionItem.JOB_FAILED, Screen.BACKGROUND_JOBS, jobsNow, all -> named(failed(all)), attention,
				unchecked);
		attend(AttentionItem.JOB_OVERDUE, Screen.BACKGROUND_JOBS, jobsNow, all -> named(overdue(all, now)),
				attention, unchecked);
		attend(AttentionItem.DISK_LOW, Screen.BACKENDS, volumesNow, DashboardService::disksLow, attention, unchecked);

		List<HealthTile> health = new ArrayList<>();
		health.add(tile(HealthTile.SERVER, Screen.SERVER_LOG, null, DashboardService::serverTile));
		health.add(tile(HealthTile.DATABASE, Screen.SERVER_LOG, databaseNow,
				() -> databaseTile(databaseNow.value())));
		health.add(tile(HealthTile.DISK, Screen.BACKENDS, volumesNow, () -> diskTile(volumesNow.value())));
		health.add(tile(HealthTile.MEMORY, Screen.SERVER_LOG, null, DashboardService::memoryTile));
		health.add(tile(HealthTile.SIGN_IN, Screen.SIGN_IN_PROVIDERS, signInNow,
				() -> signInTile(signInNow.value())));
		health.add(tile(HealthTile.STORAGE_BACKENDS, Screen.BACKENDS, storageNow,
				() -> storageTile(storageNow.value())));
		health.add(tile(HealthTile.SEARCH_INDEX, Screen.SEARCH_INDEX, indexNow, () -> indexTile(indexNow.value())));
		health.add(tile(HealthTile.PREVIEWS, Screen.BACKGROUND_JOBS, previewsNow,
				() -> previewsTile(previewsNow.value())));
		health.add(tile(HealthTile.BACKGROUND_JOBS, Screen.BACKGROUND_JOBS, jobsNow,
				() -> jobsTile(jobsNow.value(), now)));
		health.add(tile(HealthTile.OUTGOING_EMAIL, Screen.SETTINGS, mailNow, () -> mailTile(mailNow.value(), now)));
		return new Dashboard(attention, unchecked, health, oldest(now, storageNow, indexNow, previewsNow, errorsNow,
				jobsNow, volumesNow, databaseNow, signInNow, mailNow));
	}

	/**
	 * @return when the oldest of the answers was read, a failure's left out as its part shows none; now if none was,
	 *         as the Server and Memory tiles are read at each request
	 */
	private static Instant oldest(Instant now, Outcome<?>... outcomes) {
		Instant oldest = now;
		for (Outcome<?> outcome : outcomes)
			if (outcome.ok() && outcome.at().isBefore(oldest))
				oldest = outcome.at();
		return oldest;
	}

	/**
	 * Forgets every source's answer, so the next read asks each afresh: what tests call after changing what they
	 * read. The sign-in providers' answers stay, as they're kept for each provider.
	 */
	void forget() {
		for (Cached<?> source : List.of(storage, indexCounts, previewCounts, errorCounts, jobViews, volumes, database,
				signIn, mail))
			source.forget();
	}

	/** Waits for a source until the deadline; past it, the source is still being worked out */
	private <T> Outcome<T> await(CompletableFuture<Outcome<T>> asked, long deadline) {
		try {
			return asked.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
		} catch (TimeoutException e) {
			return new Outcome<>(null, new StillWorking(), clock.instant());
		} catch (ExecutionException e) {
			return new Outcome<>(null, e.getCause(), clock.instant());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Outcome<>(null, e, clock.instant());
		}
	}

	/** A source a request didn't wait any longer for */
	private static final class StillWorking extends Exception {

		private static final long serialVersionUID = 1L;

		StillWorking() {
			super(null, null, false, false);
		}
	}

	/** How many, and the line that says more, of something that needs attention */
	private record Found(long count, String detail) {
	}

	/** @return what was found, null if there's nothing */
	private static Found found(long count, String detail) {
		return count > 0 ? new Found(count, detail) : null;
	}

	/**
	 * Adds a code's item if its check finds something, or the code to the unchecked if its source couldn't be read or
	 * the check fails
	 */
	private <T> void attend(String code, Screen screen, Outcome<T> source, Function<T, Found> check,
			List<AttentionItem> attention, List<String> unchecked) {
		if (!source.ok()) {
			unchecked.add(code);
			return;
		}
		try {
			Found found = check.apply(source.value());
			if (found != null)
				attention.add(new AttentionItem(code, (int) Math.min(Integer.MAX_VALUE, found.count()),
						found.detail(), screen.link));
		} catch (RuntimeException e) {
			log.warn("The Dashboard couldn't check {}: {}", code, e.getClass().getName());
			log.debug("The Dashboard couldn't check {}", code, e);
			unchecked.add(code);
		}
	}

	/**
	 * @return the tile as made, or with {@code problem} if its source couldn't be read or making it fails; a tile with
	 *         no source has none
	 */
	private static HealthTile tile(String name, Screen screen, Outcome<?> source, Callable<HealthTile> make) {
		if (source != null && !source.ok())
			return HealthTile.unreadable(name, screen.link,
					source.failure() instanceof StillWorking ? STILL_WORKING : UNREADABLE);
		try {
			HealthTile tile = make.call();
			return new HealthTile(name, tile.status(), tile.detail(), tile.used(), tile.total(), screen.link, null);
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Exception | LinkageError e) {
			log.warn("The Dashboard couldn't make its {} tile: {}", name, e.getClass().getName());
			log.debug("The Dashboard couldn't make its {} tile", name, e);
			return HealthTile.unreadable(name, screen.link, UNREADABLE);
		}
	}

	/** @return a tile with no bytes; its name and link are filled in by {@link #tile} */
	private static HealthTile line(String status, String detail) {
		return new HealthTile(null, status, detail, null, null, null, null);
	}

	// The sources

	private Storage readStorage() throws Exception {
		List<StorageAdministration> found = ports.get();
		if (found.isEmpty()) {
			// No port: unchecked only if storage backends should be on
			if (HEALDAN_STORAGE.equals(StringUtils.strip(Context.get().getConfig().getProperty("store"))))
				throw new IllegalStateException("CE keeps its files through Healdan's storage, with no storage port");
			return Storage.OFF;
		}
		StorageOverview overview = found.get(0).overview();
		return overview.on() ? new Storage(true, overview) : Storage.OFF;
	}

	private Errors readErrors() {
		ErrorStore.Unresolved unresolved = errors.unresolved(clock.instant().minus(ERRORS_WINDOW));
		return new Errors(unresolved.count(), unresolved.latest());
	}

	/** @return the repository's, the index's and the logs' volumes, each once, in that order */
	private List<Volume> readVolumes() throws IOException {
		Map<String, String> folders = new LinkedHashMap<>();
		folders.put("repository", "store.1.dir");
		folders.put("index", "index.dir");
		folders.put("logs", "conf.logdir");
		Map<Object, List<String>> roles = new LinkedHashMap<>();
		Map<Object, Space> spaces = new LinkedHashMap<>();
		for (Map.Entry<String, String> folder : folders.entrySet()) {
			String path = StringUtils.trimToNull(Context.get().getConfig().getProperty(folder.getValue()));
			if (path == null)
				continue;
			Space space = disks.space(Path.of(path));
			roles.computeIfAbsent(space.volume(), v -> new ArrayList<>()).add(folder.getKey());
			spaces.putIfAbsent(space.volume(), space);
		}
		if (spaces.isEmpty())
			throw new IllegalStateException("No folder of the server's is set");
		List<Volume> found = new ArrayList<>();
		for (Map.Entry<Object, Space> space : spaces.entrySet())
			found.add(new Volume(List.copyOf(roles.get(space.getKey())), space.getValue().total(),
					space.getValue().usable()));
		return found;
	}

	/** @return the folder's volume, from its nearest folder that exists */
	static Space measure(Path folder) throws IOException {
		Path at = folder.toAbsolutePath().normalize();
		while (at != null && !Files.exists(at))
			at = at.getParent();
		if (at == null)
			throw new NoSuchFileException(folder.toString());
		FileStore store = Files.getFileStore(at);
		return new Space(store, store.getTotalSpace(), store.getUsableSpace());
	}

	private Database readDatabase() {
		return jdbc.execute((ConnectionCallback<Database>) connection -> {
			DatabaseMetaData meta = connection.getMetaData();
			String product = meta.getDatabaseProductName();
			String version = meta.getDatabaseMajorVersion() + "." + meta.getDatabaseMinorVersion();
			return new Database(product, version, size(connection, product));
		});
	}

	/**
	 * @return the database's size by its own query (Admin app spec section 9.9): PostgreSQL's current database, and
	 *         MariaDB's tables in the schema the connection names, never through {@code database()}; null for another
	 *         product
	 */
	private static Long size(Connection connection, String product) throws SQLException {
		String name = product == null ? "" : product.toLowerCase(Locale.ROOT);
		if (name.contains("postgresql"))
			return single(connection, "select pg_database_size(current_database())", null);
		if (name.contains("mariadb") || name.contains("mysql")) {
			String schema = connection.getCatalog();
			return schema == null ? null
					: single(connection, "select coalesce(sum(data_length + index_length), 0)"
							+ " from information_schema.tables where table_schema = ?", schema);
		}
		return null;
	}

	/** @return the first column of the query's one row, with its one parameter if it has one; null with no row */
	private static Long single(Connection connection, String sql, String parameter) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			if (parameter != null)
				statement.setString(1, parameter);
			try (ResultSet rs = statement.executeQuery()) {
				return rs.next() ? rs.getLong(1) : null;
			}
		}
	}

	/**
	 * @return each enabled provider, in the order of their buttons, and whether it answered: asked again, all at
	 *         once, once its last answer is {@link #SIGN_IN_MAX_AGE} old, or was given at another issuer
	 */
	private List<Provider> readSignIn() throws InterruptedException {
		List<SignInProvider> enabled = providers.enabled();
		Map<String, Future<Boolean>> asked = new LinkedHashMap<>();
		try (ExecutorService asking = Executors
				.newThreadPerTaskExecutor(Thread.ofVirtual().name(Cached.THREAD).factory())) {
			for (SignInProvider p : enabled)
				if (!fresh(answers.get(p.id()), p))
					asked.put(p.id(), asking.submit(() -> providerClient.answers(p)));
		}
		Set<String> ids = new HashSet<>();
		List<Provider> found = new ArrayList<>();
		for (SignInProvider p : enabled) {
			ids.add(p.id());
			Future<Boolean> pending = asked.get(p.id());
			Answer answer = answers.get(p.id());
			if (pending != null || answer == null) {
				boolean answering;
				try {
					answering = pending != null && pending.get();
				} catch (ExecutionException e) {
					answering = false;
				}
				answer = new Answer(p.issuer(), answering, clock.instant());
				answers.put(p.id(), answer);
			}
			found.add(new Provider(StringUtils.defaultIfBlank(p.label(), p.id()), answer.answering()));
		}
		// A provider no longer on is forgotten
		answers.keySet().retainAll(ids);
		return found;
	}

	/** @return whether the provider's last answer was given at its issuer less than {@link #SIGN_IN_MAX_AGE} ago */
	private boolean fresh(Answer last, SignInProvider p) {
		if (last == null || !Objects.equals(last.issuer(), p.issuer()))
			return false;
		Duration age = Duration.between(last.at(), clock.instant());
		return !age.isNegative() && age.compareTo(SIGN_IN_MAX_AGE) < 0;
	}

	private Mail readMail() {
		if (!OutgoingEmail.isSetUp())
			return new Mail(false, null);
		Instant failedAt = null;
		for (ErrorGroup fault : errors.latest(Set.of(MAIL_LOGGER), clock.instant().minus(ERRORS_WINDOW),
				MAIL_ERRORS_READ))
			if (!fault.resolved()) {
				failedAt = fault.lastSeen();
				break;
			}
		return new Mail(true, failedAt);
	}

	// Needs Attention

	private static Found filesStuck(Storage s) {
		if (!s.on())
			return null;
		StorageOverview overview = s.overview();
		List<StoreBackendInfo> stuck = overview.stores().stream().filter(store -> store.stuck() > 0)
				.sorted(Comparator.comparingLong(StoreBackendInfo::stuck).reversed()
						.thenComparing(StoreBackendInfo::storeName, String.CASE_INSENSITIVE_ORDER))
				.toList();
		long count = overview.otherStuck() + stuck.stream().mapToLong(StoreBackendInfo::stuck).sum();
		if (count == 0)
			return null;
		if (stuck.size() == 1 && overview.otherStuck() == 0) {
			StoreBackendInfo store = stuck.get(0);
			return new Found(count,
					store.storeName() + (store.backendName() != null ? ", moving to " + store.backendName() : ""));
		}
		if (stuck.isEmpty())
			return new Found(count, "Files with no Store");
		List<String> places = new ArrayList<>();
		for (StoreBackendInfo store : stuck)
			places.add(store.storeName() + " (" + Words.number(store.stuck()) + ")");
		if (overview.otherStuck() > 0)
			places.add("no Store (" + Words.number(overview.otherStuck()) + ")");
		return new Found(count, Words.list(places));
	}

	private static Found backendsDown(Storage s) {
		if (!s.on())
			return null;
		List<String> down = new ArrayList<>();
		for (BackendInfo backend : s.overview().backends())
			if (BackendInfo.UNREACHABLE.equals(backend.status()))
				down.add(backend.name() + " can't be reached");
			else if (BackendInfo.FAILING.equals(backend.status()))
				down.add(backend.name() + " is failing");
		return found(down.size(), Words.list(down));
	}

	/** @return the jobs switched on whose last run failed */
	private static List<JobView> failed(List<JobView> all) {
		return all.stream().filter(DashboardService::isFailed).toList();
	}

	/**
	 * @return the jobs overdue now, a job switched off never, and one that failed neither: it is counted once, as
	 *         failed
	 */
	private static List<JobView> overdue(List<JobView> all, Instant now) {
		return all.stream().filter(job -> job.overdue(now) && !isFailed(job)).toList();
	}

	private static boolean isFailed(JobView job) {
		return job.enabled() && JobView.FAILED.equals(job.result());
	}

	/** @return how many jobs, and their names */
	private static Found named(List<JobView> jobs) {
		return found(jobs.size(), Words.list(jobs.stream().map(JobView::label).toList()));
	}

	private static Found disksLow(List<Volume> all) {
		List<String> low = new ArrayList<>();
		for (Volume volume : all)
			if (volume.low())
				low.add(roles(volume) + " " + volume.percent() + "% full");
		return found(low.size(), Words.capitalised(String.join("; ", low)));
	}

	/** @return what the server keeps on the volume, as {@code repository and logs} */
	private static String roles(Volume volume) {
		return Words.list(volume.roles());
	}

	// Server Health

	private static HealthTile serverTile() {
		String release = StringUtils.trimToNull(Context.get().getConfig().getProperty("product.release"));
		Duration up = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
		return line(HealthTile.OK, "Up " + Words.duration(up) + ", Healdan" + (release != null ? " " + release : ""));
	}

	private static HealthTile databaseTile(Database d) {
		String detail = d.product() + " " + d.version() + (d.size() != null ? ", " + Words.size(d.size()) : "");
		return new HealthTile(null, HealthTile.OK, detail, d.size(), null, null, null);
	}

	/** The fullest volume's bytes; the line names the others */
	private static HealthTile diskTile(List<Volume> all) {
		List<Volume> fullest = all.stream().sorted(Comparator.comparingDouble(Volume::share).reversed()).toList();
		Volume first = fullest.get(0);
		String detail;
		if (fullest.size() == 1)
			detail = Words.usedOf(first.used(), first.total());
		else {
			List<String> parts = new ArrayList<>();
			for (Volume volume : fullest)
				parts.add(roles(volume) + ": " + Words.usedOf(volume.used(), volume.total()));
			detail = Words.capitalised(String.join("; ", parts));
		}
		return new HealthTile(null, first.low() ? HealthTile.ATTENTION : HealthTile.OK, detail, first.used(),
				first.total(), null, null);
	}

	private static HealthTile memoryTile() {
		Runtime runtime = Runtime.getRuntime();
		long max = runtime.maxMemory() == Long.MAX_VALUE ? runtime.totalMemory() : runtime.maxMemory();
		long used = Math.max(0, runtime.totalMemory() - runtime.freeMemory());
		return new HealthTile(null, HealthTile.OK, Words.usedOf(used, max), used, max, null, null);
	}

	private static HealthTile signInTile(List<Provider> all) {
		if (all.isEmpty())
			return line(HealthTile.OFF, "No sign-in provider is on");
		List<String> answering = all.stream().filter(Provider::answering).map(Provider::label).toList();
		List<String> silent = all.stream().filter(p -> !p.answering()).map(Provider::label).toList();
		if (silent.isEmpty())
			return line(HealthTile.OK, Words.list(answering) + " answering");
		String detail = Words.list(silent) + (silent.size() == 1 ? " isn't answering" : " aren't answering");
		if (!answering.isEmpty())
			detail += ", " + Words.list(answering) + " answering";
		return line(HealthTile.ATTENTION, detail);
	}

	private static HealthTile storageTile(Storage s) {
		if (!s.on())
			return line(HealthTile.OFF, "Not in use on this server");
		StorageOverview overview = s.overview();
		long reachable = 0;
		long unchecked = 0;
		long down = 0;
		for (BackendInfo backend : overview.backends())
			if (BackendInfo.OK.equals(backend.status()))
				reachable++;
			else if (BackendInfo.UNCHECKED.equals(backend.status()))
				unchecked++;
			else
				down++;
		long stuck = overview.otherStuck() + overview.stores().stream().mapToLong(StoreBackendInfo::stuck).sum();
		StringBuilder detail = new StringBuilder(
				Words.number(reachable) + " of " + Words.number(overview.backends().size()) + " reachable");
		if (unchecked > 0)
			detail.append(", ").append(Words.number(unchecked)).append(" not checked yet");
		detail.append(", ").append(stuck == 0 ? "no files stuck" : Words.count(stuck, "file", "files") + " stuck");
		return line(down > 0 || stuck > 0 ? HealthTile.ATTENTION : HealthTile.OK, detail.toString());
	}

	private static HealthTile indexTile(IndexCounts.Counts counts) {
		return line(counts.failed() > 0 ? HealthTile.ATTENTION : HealthTile.OK,
				some(counts.waiting(), "waiting", true) + ", " + some(counts.failed(), "failed", false));
	}

	private static HealthTile previewsTile(Previews p) {
		return line(p.givenUp() > 0 ? HealthTile.ATTENTION : HealthTile.OK,
				some(p.waiting(), "waiting", true) + ", " + some(p.givenUp(), "given up", false));
	}

	/** @return {@code 5 waiting}, or {@code None waiting} ({@code none} when it doesn't start the line) */
	private static String some(long n, String what, boolean first) {
		return (n == 0 ? (first ? "None" : "none") : Words.number(n)) + " " + what;
	}

	private static HealthTile jobsTile(List<JobView> all, Instant now) {
		List<JobView> failed = failed(all);
		List<JobView> overdue = overdue(all, now);
		long on = all.stream().filter(JobView::enabled).count();
		long off = all.size() - on;
		long onSchedule = on - failed.size() - overdue.size();
		List<String> parts = new ArrayList<>();
		if (!failed.isEmpty())
			parts.add(Words.list(failed.stream().map(JobView::label).toList()) + " failed");
		if (!overdue.isEmpty())
			parts.add(Words.list(overdue.stream().map(JobView::label).toList()) + " overdue");
		if (onSchedule > 0 || parts.isEmpty())
			parts.add(Words.number(onSchedule) + " on schedule");
		if (off > 0)
			parts.add(Words.number(off) + " switched off");
		return line(failed.isEmpty() && overdue.isEmpty() ? HealthTile.OK : HealthTile.ATTENTION,
				String.join(", ", parts));
	}

	private static HealthTile mailTile(Mail m, Instant now) {
		if (!m.setUp())
			return line(HealthTile.OFF, "Not set up");
		if (m.failedAt() != null)
			return line(HealthTile.ATTENTION, "A send failed " + Words.ago(m.failedAt(), now));
		return line(HealthTile.OK, "Set up, no failed sends in the last 24 hours");
	}
}
