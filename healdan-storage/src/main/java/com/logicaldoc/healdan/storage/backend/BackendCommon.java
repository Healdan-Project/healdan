// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** What every backend kind shares: the marker's text, and MD5 for the checksums its writes carry */
public final class BackendCommon {

	private BackendCommon() {
	}

	/**
	 * @param installId this install's id
	 * @param backendId the backend's id
	 * @return what a backend's marker ({@link LocalBackend#MARKER}) holds: one line naming both
	 */
	static String marker(String installId, long backendId) {
		return installId + " " + backendId + "\n";
	}

	/** @return a new MD5 digest, which every JDK has */
	public static MessageDigest md5() {
		try {
			return MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** @return the MD5 of the bytes */
	public static byte[] md5(byte[] bytes) {
		return md5().digest(bytes);
	}
}
