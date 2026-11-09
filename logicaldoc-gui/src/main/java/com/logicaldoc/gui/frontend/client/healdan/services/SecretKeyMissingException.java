// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.services;

import com.logicaldoc.gui.common.client.ServerException;

/**
 * Refuses a typed client secret or S3 secret while Healdan's secret key file
 * is missing and stored values are sealed with it (ruling R37). Nothing was
 * saved: the Sign-In or Storage tab asks the administrator whether to make a
 * new key, and saves again with {@code newKey} if they do.
 */
public class SecretKeyMissingException extends ServerException {

	private static final long serialVersionUID = 1L;

	/** How many stored values are sealed with the missing key */
	private long sealed;

	public SecretKeyMissingException() {
		super();
	}

	public SecretKeyMissingException(String message, long sealed) {
		super(message);
		this.sealed = sealed;
	}

	public long getSealed() {
		return sealed;
	}
}
