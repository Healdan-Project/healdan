// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.time.Instant;

/**
 * A person's page (Admin app spec section 8.7), {@code openapi-admin.yaml}'s {@code User}: their row, with what their
 * page shows besides, and which of its changes the server would refuse now.
 *
 * @param row what the Users list shows of them
 * @param language the language their screens and emails are in, such as {@code en}: the default language
 *        ({@link UserOptions#defaultLanguage()}) when the account has none
 * @param timeZone their time zone, an IANA name such as {@code Europe/London}; null for none, the server's
 * @param created when the account was made
 * @param passwordChanged when their password last changed; null for an account that signs in through a provider
 * @param mustChangePassword whether they have to change their password at their next sign-in: it was set by an
 *        administrator, or it's older than the password rules allow; false for an account that signs in through a
 *        provider
 * @param checkedOut how many documents they have checked out, as {@code checkouts} lists them
 * @param mattersOwned how many matters they own that are live: not deleted, and not in the Trash
 * @param refusals why the server would refuse each of the page's changes now, for its buttons
 */
public record Person(PersonRow row, String language, String timeZone, Instant created, Instant passwordChanged,
		boolean mustChangePassword, int checkedOut, long mattersOwned, Refusals refusals) {

	/**
	 * The sentence each change of a person's page would be refused with, read when the page is; null where it would
	 * be allowed. An administrator's change is checked again when it's made.
	 *
	 * @param disable Disable's: the {@code admin} account, one's own, or the last enabled administrator
	 * @param enable Enable's; null today, as nothing refuses it
	 * @param resetPassword Reset Password's: an account that signs in through a provider, or one's own
	 * @param leaveAdmin taking them out of the {@code admin} group: the {@code admin} account, oneself, or the last
	 *        enabled administrator; null when they aren't in it
	 */
	public record Refusals(String disable, String enable, String resetPassword, String leaveAdmin) {
	}
}
