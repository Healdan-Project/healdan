package com.logicaldoc.healdan.api.model;

import java.util.Date;

/**
 * What a sign-in returns (spec section 5.2). The token is shown only here.
 *
 * @param token the device token, to send as {@code Authorization: Bearer}
 * @param id the token's id, to revoke it by
 * @param name what the device is called
 * @param expiresAt when the token stops working at the latest
 * @param user who signed in
 */
public record SignedIn(String token, long id, String name, Date expiresAt, UserView user) {
}
