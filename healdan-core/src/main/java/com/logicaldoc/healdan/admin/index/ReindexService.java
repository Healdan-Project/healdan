// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.admin.Page;
import com.logicaldoc.healdan.admin.index.ReindexJob.State;
import com.logicaldoc.healdan.admin.index.ReindexPreview.Match;
import com.logicaldoc.healdan.admin.index.ReindexPreview.Status;
import com.logicaldoc.healdan.admin.index.ReindexScope.Kind;
import com.logicaldoc.healdan.admin.stores.StoreService;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.util.spring.Context;

/**
 * Reindexing (Admin app spec sections 8.9 and 9.6, rulings P6-R2 to P6-R9 and P6-R16): what a scope would cover,
 * starting a job, and the jobs, which {@link ReindexWorker} runs one at a time.
 * <p>
 * A scope's documents are its live ones: in its folders' trees, walked by parent ({@link StoredRows#tree}), its
 * documents by doc #, or every one of the tenant's. Of those, a job tries what {@link ReindexJobs#ELIGIBLE} keeps, and
 * leaves out, counting them, shortcuts and the documents set to Skip, archived or in a running transaction. Its
 * documents are fixed when it's started, so a restart resumes it over the same set, whatever happens to its folders.
 * <p>
 * Every method refuses anyone but an administrator first ({@link #ONLY_ADMINS}). A preview and a start then refuse a
 * scope that lacks the member its kind needs, or names more than {@value ReindexScope#MAX_DOC_NUMBERS} different doc
 * #s ({@link FieldRefusal}); answer null for a scope whose target isn't live, as a route answers {@code 404}; and
 * refuse {@code emptyFirst} off {@link Kind#EVERYTHING} ({@link FieldRefusal}). A start is then refused when it would
 * try nothing, and while a job of the same kind and target hasn't ended (but documents), which a preview says as its
 * {@link ReindexPreview#refusal()}. A start is recorded in its transaction ({@value #STARTED}), and a cancel that
 * changed something after it ({@value #CANCELLED}), area {@code SEARCH_INDEX}.
 */
@Component
public class ReindexService {

	/** The refusal of anyone but an administrator */
	public static final String ONLY_ADMINS = "Only an administrator can reindex documents or see the reindex jobs.";

	/** The refusal of a start that would try nothing */
	public static final String NOTHING_TO_REINDEX = "There's nothing here to reindex.";

	/** The refusal of a start whose kind and target equal a job's that hasn't ended */
	public static final String ALREADY_REINDEXING = "This is already being reindexed.";

	/** The refusal of a cancel of a job that has ended */
	public static final String ALREADY_ENDED = "This reindex has ended.";

	/** The field code of more different doc #s than a scope takes */
	public static final String TOO_MANY = "too-many";

	/** The sentence of more different doc #s than a scope takes */
	public static final String TOO_MANY_DOCUMENTS = "Reindex at most 1,000 documents at a time.";

	/** The sentence of a folder, matter or Store scope with no target */
	public static final String TARGET_REQUIRED = "Choose what to reindex.";

	/** The sentence of a documents scope with no doc #s */
	public static final String DOC_NUMBERS_REQUIRED = "Give the doc #s to reindex.";

	/** The refusal of {@code emptyFirst} off Everything */
	public static final String EMPTY_FIRST_EVERYTHING = "Only a reindex of everything can empty the index first.";

	/** The action a start records */
	public static final String STARTED = "reindex.started";

	/** The action a cancel records */
	public static final String CANCELLED = "reindex.cancelled";

	/** The seconds a document is reckoned to take when no job done tried enough to go by: 412 in 12 minutes */
	static final double SECONDS_PER_DOCUMENT = 1.75;

	/** The fewest documents a job done must have tried for its rate to be gone by */
	static final int RATE_FROM = 20;

	/** Holds starts one at a time, so two of one target can't both pass the check for an active job */
	private static final Object STARTS = new Object();

	private final ReindexJobs jobs;

