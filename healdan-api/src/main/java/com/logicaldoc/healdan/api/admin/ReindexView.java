// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.logicaldoc.healdan.admin.index.ReindexJob;
import com.logicaldoc.healdan.admin.index.ReindexPreview;
import com.logicaldoc.healdan.api.admin.MatterView.PersonBrief;

/**
 * The reindex routes' answers, as {@code openapi-admin.yaml} has them: {@code ReindexPreview}, {@code ReindexMatch}
 * and {@code ReindexJob}, their kinds, states and statuses written in lower case. A match's document is the Search
 * Index's {@code IndexDocument}, {@link SearchIndexView.IndexDocument}.
 */
public final class ReindexView {

	private ReindexView() {
	}

	/**
	 * {@code ReindexMatch}: a doc # given for a reindex of documents, and what it names.
	 *
	 * @param docNumber the doc #, as given
	 * @param status {@code found}, {@code left-out} or {@code not-found}
	 * @param document the document it names; null for {@code not-found}
	 */
	public record ReindexMatch(long docNumber, String status, SearchIndexView.IndexDocument document) {

		static ReindexMatch of(ReindexPreview.Match match) {
			return new ReindexMatch(match.docNumber(), match.status().name().toLowerCase(Locale.ROOT).replace('_', '-'),
					match.found() ? SearchIndexView.IndexDocument.of(match.id(), match.docNumber(), match.fileName(),
							match.matter()) : null);
		}
	}

	/**
	 * {@code ReindexPreview}: what a reindex of a scope would cover, if it were started now.
	 */
	public record Preview(String name, long count, long skipped, Long estimateSeconds, List<ReindexMatch> documents,
			String refusal) {

		static Preview of(ReindexPreview preview) {
			return new Preview(preview.name(), preview.count(), preview.skipped(), preview.estimateSeconds(),
					preview.documents() != null ? preview.documents().stream().map(ReindexMatch::of).toList() : null,
					preview.refusal());
		}
	}

	/**
	 * {@code ReindexJob}: a reindex, with its progress.
	 */
	public record Job(long id, String kind, Long targetId, String name, PersonBrief startedBy, String state,
			boolean cancelling, boolean emptyFirst, long total, long done, long failed, long skipped,
			long unreachable, long leftOut, Date created, Date started, Date ended, Date stalledSince,
			Date failingSince, Long estimateSecondsLeft) {

		static Job of(ReindexJob job) {
			return new Job(job.id(), job.kind().name().toLowerCase(Locale.ROOT), job.targetId(), job.name(),
					new PersonBrief(job.startedById(), job.startedByUsername(), job.startedByName()),
					job.state().name().toLowerCase(Locale.ROOT), job.cancelling(), job.emptyFirst(), job.total(),
					job.done(), job.failed(), job.skipped(), job.unreachable(), job.leftOut(), date(job.created()),
					date(job.started()), date(job.ended()), date(job.stalledSince()), date(job.failingSince()),
					job.estimateSecondsLeft());
		}
	}

	private static Date date(Instant instant) {
		return instant != null ? Date.from(instant) : null;
	}
}
