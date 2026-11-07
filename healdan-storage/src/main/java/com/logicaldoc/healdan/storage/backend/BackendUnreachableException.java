// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

/**
 * A backend that can't be reached: an outage, which isn't a failure of the file being moved, so retries aren't
 * spent on it.
 */
public class BackendUnreachableException extends BackendFailingException {

	private static final long serialVersionUID = 1L;

	public BackendUnreachableException(String message) {
		super(message);
	}

	public BackendUnreachableException(String message, Throwable cause) {
		super(message, cause);
	}
}
