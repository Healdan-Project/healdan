// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

/**
 * Why a temporary password is shown to the administrator rather than emailed.
 */
public enum NotEmailed {

	/** Emailing it wasn't asked for */
	NOT_ASKED,

	/** Outgoing email isn't set up, or the server doesn't send email now */
	NOT_SET_UP,

	/** The person has no email address */
	NO_EMAIL,

	/** The email couldn't be sent, or the mail server didn't take it in time; it may still arrive */
	NOT_TAKEN
}
