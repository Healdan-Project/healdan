// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

/**
 * A folder or document a delete took.
 *
 * @param type <code>F</code> for a folder, <code>D</code> for a document
 * @param objectId its id
 */
public record TrashMember(char type, long objectId) {
}
