package com.logicaldoc.healdan.api.model;

/**
 * A Store named in another object
 *
 * @param id the Store's folder id
 * @param name the Store's name
 */
public record StoreRef(long id, String name) {
}
