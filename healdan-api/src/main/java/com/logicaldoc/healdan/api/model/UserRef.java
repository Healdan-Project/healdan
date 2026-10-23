package com.logicaldoc.healdan.api.model;

/**
 * A user named in another object, such as a document's creator.
 *
 * @param id the user's id
 * @param name the name as it was recorded
 */
public record UserRef(long id, String name) {
}
