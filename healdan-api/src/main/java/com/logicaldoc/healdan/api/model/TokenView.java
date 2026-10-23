package com.logicaldoc.healdan.api.model;

import java.util.Date;

/**
 * One of the caller's device tokens, without the token itself (spec section
 * 5.2).
 *
 * @param id the token's id
 * @param name what the device is called
 * @param created when it signed in
 * @param lastUsed when it was last used, to within a few minutes
 * @param expiresAt when it stops working at the latest
 * @param current true for the token making the request
 */
public record TokenView(long id, String name, Date created, Date lastUsed, Date expiresAt, boolean current) {
}
