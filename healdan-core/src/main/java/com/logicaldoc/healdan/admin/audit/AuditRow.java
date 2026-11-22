// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.logicaldoc.healdan.admin.AdminEvent.Change;

/**
 * A row of the Audit Trail (Admin app spec section 8.8), from one of its five sources.
 *
 * @param id the row's own, which no other row of the Audit Trail has: its source's letter and its id, as {@code d123}
 * @param when when it was done
 * @param who the person's name, read now; the username the row kept when no account has the row's user id; null when
 *        the row names no one
 * @param kind one of {@link #KINDS}
 * @param event what was done, as the row keeps it: LogicalDOC's history event, the administration record's action,
 *        or the storage record's
 * @param what the same in US English ({@link AuditWords}); {@code event} itself when there are no words for it
 * @param detail one line more, the server's; null when there's nothing more to say
 * @param from the address it came from; null when the row keeps none
 * @param docNumber a document row's doc #; null for other rows, and when the document has none
 * @param folderId the folder of a document or folder row, and the folder or search folder an administration row is
 *        aimed at; null otherwise
 * @param changes an administration row's changed fields, by name in order, a secret's as {@link Change#secret()};
 *        null for other rows and for an administration row that changed no field
 */
public record AuditRow(String id, Instant when, String who, String kind, String event, String what, String detail,
		String from, Long docNumber, Long folderId, Map<String, Change> changes) {

	/** An account's sign-ins, sign-outs, sessions that timed out, and failed sign-ins */
	public static final String SIGN_IN = "sign-in";

	/** What was done to a document */
	public static final String DOCUMENT = "document";

	/** What was done to a folder */
	public static final String FOLDER = "folder";

	/** The rest of an account's history */
	public static final String USER = "user";

	/** The administration record's rows, and the storage record's */
	public static final String ADMINISTRATION = "administration";

	/** Every kind, in the contract's order */
	public static final List<String> KINDS = List.of(SIGN_IN, DOCUMENT, FOLDER, USER, ADMINISTRATION);

	public AuditRow {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(when, "when");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(what, "what");
		changes = changes == null || changes.isEmpty() ? null
				: Collections.unmodifiableSortedMap(new TreeMap<>(changes));
	}
}
