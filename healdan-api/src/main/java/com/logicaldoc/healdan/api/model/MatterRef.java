package com.logicaldoc.healdan.api.model;

/**
 * A matter named in another object.
 *
 * @param id the matter's id
 * @param display the matter as people read it, such as
 *        {@code 998.1 - Healdan Demo}
 */
public record MatterRef(long id, String display) {
}
