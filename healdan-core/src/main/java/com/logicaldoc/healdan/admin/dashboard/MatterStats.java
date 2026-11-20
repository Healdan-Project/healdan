// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import java.time.Instant;

/**
 * A matter's figures, as {@link UsageSummary} last worked them out, for the Clients and Matters lists (Admin app spec
 * sections 8.2 and 8.3): its live documents, counted through the client and matter stamped on them.
 *
 * @param documents how many live documents it has, not shortcuts and not in the Trash
 * @param bytes the size of their files, the current version of each
 * @param lastActivity when one of them was last filed or saved; null if it has none
 */
public record MatterStats(long documents, long bytes, Instant lastActivity) {

	/** A matter with no documents */
	public static final MatterStats NONE = new MatterStats(0, 0, null);
}
