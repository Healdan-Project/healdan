// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.parser.ParsingException;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.healdan.util.StorageUnreachableException;
import com.logicaldoc.util.spring.Context;

/**
 * Try Again and Skip, for a document that couldn't be indexed (Admin app spec section 8.9; ruling P6-R11). Try Again
 * indexes the document now, on the caller's thread, through CE's own {@code DocumentManager.index}, given no event of
 * its own, and then writes the {@code event.indexed} or {@code event.indexed.error} row itself, as the actor's
 * ({@link IndexEventRows}, ruling P6-R24); Skip sets the document to Skip, so CE's Indexer leaves it. Each takes
 * only a live document of the actor's tenant that couldn't be indexed, each moves {@link IndexCounts#changes()} on,
 * and each is recorded straight after it (area {@code SEARCH_INDEX}, aimed at the document by its id, named by its
 * doc # and file name): {@value #RETRIED}, with the outcome, and {@value #SKIPPED}. Administrators only.
 */
@Component
public class IndexReview {

	private static final Logger log = LoggerFactory.getLogger(IndexReview.class);

	/** The refusal of anyone but an administrator */
	public static final String ONLY_ADMINS = "Only an administrator can read the search index, try a document again"
			+ " or set it to Skip.";

	/** The refusal of a document that isn't one that couldn't be indexed */
	public static final String NOT_FAILED = "This document is no longer waiting after an error.";

	/** The refusal of Try Again while the search index itself can't be written (ruling P6-R29) */
	public static final String INDEX_UNWRITABLE = "The search index can't be written just now. Try again later.";

	/** Try Again's reason for a document the tenant's file-pattern rules leave out of the index, which it sets to Skip */
	public static final String LEFT_OUT = "The server's settings leave this kind of file out of the index, so it's set"
			+ " to Skip.";

	/** The action Try Again records */
	public static final String RETRIED = "index.retried";

	/** The action Skip records */
	public static final String SKIPPED = "index.skipped";

	/** What the records aim at */
	public static final String TARGET = "document";

	/** Try Again's outcome when the document was indexed */
	public static final String INDEXED = "indexed";

	/** Try Again's outcome when it couldn't be indexed again */
	public static final String FAILED = "failed";

	private final IndexCounts counts;

	private final AdminEvents events;

	private final CacheEvictor evictor;

	private final JdbcTemplate jdbc;

	/**
	 * How a document is indexed when no test changes it: CE's own index of it, its text read first
	 * ({@link DocumentIndexing}), which writes no event of its own
	 */
	static final Indexing REAL = DocumentIndexing::index;

	/** Indexes a document through CE's index, which then writes no event of its own; a seam for tests */
	volatile Indexing indexing = REAL;

	/** The search index, asked whether it answers after an index fault; tests set it and put it back */
	volatile IndexFaults.Index index = IndexFaults.REAL;

	/** Indexes a document */
	@FunctionalInterface
	interface Indexing {
		void index(long docId) throws PersistenceException, ParsingException;
	}

	@Autowired
	public IndexReview(IndexCounts counts, AdminEvents events, CacheEvictor evictor, DataSource dataSource) {
		this.counts = counts;
		this.events = events;
		this.evictor = evictor;
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean in the application context
	 */
	public static IndexReview get() {
		return Context.get(IndexReview.class);
	}

	/**
	 * What Try Again did.
	 *
	 * @param outcome {@link #INDEXED} or {@link #FAILED}
	 * @param reason when it failed, why, as the new error's event says; null when it was indexed, or the error says
	 *        nothing
	 */
	public record Tried(String outcome, String reason) {

		/** @return whether the document is in the index now */
		public boolean indexed() {
			return INDEXED.equals(outcome);
		}
	}

	/**
	 * Finds a live document of the actor's tenant by its doc #, as the public API does: a document of its own, not a
	 * shortcut, not in the Trash.
	 *
	 * @param docNumber the doc #
	 * @param actor who asks
	 * @return the document's id; empty if no live document has the doc #
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator, before anything else
	 */
	public OptionalLong documentId(long docNumber, User actor) throws PersistenceException {
		requireAdmin(actor);
		List<Long> ids = jdbc.queryForList("select ld_id from ld_document where ld_customid = ? and ld_tenantid = ?"
				+ " and ld_docref is null and ld_deleted = 0", Long.class, Long.toString(docNumber),
				actor.getTenantId());
		return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.get(0));
	}

