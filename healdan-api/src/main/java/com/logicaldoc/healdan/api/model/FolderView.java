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
 */
public record FolderView(String type, long id, String name, String kind, MatterRef matter, StoreRef store,
		List<FolderRef> path, boolean canFile) {
}
