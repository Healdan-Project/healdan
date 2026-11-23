// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.logicaldoc.healdan.admin.index.FailedDocument;
import com.logicaldoc.healdan.admin.index.IndexEvent;
import com.logicaldoc.healdan.admin.index.SearchIndexSummary;
import com.logicaldoc.healdan.admin.index.WaitingDocument;
import com.logicaldoc.healdan.admin.jobs.JobView;
import com.logicaldoc.healdan.api.admin.PeopleView.MatterBrief;

/**
 * The Search Index's answers, {@code openapi-admin.yaml}'s schemas from {@code SearchIndex} to {@code IndexRetry}
 * (Admin app spec section 8.9).
 */
final class SearchIndexView {

	private SearchIndexView() {
	}

	/** {@code SearchIndex}: the heading */
	record SearchIndex(long indexed, long waiting, long failed, Long entries, Long bytes, IndexHealth health,
			Indexer indexer) {

		static SearchIndex of(SearchIndexSummary summary) {
			return new SearchIndex(summary.counts().indexed(), summary.counts().waiting(), summary.counts().failed(),
					summary.entries(), summary.sizeBytes(),
					new IndexHealth(summary.health().status(), summary.health().detail()),
					Indexer.of(summary.indexer(), summary.runRefusal()));
		}
	}

	/** {@code IndexHealth}: the Dashboard's Search Index tile's status and line */
	record IndexHealth(String status, String detail) {
	}

	/**
	 * {@code Indexer}: CE's Indexer as Background Jobs shows it, and what Run Now would refuse, which the button
	 * follows: just after a Run Now it says the Indexer is running while the job's {@code canRun} may still be true
	 */
	record Indexer(BackgroundJobView job, String runRefusal) {

		static Indexer of(JobView job, String runRefusal) {
			if (job == null)
				throw new IllegalStateException("The server has no Indexer task");
			return new Indexer(BackgroundJobView.of(job, Instant.now()), runRefusal);
		}
	}

	/** {@code IndexDocument}: a document as the Search Index names it */
	record IndexDocument(long id, Long docNumber, String fileName, MatterBrief matter) {

		static IndexDocument of(long id, String docNumber, String fileName, FailedDocument.Matter matter) {
			return new IndexDocument(id, SearchIndexView.docNumber(docNumber), fileName,
					matter != null ? new MatterBrief(matter.id(), matter.number(), matter.name()) : null);
		}
	}

	/** {@code IndexFailure} */
	record IndexFailure(IndexDocument document, String reason, Date when) {

		static IndexFailure of(FailedDocument failed) {
			return new IndexFailure(IndexDocument.of(failed.id(), failed.docNumber(), failed.name(), failed.matter()),
					failed.reason(), date(failed.when()));
		}
	}

	/** {@code IndexWaiting} */
	record IndexWaiting(IndexDocument document, Date lastChanged) {

		static IndexWaiting of(WaitingDocument waiting) {
			return new IndexWaiting(
					IndexDocument.of(waiting.id(), waiting.docNumber(), waiting.name(), waiting.matter()),
					date(waiting.lastChanged()));
		}
	}

	/** {@code IndexEvent} */
	record IndexEventView(long id, Date when, String event, String reason, String who, IndexDocument document) {

		static IndexEventView of(IndexEvent event) {
			return new IndexEventView(event.id(), date(event.when()), event.failed() ? "failed" : "indexed",
					event.reason(), event.who(),
					IndexDocument.of(event.docId(), event.docNumber(), event.fileName(), event.matter()));
		}
	}

	/** {@code IndexHistoryPage} */
	record IndexHistoryPageView(List<IndexEventView> items, String nextCursor, Date readBackTo) {
	}

	/** {@code IndexRetry} */
	record IndexRetry(String outcome, String reason) {
	}

	/** @return a doc # kept as text, as a number; null when there's none or it isn't one */
	static Long docNumber(String customId) {
		if (customId == null)
			return null;
		String digits = customId.strip();
		if (digits.isEmpty() || digits.length() > 18 || !digits.chars().allMatch(c -> c >= '0' && c <= '9'))
			return null;
		return Long.valueOf(digits);
	}

	private static Date date(Instant instant) {
		return instant != null ? Date.from(instant) : null;
	}
}
