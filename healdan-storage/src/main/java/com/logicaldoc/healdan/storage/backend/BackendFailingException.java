// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.IOException;

/** A backend that can't be written to: its marker is missing or names another place, or it refuses. */
public class BackendFailingException extends IOException {

	private static final long serialVersionUID = 1L;

	public BackendFailingException(String message) {
		super(message);
	}

	public BackendFailingException(String message, Throwable cause) {
		super(message, cause);
	}
}
