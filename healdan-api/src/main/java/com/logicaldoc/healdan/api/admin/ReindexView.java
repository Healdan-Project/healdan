// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.logicaldoc.healdan.admin.index.FailedDocument;
import com.logicaldoc.healdan.admin.index.ReindexJob;
import com.logicaldoc.healdan.admin.index.ReindexPreview;
import com.logicaldoc.healdan.api.admin.MatterView.PersonBrief;
import com.logicaldoc.healdan.api.admin.PeopleView.MatterBrief;

/**
 * The reindex routes' answers, as {@code openapi-admin.yaml} has them: {@code ReindexPreview}, {@code ReindexMatch},
 * {@code IndexDocument} and {@code ReindexJob}, their kinds, states and statuses written in lower case.
 */
public final class ReindexView {

	private ReindexView() {
	}

	/**
	 * {@code IndexDocument}: a document, as the Search Index names it.
	 *
	 * @param id its id
	 * @param docNumber its doc #; null when it has none
	 * @param fileName its file name
	 * @param matter the live matter stamped on it; null if none
	 */
	public record IndexDocument(long id, Long docNumber, String fileName, MatterBrief matter) {
	}

	/**
	 * {@code ReindexMatch}: a doc # given for a reindex of documents, and what it names.
	 *
	 * @param docNumber the doc #, as given
	 * @param status {@code found}, {@code left-out} or {@code not-found}
	 * @param document the document it names; null for {@code not-found}
	 */
	public record ReindexMatch(long docNumber, String status, IndexDocument document) {

		static ReindexMatch of(ReindexPreview.Match match) {
			return new ReindexMatch(match.docNumber(), match.status().name().toLowerCase(Locale.ROOT).replace('_', '-'),
					match.found() ? new IndexDocument(match.id(), match.docNumber(), match.fileName(),
							matter(match.matter())) : null);
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
			boolean cancelling, boolean emptyFirst, long total, long done, long failed, long skipped, long leftOut,
			Date created, Date started, Date ended, Date stalledSince, Long estimateSecondsLeft) {

		static Job of(ReindexJob job) {
			return new Job(job.id(), job.kind().name().toLowerCase(Locale.ROOT), job.targetId(), job.name(),
					new PersonBrief(job.startedById(), job.startedByUsername(), job.startedByName()),
					job.state().name().toLowerCase(Locale.ROOT), job.cancelling(), job.emptyFirst(), job.total(),
					job.done(), job.failed(), job.skipped(), job.leftOut(), date(job.created()), date(job.started()),
					date(job.ended()), date(job.stalledSince()), job.estimateSecondsLeft());
		}
	}

	private static MatterBrief matter(FailedDocument.Matter matter) {
		return matter != null ? new MatterBrief(matter.id(), matter.number(), matter.name()) : null;
	}

	private static Date date(Instant instant) {
		return instant != null ? Date.from(instant) : null;
	}
}
