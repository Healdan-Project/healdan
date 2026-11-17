// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.Date;

/**
 * One delete in the Trash, a folder or a document (folders and Trash spec 6.5,
 * plan correction 7).
 *
 * @param id the item's id
 * @param type {@code document} or {@code folder}
 * @param name the document's file name or the folder's name, as it was
 *        deleted
 * @param docNumber a document's doc #; null for a folder, and for a document
 *        that had none
 * @param kind the kind of file for a document's icon, as {@code DocumentView}'s
 *        {@code kind}; {@code folder} for a folder
 * @param size a document's file size in bytes; null for a folder
 * @param contents what a folder holds in the item's state that the caller
 *        can read; null for a document
 * @param deletedFrom where it was deleted from
 * @param matter the matter it was in, or is; null if none
 * @param deletedBy who deleted it
 * @param deletedAt when
 * @param removedBy who removed it from the Trash; null unless it is removed,
 *        and for one removed before the Trash recorded who
 * @param removedAt when; null unless it is removed
 * @param destroyAfter the night the nightly job destroys it; null unless it
 *        is removed
 */
public record TrashItem(long id, String type, String name, Long docNumber, String kind, Long size,
		TrashContents contents, DeletedFrom deletedFrom, MatterRef matter, UserRef deletedBy, Date deletedAt,
		UserRef removedBy, Date removedAt, Date destroyAfter) {
}
