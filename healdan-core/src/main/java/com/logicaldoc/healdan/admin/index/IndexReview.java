// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

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
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
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
import com.logicaldoc.util.spring.Context;

/**
 * Try Again and Skip, for a document that couldn't be indexed (Admin app spec section 8.9; ruling P6-R11). Try Again
 * indexes the document now, on the caller's thread, through CE's own {@code DocumentManager.index}, which writes its
 * {@code event.indexed} or {@code event.indexed.error} row as the actor; Skip sets the document to Skip, so CE's
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
	 * @param document the document as it was, failed, when it was tried
	 * @param outcome {@link #INDEXED} or {@link #FAILED}
	 * @param reason when it failed, why, as the new error's event says; null when it was indexed, or the error says
	 *        nothing
	 * @param when when it failed, as the new error's event says; null when it was indexed
	 */
	public record Tried(FailedDocument document, String outcome, String reason, Instant when) {

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
	 * Try Again: indexes a document that couldn't be indexed, now, on the caller's thread. CE writes the event as the
	 * actor, with the session when there is one; the outcome is answered either way.
	 *
	 * @param docId the document's id
	 * @param actor who asks
	 * @param session the actor's session; null for none
	 * @return what it did
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator, before anything else, and
	 *         {@link #NOT_FAILED} for a document that isn't a live one of the actor's tenant that couldn't be indexed
	 */
	public Tried retry(long docId, User actor, Session session) throws PersistenceException {
		requireAdmin(actor);
		FailedDocument failed = requireFailed(docId, actor);

		DocumentHistory history = new DocumentHistory();
		history.setUser(actor);
		if (session != null)
			history.setSession(session);
		Tried tried;
		try {
			DocumentManager.get().index(docId, null, history);
			tried = new Tried(failed, INDEXED, null, null);
		} catch (ParsingException | PersistenceException e) {
			// CE has written the error's event, which says why; its message can name the file, so it isn't logged
			log.debug("Document {} couldn't be indexed again: {}", docId, e.getClass().getName());
			FailedDocument now = counts.failure(docId, actor.getTenantId());
			tried = now != null ? new Tried(failed, FAILED, now.reason(), now.when())
					: new Tried(failed, FAILED, e.getMessage(), null);
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
	 * @return how the record names a document: its doc # and file name, as the Audit Trail names a document's rows,
	 *         such as {@code # 10388, letter.pdf}
	 */
	static String name(FailedDocument document) {
		String number = document.docNumber();
		boolean numbered = number != null && !number.isBlank();
		if (document.name() == null || document.name().isBlank())
			return numbered ? "# " + number.strip() : null;
		return numbered ? "# " + number.strip() + ", " + document.name() : document.name();
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