	/**
	 * Try Again: indexes a document that couldn't be indexed, now, on the caller's thread, through CE's own
	 * {@code DocumentManager.index}, and writes the event CE's Indexer writes ({@link IndexEventRows}),
	 * {@code event.indexed} or {@code event.indexed.error} with the error's message, as the actor, with the session
	 * when there is one, and, like CE, sets the document to Skip after its error where {@code index.skiponerror} says
	 * so; the outcome is answered either way (ruling P6-R24), and any failure but the two below is the document's
	 * own (ruling P6-R29). Once the index has been asked, the counts move on and the change is recorded even if
	 * writing the row then fails. A file whose storage can't be reached is neither (ruling P6-R23): nothing is
	 * written, counted or recorded, and the outage is thrown, which the API answers {@code 503}. Nor is a failure an
	 * index that can't be written gives, when the index is then asked and doesn't answer (rulings P6-R26 and P6-R29):
	 * nothing is written, set to Skip, counted or recorded, and it's refused, {@link #INDEX_UNWRITABLE}.
	 * <p>
	 * CE's index reads the file inside itself, and when it's given an event it writes the error's row before it
	 * throws, so it's given none, and the row is written here once the failure is known to be the document's own.
	 * The file's text is read first ({@link DocumentIndexing}), so a file whose storage can't be reached is the outage
	 * whatever {@code index.ignorecontenterror} says (review of #312, finding 2).
	 * <p>
	 * The tenant's file-pattern rules decide how, as CE's own save of a document does ({@link IndexRules}; finding
	 * 1): a document they have indexed by its properties alone is set so first, so CE reads no file of it, and stays
	 * so after a failure; one they leave out isn't indexed but set to Skip, with {@link #LEFT_OUT} as its reason and
	 * its error's row. A failure isn't written over an index of the document made since the try began, as by CE's
	 * Indexer at that moment ({@link IndexEventRows#indexedSince}; finding 4): it's answered as indexed.
	 *
	 * @param docId the document's id
	 * @param actor who asks
	 * @param session the actor's session; null for none
	 * @return what it did
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator, before anything else, and
	 *         {@link #NOT_FAILED} for a document that isn't a live one of the actor's tenant that couldn't be indexed,
	 *         and {@link #INDEX_UNWRITABLE} while the search index can't be written
	 * @throws UncheckedIOException with the {@link StorageUnreachableException} when the file's storage can't be
	 *         reached
	 */
	public Tried retry(long docId, User actor, Session session) throws PersistenceException {
		requireAdmin(actor);
		FailedDocument failed = requireFailed(docId, actor);

		String sessionId = session != null ? session.getSid() : null;
		record Found(int status, String fileName) {
		}
		List<Found> found = jdbc.query("select ld_indexed, ld_filename from ld_document where ld_id = ?",
				(rs, i) -> new Found(rs.getInt(1), rs.getString(2)), docId);
		if (found.isEmpty())
			throw new HealdanRuleException(NOT_FAILED);
		IndexingStatus before = IndexingStatus.values()[found.get(0).status()];
		IndexingStatus as = IndexRules.of(actor.getTenantId()).statusFor(found.get(0).fileName(), before);
		if (as == IndexingStatus.SKIP)
			return leaveOut(docId, failed, actor, sessionId);
		boolean marked = as == IndexingStatus.TO_INDEX_METADATA && before != as;
		// CE's index reads no file of a document set to be indexed by its properties
		if (marked && !setStatus(docId, before, as))
			throw new HealdanRuleException(NOT_FAILED);

		Instant began = Instant.ofEpochMilli(System.currentTimeMillis());
		Tried tried = null;
		try {
			try {
				indexing.index(docId);
			} catch (VirtualMachineError e) {
				throw e;
			} catch (Throwable e) {
				StorageUnreachableException outage = StorageUnreachableException.in(e);
				if (outage != null) {
					if (marked)
						setStatus(docId, as, before);
					throw new UncheckedIOException(outage);
				}
				if (IndexFaults.indexFault(e) && !IndexFaults.answers(index)) {
					if (marked)
						setStatus(docId, as, before);
					throw new HealdanRuleException(INDEX_UNWRITABLE);
				}
				// Indexed meanwhile, as by CE's Indexer: its failure isn't written over that
				if (IndexEventRows.indexedSince(jdbc, docId, began, false)) {
					tried = new Tried(INDEXED, null);
					return tried;
				}
				// The document's own failure, whatever it is. Its message can name the file, so it isn't logged
				log.debug("Document {} couldn't be indexed again: {}", docId, e.getClass().getName());
				String reason = IndexFaults.reason(e);
				tried = new Tried(FAILED, reason);
				IndexEventRows.failed(docId, actor, reason, sessionId);
				// As CE's index does after the error's event, where the tenant says so
				if (IndexSettings.skipOnError(actor.getTenantId()) && counts.skip(docId, actor.getTenantId()))
					evictor.evictDocuments(List.of(docId));
				FailedDocument now = counts.failure(docId, actor.getTenantId());
				if (now != null)
					tried = new Tried(FAILED, now.reason());
				return tried;
			}
			tried = new Tried(INDEXED, null);
			IndexEventRows.indexed(docId, actor, before, sessionId);
		} finally {
			// Once the index has been asked, the document's state may have changed, even if writing its row failed
			if (tried != null) {
				counts.moved();
				events.recordAfter(new AdminEvent(AdminArea.SEARCH_INDEX, RETRIED, TARGET, docId, name(failed),
						Map.of("outcome", new Change(null, tried.outcome()))), actor);
			}
		}
		return tried;
	}

