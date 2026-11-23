// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.time.Instant;

import com.logicaldoc.healdan.admin.index.ReindexScope.Kind;

/**
 * A reindex job, as {@code hd_reindex_job} holds it (Admin app spec section 9.6, rulings P6-R2 to P6-R5). Once it
 * has ended as {@link State#DONE}, {@code done + failed + skipped = total}.
 *
 * @param id its id
 * @param kind what kind of scope it covers
 * @param targetId its scope's target, as {@link ReindexScope#targetId()}; null for documents and everything
 * @param name what it covers, as the Admin app shows it, such as {@code Matter 1001.1 Charter Dispute}
 * @param startedById who started it
 * @param startedByUsername their username, as the job kept it
 * @param startedByName their name now, as {@link com.logicaldoc.healdan.admin.PersonNames#of} gives it; the
 *        username the job kept when the account is gone
 * @param state where it is
 * @param cancelling whether it was asked to stop while running, which it does at its next batch; false in every other
 *        state
 * @param emptyFirst whether it empties the index and marks every document to index when its run begins
 * @param total the documents it will try, fixed when it was started
 * @param done those indexed
 * @param failed those it tried that couldn't be indexed
 * @param skipped those no longer to be tried when their turn came: no longer live (in the Trash or destroyed), set
 *        to Skip, archived, or in a running transaction
 * @param leftOut what its scope held when it was started that it doesn't try: shortcuts, and documents set to
 *        Skip, archived or in a running transaction
 * @param created when it was started
 * @param started when its run began, null while it waits
 * @param ended when it ended, null while it hasn't
 * @param stalledSince since when the search index couldn't be written, so it waits and tries again; null while it
 *        can
 * @param estimateSecondsLeft about how many seconds it has left, as {@link ReindexService} works it out; null once it
 *        has ended, while stalled, when nothing is left, or where it wasn't worked out
 */
public record ReindexJob(long id, Kind kind, Long targetId, String name, long startedById, String startedByUsername,
		String startedByName, State state, boolean cancelling, boolean emptyFirst, long total, long done, long failed,
		long skipped, long leftOut, Instant created, Instant started, Instant ended, Instant stalledSince,
		Long estimateSecondsLeft) {

	/** Where a job is */
	public enum State {
		/** Started, and waiting its turn: one job runs at a time, the oldest first */
		WAITING,
		/** Running */
		RUNNING,
		/** It went through every document it covers */
		DONE,
		/** Cancelled before it went through them */
		CANCELLED;

		/** @return whether a job in this state hasn't ended */
		public boolean active() {
			return this == WAITING || this == RUNNING;
		}
	}

	/**
	 * @return how many of its documents it hasn't come to: its total less those done, failed and skipped
	 */
	public long left() {
		return Math.max(0, total - done - failed - skipped);
	}

	/**
	 * @param estimate about how many seconds it has left
	 * @return it, with that estimate
	 */
	public ReindexJob withEstimate(Long estimate) {
		return new ReindexJob(id, kind, targetId, name, startedById, startedByUsername, startedByName, state,
				cancelling, emptyFirst, total, done, failed, skipped, leftOut, created, started, ended, stalledSince,
				estimate);
	}

	/**
	 * @return whether it hasn't ended
	 */
	public boolean active() {
		return state.active();
	}
}
