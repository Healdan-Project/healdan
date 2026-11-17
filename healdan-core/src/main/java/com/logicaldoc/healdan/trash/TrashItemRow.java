// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.time.Instant;
import java.util.List;

/**
 * One delete a person made: a folder or a document, with what it took.
 *
 * @param kind <code>document</code> or <code>folder</code>
 * @param fromPath the names of the folders it was in, matter first
 */
public record TrashItemRow(long id, long tenantId, String kind, long objectId, String name, String docNumber,
		Long fromFolderId, List<String> fromPath, Long matterId, int folders, int documents, Long deletedById,
		String deletedByName, Instant deletedAt) {
}