	/**
	 * Try Again on a document the tenant's file-pattern rules leave out of the index: it isn't indexed, but given its
	 * error's row, {@link #LEFT_OUT}, and set to Skip, as CE's own save of it would; counted and recorded as a failure.
	 */
	private Tried leaveOut(long docId, FailedDocument failed, User actor, String sessionId)
			throws PersistenceException {
		Tried tried = new Tried(FAILED, LEFT_OUT);
		try {
			IndexEventRows.failed(docId, actor, LEFT_OUT, sessionId);
			if (counts.skip(docId, actor.getTenantId()))
				evictor.evictDocuments(List.of(docId));
		} finally {
			counts.moved();
			events.recordAfter(new AdminEvent(AdminArea.SEARCH_INDEX, RETRIED, TARGET, docId, name(failed),
					Map.of("outcome", new Change(null, tried.outcome()))), actor);
		}
		return tried;
	}

	/** @return whether the document's indexing status was {@code from}, and is now {@code to} */
	private boolean setStatus(long docId, IndexingStatus from, IndexingStatus to) {
		boolean changed = jdbc.update("update ld_document set ld_indexed = ? where ld_id = ? and ld_indexed = ?",
				to.ordinal(), docId, from.ordinal()) > 0;
		evictor.evictDocuments(List.of(docId));
		return changed;
	}

	/**
	 * Skip: sets a document that couldn't be indexed to Skip, so CE's Indexer no longer takes it. Its entry in the
	 * index, if it has one, stays as it is.
	 *
	 * @param docId the document's id
	 * @param actor who asks
	 * @return the document as it was, failed
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator, before anything else, and
	 *         {@link #NOT_FAILED} for a document that isn't a live one of the actor's tenant that couldn't be indexed
	 */
	public FailedDocument skip(long docId, User actor) throws PersistenceException {
		requireAdmin(actor);
		FailedDocument failed = requireFailed(docId, actor);
		// Found failed again as it's changed, so one indexed meanwhile isn't set to Skip
		if (!counts.skip(docId, actor.getTenantId()))
			throw new HealdanRuleException(NOT_FAILED);
		evictor.evictDocuments(List.of(docId));
		counts.moved();
		events.recordAfter(new AdminEvent(AdminArea.SEARCH_INDEX, SKIPPED, TARGET, docId, name(failed), null),
				actor);
		return failed;
	}

	private FailedDocument requireFailed(long docId, User actor) throws PersistenceException {
		FailedDocument failed = counts.failure(docId, actor.getTenantId());
		if (failed == null)
			throw new HealdanRuleException(NOT_FAILED);
		return failed;
	}

	/**
	 * @return how the record names a document: its doc # and file name, such as {@code # 10412 Witness Statement.pdf}
	 */
	static String name(FailedDocument document) {
		String number = document.docNumber();
		boolean numbered = number != null && !number.isBlank();
		if (document.name() == null || document.name().isBlank())
			return numbered ? "# " + number.strip() : null;
		return numbered ? "# " + number.strip() + " " + document.name() : document.name();
	}

	/*
	 * FilingRules reloads the user's groups, which are transient
	 */
	static void requireAdmin(User actor) throws PersistenceException {
		Objects.requireNonNull(actor, "actor");
		if (!FilingRules.get().isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
	}
}
