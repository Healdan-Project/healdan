package com.logicaldoc.healdan.api.model;

/**
 * The computer a client signs in from (M3 spec section 9.4).
 *
 * @param id the computer's device id: 1 to 64 letters, digits and hyphens,
 *        such as the random UUID the helper keeps
 */
public record DeviceRef(String id) {
}
