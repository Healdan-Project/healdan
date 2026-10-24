package com.logicaldoc.healdan.api.model;

/**
 * A user as {@code GET /v1/me} describes them (spec section 6.1).
 *
 * @param id the user's id
 * @param username the login
 * @param name the full name
 * @param email the email address, or null
 * @param language the user's language, such as {@code en}, or null
 * @param admin true for a member of the {@code admin} group
 */
public record UserView(long id, String username, String name, String email, String language, boolean admin) {
}
