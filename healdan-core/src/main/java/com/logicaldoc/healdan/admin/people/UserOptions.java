// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.util.List;

/**
 * What New User and Edit offer (Admin app spec section 8.7), and what they take.
 *
 * @param languages the languages a person's screens can be in, as the tenant turns them on, by name
 * @param defaultLanguage the language a person gets unless another is chosen, one of {@code languages}
 * @param timeZones the time zones a person can have, the IANA region ids such as {@code Europe/London}, sorted
 * @param providers the sign-in providers people can sign in through now, in the order of their buttons; none when
 *        no provider is on. While one is, New User says that people who sign in through it don't need adding
 * @param outgoingEmail whether outgoing email is set up, so a temporary password can be emailed
 */
public record UserOptions(List<Language> languages, String defaultLanguage, List<String> timeZones,
		List<SignsInWith.Provider> providers, boolean outgoingEmail) {

	/**
	 * A language
	 *
	 * @param code its code, such as {@code en} or {@code pt_BR}
	 * @param name its name in English, such as {@code Portuguese (Brazil)}
	 */
	public record Language(String code, String name) {
	}

	public UserOptions {
		languages = List.copyOf(languages);
		timeZones = List.copyOf(timeZones);
		providers = List.copyOf(providers);
	}
}
