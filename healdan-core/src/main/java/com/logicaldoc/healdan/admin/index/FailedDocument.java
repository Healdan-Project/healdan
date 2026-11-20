// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.time.Instant;

/**
 * A document that couldn't be indexed (Admin app spec sections 8.9 and 9.6): it waits to be indexed, and its latest
 * index event is the error.
 *
 * @param id the document's id
 * @param docNumber its doc #; null if it has none
 * @param name its file name
 * @param matter the matter stamped on it; null if none is, or the stamps name no live matter
 * @param reason why it couldn't be indexed, as the error's event says; null if it says nothing
 * @param when when it last couldn't be; null if the event has no time
 */
public record FailedDocument(long id, String docNumber, String name, Matter matter, String reason, Instant when) {

	/**
	 * @param id the matter's id
	 * @param number its number with its client's, such as {@code 1001.1}
	 * @param name its name
	 */
	public record Matter(long id, String number, String name) {
	}
}
