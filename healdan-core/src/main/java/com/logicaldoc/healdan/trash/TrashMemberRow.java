// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.time.Instant;

/**
 * Where one folder or document of a delete stands, and who moved it and when.
 */
public record TrashMemberRow(long trashId, TrashMember member, TrashState state, Long removedById,
		String removedByName, Instant removedAt, Long restoredById, String restoredByName, Instant restoredAt,
		Instant destroyedAt) {
}
