// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.time.Instant;

/**
 * A document a person has checked out, as their page lists it. An administrator reads every document, so the list
 * holds every check-out of theirs the API shows: documents with a doc # in a Store.
 *
 * @param docNumber the document's doc #
 * @param fileVersion its file's version, as the User app's Version column shows it, such as {@code 1.2}
 * @param title its name, as the User app's Title column shows it
 * @param matter the matter it's in; null for one in a Store's folder outside any matter
 * @param since when they checked it out; null when that isn't recorded
 * @param device the name of the device that holds the check-out; null when none does, or the device has no name
 * @param app the app it's open in on that device, such as {@code Word}; null when no device holds it
 */
public record CheckedOut(long docNumber, String fileVersion, String title, Matter matter, Instant since,
		String device, String app) {

	/**
	 * A matter
	 *
	 * @param id the matter's id
	 * @param number its client's number and its own, such as {@code 1001.1}
	 * @param name its own name
	 */
	public record Matter(long id, String number, String name) {
	}
}
