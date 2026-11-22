// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

/**
 * A document a fault's latest message names, for the fault's details (Admin
 * app spec section 8.8).
 *
 * @param id LogicalDOC's id of the document, as the message names it
 * @param docNumber its doc #; null when no document has that id, or its custom
 *        id isn't a doc #
 * @param fileName its file name; null when no document has that id
 */
public record ErrorDocument(long id, Long docNumber, String fileName) {
}
