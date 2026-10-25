package com.logicaldoc.healdan.api.model;

/**
 * The body of {@code POST /v1/auth/tokens} (spec section 5.2).
 *
 * @param username the login
 * @param password the password
 * @param name what the device is called, such as "Word on MacBook"; blank
 *        for "Unnamed device"
 * @param device the computer signing in, or null; signing in again with the
 *        same device keeps the check-outs it holds (M3 spec section 9.4)
 */
public record SignIn(String username, String password, String name, DeviceRef device) {
}
