package com.logicaldoc.healdan.api.model;

/**
 * A user {@code GET /v1/users} found (spec section 6.1).
 *
 * @param id the user's id
 * @param username the login
 * @param name the full name
 */
public record UserHit(long id, String username, String name) {
}
