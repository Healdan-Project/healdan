// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

/**
 * Where the admin request this thread is serving came from, for the
 * administration record (Admin app spec section 9.3). The API's admin filter
 * sets it once the request may go on, and the API's servlet clears it before
 * and after every request, once the response is written: a streamed body,
 * such as an export's, still has it, and a thread that serves one request
 * after another never hands an address on. Outside an admin request, such as
 * in the Classic Interface, there's none.
 */
public final class AdminContext {

	private static final ThreadLocal<String> ADDRESS = new ThreadLocal<>();

	private AdminContext() {
	}

	/**
	 * @param address the client's address, as the server believes it: the
	 *        request's remote address, which Tomcat has already taken from a
	 *        trusted proxy's {@code X-Forwarded-For}
	 */
	public static void set(String address) {
		ADDRESS.set(address);
	}

	/**
	 * @return the current admin request's address, or null outside one
	 */
	public static String address() {
		return ADDRESS.get();
	}

	/**
	 * Forgets the address of this thread.
	 */
	public static void clear() {
		ADDRESS.remove();
	}
}