	private final ReindexWorker worker;

	private final AdminEvents events;

	private final TransactionTemplate transaction;

	private final JdbcTemplate jdbc;

	@Autowired
	public ReindexService(ReindexJobs jobs, ReindexWorker worker, AdminEvents events,
			PlatformTransactionManager transactions, DataSource dataSource) {
		this.jobs = jobs;
		this.worker = worker;
		this.events = events;
		this.transaction = new TransactionTemplate(transactions);
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static ReindexService get() {
		return Context.get(ReindexService.class);
	}

	/**
	 * What a job of the scope would cover, if it were started now.
	 *
	 * @param scope what to reindex
	 * @param actor who asks
	 * @return the preview; null if the scope's target isn't live
	 * @throws PersistenceException {@link #ONLY_ADMINS} first; a {@link FieldRefusal} for a member missing or too many
	 *         doc #s, and, once the target is found, for {@code emptyFirst} off Everything
	 */
	public ReindexPreview preview(ReindexScope scope, User actor) throws PersistenceException {
		requireAdmin(actor);
		checkFields(scope);
		long tenantId = actor.getTenantId();
		Resolved resolved = resolve(scope, tenantId);
		if (resolved == null)
			return null;
		checkEmptyFirst(scope);
		Counts counts = count(resolved, tenantId);
		return new ReindexPreview(resolved.name(), counts.eligible(), counts.all() - counts.eligible(),
				estimateSeconds(counts.eligible()), resolved.matches(), refusal(scope, tenantId, counts.eligible()));
	}

	/**
	 * Starts a job of the scope, which waits its turn, and records it in the same transaction. Its documents are
	 * those it would try now, as {@link #preview} counts them.
	 *
	 * @param scope what to reindex
	 * @param actor who starts it
	 * @return the job, waiting; null if the scope's target isn't live
	 * @throws PersistenceException as {@link #preview} does; {@link #NOTHING_TO_REINDEX} when it would try nothing;
	 *         {@link #ALREADY_REINDEXING} while a job of the same kind and target hasn't ended, but for documents
	 */
	public ReindexJob start(ReindexScope scope, User actor) throws PersistenceException {
		requireAdmin(actor);
		checkFields(scope);
		long tenantId = actor.getTenantId();
		Resolved resolved = resolve(scope, tenantId);
		if (resolved == null)
			return null;
		checkEmptyFirst(scope);
		ReindexJob job;
		synchronized (STARTS) {
			Counts counts = count(resolved, tenantId);
			String refusal = refusal(scope, tenantId, counts.eligible());
			if (refusal != null)
				throw new HealdanRuleException(refusal);
			job = transaction.execute(status -> {
				long id = jobs.insert(tenantId, scope.kind(), scope.targetId(), resolved.name(), actor,
						scope.emptyFirst(), ReindexJobs.now());
				int total = jobs.addDocuments(id, resolved.from(true), resolved.params(tenantId, true));
				if (total == 0) {
					status.setRollbackOnly();
					return null;
				}
				jobs.counted(id, total, resolved.from(false), resolved.params(tenantId, false));
				events.record(new AdminEvent(AdminArea.SEARCH_INDEX, STARTED, resolved.targetKind(),
						resolved.recordTargetId(), resolved.name(),
						Map.of("kind", new Change(null, scope.kind().name().toLowerCase(Locale.ROOT)), "documents",
								new Change(null, Long.toString(total)), "emptyFirst",
								new Change(null, Boolean.toString(scope.emptyFirst())))),
						actor);
				return jobs.find(id, tenantId);
			});
		}
		if (job == null)
			throw new HealdanRuleException(NOTHING_TO_REINDEX);
		worker.wake();
		return withEstimate(job);
	}

	/**
	 * Cancels a job: one waiting at once; a running one is asked to stop, and stops at its next batch, as a batch on
	 * its way finishes and its counts are saved. The documents it hadn't reached keep their entries and their state.
	 * A running job already asked to stop is answered as it is, and nothing is recorded.
	 *
	 * @param jobId the job
	 * @param actor who cancels it
	 * @return the job as it is then; null if the tenant has no such job
	 * @throws PersistenceException {@link #ONLY_ADMINS} first; {@link #ALREADY_ENDED} for a job that has ended
	 */
	public ReindexJob cancel(long jobId, User actor) throws PersistenceException {
		requireAdmin(actor);
		long tenantId = actor.getTenantId();
		// A waiting job may begin meanwhile, so the cancel tries again from the state it's in then
		for (int tries = 0; tries < 3; tries++) {
			ReindexJob job = jobs.find(jobId, tenantId);
			if (job == null)
				return null;
			if (!job.active())
				throw new HealdanRuleException(ALREADY_ENDED);
			if (job.cancelling())
				return withEstimate(job);
			boolean changed = job.state() == State.WAITING
					? Boolean.TRUE.equals(transaction.execute(
							status -> jobs.end(jobId, State.WAITING, State.CANCELLED, ReindexJobs.now())))
					: jobs.askToCancel(jobId);
			if (changed) {
				events.recordAfter(new AdminEvent(AdminArea.SEARCH_INDEX, CANCELLED, targetKind(job.kind()),
						job.targetId(), job.name(), null), actor);
				if (job.state() == State.WAITING)
					IndexCounts.get().moved();
				worker.wake();
				return withEstimate(jobs.find(jobId, tenantId));
			}
		}
		throw new HealdanRuleException(ALREADY_ENDED);
	}

	/**
	 * @param jobId a job
	 * @param actor who asks
	 * @return the tenant's job; null if there's none
	 * @throws PersistenceException {@link #ONLY_ADMINS} first
	 */
	public ReindexJob job(long jobId, User actor) throws PersistenceException {
		requireAdmin(actor);
		ReindexJob job = jobs.find(jobId, actor.getTenantId());
		return job != null ? withEstimate(job) : null;
	}

	/**
	 * @param offset how many to skip
	 * @param limit the most to give
	 * @param activeOnly true for only the jobs that haven't ended, waiting or running
	 * @param actor who asks
	 * @return a page of the tenant's jobs, newest first
	 * @throws PersistenceException {@link #ONLY_ADMINS} first
	 */
	public Page<ReindexJob> jobs(int offset, int limit, boolean activeOnly, User actor) throws PersistenceException {
		requireAdmin(actor);
		long tenantId = actor.getTenantId();
		int from = Math.max(0, offset);
		List<ReindexJob> page = limit > 0 ? jobs.page(tenantId, activeOnly, from, limit) : List.of();
		Double rate = jobs.secondsPerDocument(RATE_FROM);
		return new Page<>(page.stream().map(job -> withEstimate(job, rate)).toList(), from, limit,
				jobs.count(tenantId, activeOnly));
	}

	/**
	 * @param count how many documents
	 * @return about how many seconds a job of that many would take, at the rate of the most recent job done that
	 *         tried {@value #RATE_FROM} documents at least, else {@value #SECONDS_PER_DOCUMENT} seconds a document;
	 *         null for none
	 */
	Long estimateSeconds(long count) {
		return estimate(count, jobs.secondsPerDocument(RATE_FROM));
	}

	/** @return {@link #estimateSeconds} at a rate, null for the default rate */
	private static Long estimate(long count, Double rate) {
		if (count <= 0)
			return null;
		return Math.max(1, (long) Math.ceil(count * (rate != null ? rate : SECONDS_PER_DOCUMENT)));
	}

	private ReindexJob withEstimate(ReindexJob job) {
		return job.active() ? withEstimate(job, jobs.secondsPerDocument(RATE_FROM)) : job;
	}

	/**
	 * @param rate {@link #estimateSeconds}' rate
	 * @return the job with about how many seconds it has left: for one waiting, its total as a preview estimates it;
	 *         for one running, what it has left at the rate of its own run once it has tried {@value #RATE_FROM}, as
	 *         a preview estimates it before then; none once it has ended, while it's stalled, or when nothing is left
	 */
	static ReindexJob withEstimate(ReindexJob job, Double rate) {
		long left = job.left();
		if (!job.active() || job.stalledSince() != null || left == 0)
			return job.withEstimate(null);
		long tried = job.done() + job.failed();
		if (job.state() == State.RUNNING && job.started() != null && tried >= RATE_FROM) {
			double seconds = Math.max(0, Duration.between(job.started(), Instant.now()).toMillis())
					/ 1000.0;
			return job.withEstimate(estimate(left, seconds / tried));
		}
		return job.withEstimate(estimate(left, rate));
	}

	/**
	 * @return the sentence a start of the scope would be refused with: {@link #NOTHING_TO_REINDEX} for a count of 0,
	 *         {@link #ALREADY_REINDEXING} while a job of its kind and target hasn't ended, but for documents; null for
	 *         none
	 */
	private String refusal(ReindexScope scope, long tenantId, long count) {
		if (count == 0)
			return NOTHING_TO_REINDEX;
		if (scope.kind() != Kind.DOCUMENTS && jobs.activeWith(tenantId, scope.kind(), scope.targetId()))
			return ALREADY_REINDEXING;
		return null;
	}

	/**
	 * @throws FieldRefusal for a folder's, matter's or Store's scope with no target ({@code targetId}), a documents
	 *         scope with no doc #s ({@code docNumbers}), or more different ones than it takes
	 */
	private static void checkFields(ReindexScope scope) throws FieldRefusal {
		if (scope.kind() == Kind.DOCUMENTS) {
			if (scope.docNumbers() == null || scope.docNumbers().isEmpty())
				throw new FieldRefusal(List.of(
						new FieldRefusal.Field("docNumbers", FieldRefusal.REQUIRED, DOC_NUMBERS_REQUIRED)));
			if (scope.distinctDocNumbers().size() > ReindexScope.MAX_DOC_NUMBERS)
				throw new FieldRefusal(List.of(new FieldRefusal.Field("docNumbers", TOO_MANY, TOO_MANY_DOCUMENTS)));
		} else if (scope.kind() != Kind.EVERYTHING && scope.targetId() == null) {
			throw new FieldRefusal(List.of(new FieldRefusal.Field("targetId", FieldRefusal.REQUIRED, TARGET_REQUIRED)));
		}
	}

	private static void requireAdmin(User actor) throws PersistenceException {
		if (actor == null || !FilingRules.get().isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
	}

	private static void checkEmptyFirst(ReindexScope scope) throws FieldRefusal {
		if (scope.emptyFirst() && scope.kind() != Kind.EVERYTHING)
			throw new FieldRefusal(List.of(new FieldRefusal.Field("emptyFirst", FieldRefusal.INVALID,
					EMPTY_FIRST_EVERYTHING)));
	}

	/** @return what the administration record aims a job of the kind at */
	private static String targetKind(Kind kind) {
		return kind.name().toLowerCase(Locale.ROOT);
	}

	/** How many live documents a scope holds, and how many of those a job would try */
	private record Counts(long all, long eligible) {
	}

	private Counts count(Resolved resolved, long tenantId) {
		Object[] eligible = ReindexJobs.eligibleParams();
		Object[] scope = resolved.params(tenantId, false);
		Object[] params = new Object[eligible.length + scope.length];
		System.arraycopy(eligible, 0, params, 0, eligible.length);
		System.arraycopy(scope, 0, params, eligible.length, scope.length);
		return jdbc.query("select count(*), coalesce(sum(case when " + ReindexJobs.ELIGIBLE
				+ " then 1 else 0 end), 0) " + resolved.from(false), ReindexJobs.bind(params), rs -> {
					rs.next();
					return new Counts(rs.getLong(1), rs.getLong(2));
				});
	}

	/**
	 * A scope, its target found
	 *
	 * @param name the job's name
	 * @param targetKind what the administration record aims at
	 * @param recordTargetId the id it aims at, null for none
	 * @param condition what keeps document {@code d} of the live ones, from {@code " and "}; empty for every one
	 * @param ids its one parameter, null for none
	 * @param matches each doc # given, for a documents scope; null for the others
	 */
	private record Resolved(String name, String targetKind, Long recordTargetId, String condition, Long[] ids,
			List<Match> matches) {

		/**
		 * @param eligible true for those of them a job tries
		 * @return the {@code from} and {@code where} of the scope's live documents
		 */
		String from(boolean eligible) {
			return "from ld_document d where d.ld_deleted = 0 and d.ld_tenantid = ?" + condition
					+ (eligible ? " and " + ReindexJobs.ELIGIBLE : "");
		}

		/** @return {@link #from}'s parameters */
		Object[] params(long tenantId, boolean eligible) {
			List<Object> params = new ArrayList<>();
			params.add(tenantId);
			if (ids != null)
				params.add(ids);
			if (eligible)
				params.addAll(List.of(ReindexJobs.eligibleParams()));
			return params.toArray();
		}
	}

	/**
	 * @return the scope with its target found; null if its target isn't live
	 */
	private Resolved resolve(ReindexScope scope, long tenantId) throws PersistenceException {
		return switch (scope.kind()) {
		case DOCUMENTS -> documents(scope.distinctDocNumbers(), tenantId);
		case FOLDER -> folder(scope.targetId(), tenantId);
		case MATTER -> matter(scope.targetId(), tenantId);
		case STORE -> store(scope.targetId(), tenantId);
		case EVERYTHING -> new Resolved("Everything", targetKind(Kind.EVERYTHING), null, "", null, null);
		};
	}

	/** @return the tree of a folder's live folders, as the scope's one parameter */
	private static Resolved tree(String name, Kind kind, long targetId, long rootId) {
		return new Resolved(name, targetKind(kind), targetId, " and d.ld_folderid = any(?)",
				StoredRows.get().tree(rootId).toArray(Long[]::new), null);
	}

	private Resolved folder(long folderId, long tenantId) throws PersistenceException {
		Folder folder = FolderDAO.get().findById(folderId);
		if (folder == null || folder.getDeleted() != 0 || folder.getTenantId() != tenantId
				|| folder.getFoldRef() != null)
			return null;
		String name = "Folder " + folder.getName();
		Matter matter = MatterLocator.get().locate(folder).matter();
		if (matter != null) {
			Client client = ClientDAO.get().findById(matter.getClientId());
			if (client != null)
				name += " in " + MatterDisplay.number(client.getNumber(), matter.getNumber()) + " "
						+ matter.getName();
		}
		return tree(name, Kind.FOLDER, folderId, folderId);
	}

	private Resolved matter(long matterId, long tenantId) {
		List<Resolved> found = jdbc.query("""
				select m.hd_folderid, m.hd_number, m.hd_name, c.hd_number as clientnumber
				  from hd_matter m
				  join hd_client c on c.ld_id = m.hd_clientid and c.ld_deleted = 0
				  join ld_folder f on f.ld_id = m.hd_folderid and f.ld_deleted = 0
				 where m.ld_id = ? and m.ld_deleted = 0 and f.ld_tenantid = ?""",
				(rs, i) -> tree(
						"Matter " + MatterDisplay.number(rs.getLong("clientnumber"), rs.getLong("hd_number")) + " "
								+ rs.getString("hd_name"),
						Kind.MATTER, matterId, rs.getLong("hd_folderid")),
				matterId, tenantId);
		return found.isEmpty() ? null : found.get(0);
	}

	private Resolved store(long storeId, long tenantId) throws PersistenceException {
		Folder store = StoreService.get().store(storeId);
		if (store == null || store.getTenantId() != tenantId)
			return null;
		return tree("Store " + store.getName(), Kind.STORE, storeId, storeId);
	}

	/** A document a doc # names, whether a job would try it, and the matter numbers stamped on it */
	private record Found(long id, String fileName, boolean eligible, Long client, Long matter) {
	}

	/**
	 * @param docNumbers the doc #s, each once, in the order given
	 */
	private Resolved documents(List<Long> docNumbers, long tenantId) throws PersistenceException {
		String[] numbers = docNumbers.stream().map(String::valueOf).toArray(String[]::new);
		Object[] eligible = ReindexJobs.eligibleParams();
		Map<String, Found> byNumber = new HashMap<>();
		jdbc.query("""
				select d.ld_id, d.ld_customid, d.ld_filename, case when %s then 1 else 0 end as eligible,
				       c.ld_stringvalue as client, m.ld_stringvalue as matter
				  from ld_document d
				  left join ld_document_ext c on c.ld_docid = d.ld_id and c.ld_name = ?
				  left join ld_document_ext m on m.ld_docid = d.ld_id and m.ld_name = ?
				 where d.ld_tenantid = ? and d.ld_deleted = 0 and d.ld_docref is null and d.ld_customid = any(?)"""
				.formatted(ReindexJobs.ELIGIBLE),
				ReindexJobs.bind(eligible[0], eligible[1], HealdanConstants.ATTR_CLIENT, HealdanConstants.ATTR_MATTER,
						tenantId, numbers),
				rs -> {
					byNumber.put(rs.getString("ld_customid"),
							new Found(rs.getLong("ld_id"), rs.getString("ld_filename"), rs.getInt("eligible") == 1,
									stamp(rs.getString("client")), stamp(rs.getString("matter"))));
				});

		Map<String, FailedDocument.Matter> matters = matters(byNumber.values());
		List<Match> matches = new ArrayList<>();
		Set<Long> ids = new LinkedHashSet<>();
		for (long number : docNumbers) {
			Found found = byNumber.get(Long.toString(number));
			if (found == null) {
				matches.add(new Match(number, Status.NOT_FOUND, null, null, null));
				continue;
			}
			ids.add(found.id());
			matches.add(new Match(number, found.eligible() ? Status.FOUND : Status.LEFT_OUT, found.id(),
					found.fileName(),
					found.client() != null && found.matter() != null
							? matters.get(MatterDisplay.number(found.client(), found.matter()))
							: null));
		}
		String name = ids.size() == 1 ? "1 Document" : String.format(Locale.US, "%,d Documents", ids.size());
		return new Resolved(name, targetKind(Kind.DOCUMENTS), null, " and d.ld_id = any(?)",
				ids.toArray(Long[]::new), matches);
	}

	/**
	 * @return the live matters the documents' stamps name, by their numbers ({@code 1001.1})
	 */
	private static Map<String, FailedDocument.Matter> matters(Iterable<Found> documents) throws PersistenceException {
		Set<Long> clientNumbers = new HashSet<>();
		Set<Long> matterNumbers = new HashSet<>();
		for (Found document : documents)
			if (document.client() != null && document.matter() != null) {
				clientNumbers.add(document.client());
				matterNumbers.add(document.matter());
			}
		Map<String, FailedDocument.Matter> matters = new HashMap<>();
		if (clientNumbers.isEmpty())
			return matters;
		Map<Long, Client> clients = new HashMap<>();
		for (Client client : ClientDAO.get().findByNumbers(clientNumbers))
			clients.put(client.getId(), client);
		for (Matter matter : MatterDAO.get().findLiveByClientsAndNumbers(clients.keySet(), matterNumbers)) {
			Client client = clients.get(matter.getClientId());
			String number = MatterDisplay.number(client.getNumber(), matter.getNumber());
			matters.put(number, new FailedDocument.Matter(matter.getId(), number, matter.getName()));
		}
		return matters;
	}

	/** @return a stamped number, read trimmed; null if there's none or it isn't a number */
	private static Long stamp(String stamp) {
		if (stamp == null)
			return null;
		try {
			return Long.valueOf(stamp.strip());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
