package com.logicaldoc.healdan.api.model;

/**
 * A folder named in another object.
 *
 * @param id the folder's id
 * @param name the folder's name
 * @param kind {@code store}, {@code matter} (a matter's workspace),
 *        {@code folder} or {@code searchFolder}
 */
public record FolderRef(long id, String name, String kind) {
}
