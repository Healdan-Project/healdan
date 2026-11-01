package com.logicaldoc.healdan.api.model;

/**
 * The body of {@code POST /v1/auth/tokens} (spec section 5.2): a password
 * sign-in, or the helper's one-time code from a sign-in through a provider
 * (M4 spec section 6.2), never both.
 *
 * @param username the login, for a password sign-in
 * @param password the password, for a password sign-in
 * @param name what the device is called, such as "Word on MacBook"; blank
 *        for "Unnamed device"
 * @param device the computer signing in, or null; signing in again with the
 *        same device keeps the check-outs it holds (M3 spec section 9.4)
 * @param code the one-time code the sign-in handed the helper's loopback
 *        address
 * @param codeVerifier the helper's PKCE verifier, whose S256 challenge it
 *        started the sign-in with
 * @param redirectUri the helper's loopback address, exactly as it started
 *        the sign-in with
 */
public record SignIn(String username, String password, String name, DeviceRef device, String code,
		String codeVerifier, String redirectUri) {

	@Override
	public String toString() {
		// The password, code and verifier are secret
		return "SignIn[username=" + username + ", name=" + name + ", device=" + device + "]";
	}
}
