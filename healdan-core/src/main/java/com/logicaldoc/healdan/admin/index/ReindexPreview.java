// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.util.List;

/**
 * What a reindex job of a scope would cover, if it were started now (Admin app spec section 8.9, rulings P6-R4 and
 * P6-R9).
 *
 * @param name the name the job would be given
 * @param count the documents it would try, which a job started now takes as its total
 * @param skipped what the scope holds that it would leave out: shortcuts, and documents set to Skip, archived or in a
 *        running transaction; a job started now takes it as its left-out
 * @param estimateSeconds about how long it would take once it runs; null for a count of 0
 * @param documents for a documents scope, each doc # given, a repeat left out, in the order given; null for the other
 *        kinds
 * @param refusal the sentence a start of the scope would be refused with now ({@link ReindexService#NOTHING_TO_REINDEX}
 *        or {@link ReindexService#ALREADY_REINDEXING}); null when it would start a job
 */
public record ReindexPreview(String name, long count, long skipped, Long estimateSeconds, List<Match> documents,
		String refusal) {

	public ReindexPreview {
		if (documents != null)
			documents = List.copyOf(documents);
	}

	/** What a doc # names */
	public enum Status {
		/** A live document a job would try */
		FOUND,
		/** A live document a job would leave out, counted in {@code skipped}: set to Skip, archived or busy */
		LEFT_OUT,
		/** No live document of the tenant, of its own, has it */
		NOT_FOUND
	}

	/**
	 * A doc # as a documents scope gave it
	 *
	 * @param docNumber the doc #
	 * @param status what it names
	 * @param id the live document it names, null for {@link Status#NOT_FOUND}
	 * @param fileName that document's file name, null for {@link Status#NOT_FOUND}
	 * @param matter the live matter stamped on that document; null if none
	 */
	public record Match(long docNumber, Status status, Long id, String fileName, FailedDocument.Matter matter) {

		/** @return whether it names a live document */
		public boolean found() {
			return status != Status.NOT_FOUND;
		}
	}
}
