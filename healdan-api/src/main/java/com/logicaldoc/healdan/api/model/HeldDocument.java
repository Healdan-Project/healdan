// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * A checked-out document a folder holds, named in a folder's
 * {@code 409 checked-out} problem (folders and Trash spec, 4.2 and 4.3). Only
 * documents the caller can read are named.
 *
 * @param docNumber the doc #
 * @param name the file name
 * @param hold who holds it and since when, as a document's
 *        {@code checked-out} problem gives it
 */
public record HeldDocument(long docNumber, String name, HoldView hold) {
}
