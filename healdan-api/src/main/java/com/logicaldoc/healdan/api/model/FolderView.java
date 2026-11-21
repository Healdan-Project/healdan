// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param canMove whether the folder itself lets the caller move it, as
 *        {@code canRename} says for a rename (folders and Trash spec 6.3);
 *        where it goes, and what it holds, aren't counted
 * @param canDelete whether the folder itself lets the caller delete it, on
 *        the same terms; what it holds isn't counted
 * @param lastUsed when the caller last changed a document in it; only in
 *        Recent's list, and left out of every other answer
 */
public record FolderView(String type, long id, String name, String kind, MatterRef matter, StoreRef store,
		List<FolderRef> path, boolean canFile, boolean canRename, boolean canMove, boolean canDelete,
		@JsonInclude(JsonInclude.Include.NON_NULL) Date lastUsed) {

	/**
	 * @param used when the caller last used the folder
	 * @return this view with {@code lastUsed} set
	 */
	public FolderView withLastUsed(Date used) {
		return new FolderView(type, id, name, kind, matter, store, path, canFile, canRename, canMove, canDelete, used);
	}
}
