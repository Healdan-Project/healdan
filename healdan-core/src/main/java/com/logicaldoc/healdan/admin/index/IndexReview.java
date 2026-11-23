// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.io.UncheckedIOException;
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
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
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
 * indexes the document now, on the caller's thread, through CE's own {@code DocumentManager.index}, with its
 * {@code event.indexed} or {@code event.indexed.error} row as the actor's; Skip sets the document to Skip, so CE's
 * Indexer leaves it. Each takes only a live document of the actor's tenant that couldn't be indexed, each moves
 * {@link IndexCounts#changes()} on, and each is recorded straight after it (area {@code SEARCH_INDEX}, aimed at the
 * document by its id, named by its doc # and file name): {@value #RETRIED}, with the outcome, and {@value #SKIPPED}.
 * Administrators only.
 */
@Component
public class IndexReview {

	private static final Logger log = LoggerFactory.getLogger(IndexReview.class);

	/** The refusal of anyone but an administrator */
	public static final String ONLY_ADMINS = "Only an administrator can read the search index, try a document again"
			+ " or set it to Skip.";

	/** The refusal of a document that isn't one that couldn't be indexed */
	public static final String NOT_FAILED = "This document is no longer waiting after an error.";

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

	/** Indexes a document through CE's index, which then writes no event of its own; a seam for tests */
	volatile Indexing indexing = docId -> DocumentManager.get().index(docId, null, null);

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
	 * {@code event.indexed} or {@code event.indexed.error} with the error's message, as the actor, with the session's id
	 * when there is one, and, like CE, sets the document to Skip after its error where {@code index.skiponerror} says
	 * so; the outcome is answered either way (ruling P6-R24). A file whose storage can't be reached is neither (ruling P6-R23): nothing is
	 * written, counted or recorded, and the outage is thrown, which the API answers {@code 503}.
	 * <p>
	 * CE's index reads the file inside itself, and when it's given an event it writes the error's row before it
	 * throws, so it's given none, and the row is written here once the failure is known to be the document's own.
	 *
	 * @param docId the document's id
	 * @param actor who asks
	 * @param session the actor's session; null for none
	 * @return what it did
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator, before anything else, and
	 *         {@link #NOT_FAILED} for a document that isn't a live one of the actor's tenant that couldn't be indexed;
	 *         any other failure of the index's, such as the document's row not being read, as it is
	 * @throws UncheckedIOException with the {@link StorageUnreachableException} when the file's storage can't be
	 *         reached
	 */
	public Tried retry(long docId, User actor, Session session) throws PersistenceException {
		requireAdmin(actor);
		FailedDocument failed = requireFailed(docId, actor);

		String sessionId = session != null ? session.getSid() : null;
		Tried tried;
		try {
			indexing.index(docId);
			IndexEventRows.indexed(docId, actor, IndexingStatus.TO_INDEX, sessionId);
			tried = new Tried(INDEXED, null);
		} catch (ParsingException e) {
			StorageUnreachableException outage = StorageUnreachableException.in(e);
			if (outage != null)
				throw new UncheckedIOException(outage);
			// The document's own failure, the index's included, which CE wraps so. Its message can name the file, so
			// it isn't logged
			log.debug("Document {} couldn't be indexed again: {}", docId, e.getClass().getName());
			IndexEventRows.failed(docId, actor, e.getMessage(), sessionId);
			Document document = DocumentDAO.get().findById(docId);
			if (document != null)
				IndexSettings.skipOnError(document);
			FailedDocument now = counts.failure(docId, actor.getTenantId());
			tried = new Tried(FAILED, now != null ? now.reason() : e.getMessage());
		}
		counts.moved();
		events.recordAfter(new AdminEvent(AdminArea.SEARCH_INDEX, RETRIED, TARGET, docId, name(failed),
				Map.of("outcome", new Change(null, tried.outcome()))), actor);
		return tried;
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
