package com.logicaldoc.healdan.api.model;

/**
 * One field of a {@code validation} problem (spec section 4.4).
 *
 * @param field the field's name, as the request names it
 * @param code what's wrong, such as {@code required}
 * @param detail what's wrong, for the user
 */
public record FieldError(String field, String code, String detail) {
}
