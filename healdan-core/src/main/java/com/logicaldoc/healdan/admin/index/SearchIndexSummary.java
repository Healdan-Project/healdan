// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import com.logicaldoc.healdan.admin.dashboard.HealthTile;
import com.logicaldoc.healdan.admin.jobs.JobView;

/**
 * The Search Index's header (Admin app spec section 8.9; ruling P6-R14), read at the request.
 *
 * @param counts the documents indexed, waiting and failed, as {@link IndexCounts#counts()} gives them
 * @param entries how many entries the index holds; null when that can't be read
 * @param sizeBytes the bytes the index's folder takes; null when that can't be read
 * @param health the Dashboard's Search Index tile, as it makes it
 * @param indexer CE's Indexer, as Background Jobs shows it; null if the server has none
 */
public record SearchIndexSummary(IndexCounts.Counts counts, Long entries, Long sizeBytes, HealthTile health,
		JobView indexer) {
}
