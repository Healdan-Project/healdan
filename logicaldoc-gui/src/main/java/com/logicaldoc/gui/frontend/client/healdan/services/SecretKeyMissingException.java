package com.logicaldoc.gui.frontend.client.healdan.services;

import com.logicaldoc.gui.common.client.ServerException;

/**
 * Refuses a typed client secret while Healdan's secret key file is missing
 * and stored values are sealed with it (ruling R37). Nothing was saved: the
 * Sign-In tab asks the administrator whether to make a new key, and saves
 * again with {@code newKey} if they do.
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
