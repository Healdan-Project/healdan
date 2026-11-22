// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.util.Objects;

/**
 * How a person signs in: with a password, or through the sign-in provider their account is linked to. An account
 * linked to a provider has no password here, and its names and email may be overwritten at its next sign-in.
 *
 * @param kind {@link Kind#PASSWORD} or {@link Kind#PROVIDER}
 * @param provider the provider the account is linked to; null for a password account
 */
public record SignsInWith(Kind kind, Provider provider) {

	/** How a person signs in */
	public enum Kind {

		PASSWORD("password"), PROVIDER("provider");

		private final String code;

		Kind(String code) {
			this.code = code;
		}

		/**
		 * @return the kind as the contract names it
		 */
		public String code() {
			return code;
		}
	}

	/**
	 * A sign-in provider
	 *
	 * @param id its id, as in its sign-in address
	 * @param label the label of its button; its id when it has none or is gone
	 */
	public record Provider(String id, String label) {
	}

	public SignsInWith {
		Objects.requireNonNull(kind, "kind");
		if ((kind == Kind.PROVIDER) != (provider != null))
			throw new IllegalArgumentException("Only a provider account names its provider");
	}

	/** A password account */
	public static final SignsInWith PASSWORD = new SignsInWith(Kind.PASSWORD, null);
}
