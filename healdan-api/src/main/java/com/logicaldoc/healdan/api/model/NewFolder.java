package com.logicaldoc.healdan.api.model;

/**
 * The body of {@code POST /v1/folders} (spec section 9.2).
 *
 * @param parentId the folder to create the subfolder in
 * @param name the new folder's name
 */
public record NewFolder(Long parentId, String name) {
}
