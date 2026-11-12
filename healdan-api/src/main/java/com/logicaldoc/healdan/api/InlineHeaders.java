// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import jakarta.ws.rs.core.Response.ResponseBuilder;

/**
 * What every answer that serves stored bytes inline carries (M6 R57): {@code nosniff}, and a
 * {@code Content-Security-Policy} of {@code sandbox}, so that going to one directly, an SVG picture say, runs no
 * script.
 */
final class InlineHeaders {

	/** The policy of such an answer */
	static final String SANDBOX = "sandbox";

	private InlineHeaders() {
	}

	/**
	 * @param response an answer that serves stored bytes inline
	 * @return it, with the headers added
	 */
	static ResponseBuilder apply(ResponseBuilder response) {
		return response.header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", SANDBOX);
	}
}
