package com.logicaldoc.healdan.api.model;

/**
 * The computer that holds a check-out, shown to the user who holds it (M3
 * spec section 9.4).
 *
 * @param id its device id
 * @param name what it's called: the name of the token it last wrote
 *        through
 * @param app the app the check-out named, such as {@code LibreOffice}, or
 *        null
 */
public record DeviceView(String id, String name, String app) {
}
