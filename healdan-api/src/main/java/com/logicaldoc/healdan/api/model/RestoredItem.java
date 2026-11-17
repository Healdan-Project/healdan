// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * What a restore answers (folders and Trash spec 6.5, plan correction 8).
 *
 * @param place the folder the item went back to, as {@code getFolder} gives
 *        it, so a client can name its matter and path
 * @param item the item, restored
 */
public record RestoredItem(FolderView place, TrashItem item) {
}
