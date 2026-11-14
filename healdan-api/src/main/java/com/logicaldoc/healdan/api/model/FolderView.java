// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;

/**
 * A folder inside a Store, as the API shows it (spec section 6.2)
 *
 * @param type always {@code folder}
 * @param id the folder's id
 * @param name the folder's name
 * @param kind {@code store}, {@code matter}, {@code folder} or {@code searchFolder}
 * @param matter the matter the folder is or is in; null for a Store
 * @param store the Store it is in, or is
 * @param path from the Store down to the folder itself
 * @param canFile whether the caller may file documents into it
 * @param canRename whether {@code PATCH /v1/folders/{id}} would rename it for
 *        the caller (folder rename spec section 4.2)
 */
public record FolderView(String type, long id, String name, String kind, MatterRef matter, StoreRef store,
		List<FolderRef> path, boolean canFile, boolean canRename) {
}
