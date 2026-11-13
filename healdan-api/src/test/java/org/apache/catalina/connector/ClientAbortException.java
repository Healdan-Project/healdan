// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package org.apache.catalina.connector;

import java.io.IOException;

/**
 * A stand-in for Tomcat's exception when the client has gone away, which
 * Tomcat's connector throws as a request's answer is written or its body
 * read. healdan-api doesn't compile against Tomcat, so {@code ProblemMapper}
 * knows it by name, and the tests throw this one.
 */
public class ClientAbortException extends IOException {

	private static final long serialVersionUID = 1L;

	public ClientAbortException(Throwable cause) {
		super(cause);
	}
}
