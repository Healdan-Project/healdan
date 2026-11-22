// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import java.util.HashMap;
import java.util.Map;

/**
 * The Audit Trail's words (ruling P5-R14): what each event a row keeps says in US English, in sentence case, as the
 * Audit Trail and its export show it. One table for LogicalDOC's history of documents, folders and accounts, the
 * administration record's actions and the storage record's; an event it doesn't hold shows itself.
 */
public final class AuditWords {

	private static final Map<String, String> WHAT = new HashMap<>();

	private static final Map<String, String> KINDS = Map.of(AuditRow.SIGN_IN, "Sign-In", AuditRow.DOCUMENT,
			"Document", AuditRow.FOLDER, "Folder", AuditRow.USER, "User", AuditRow.ADMINISTRATION, "Administration");

	static {
		// A document's history (LogicalDOC's DocumentEvent)
		words("event.stored", "Filed");
		words("event.changed", "Changed the properties");
		words("event.checkedin", "Saved a new version");
		words("event.checkedout", "Checked out");
		words("event.immutable", "Made immutable");
		words("event.renamed", "Renamed");
		words("event.downloaded", "Downloaded");
		words("event.indexed", "Indexed");
		words("event.indexed.error", "Failed to index");
		words("event.moved", "Moved");
		words("event.locked", "Locked");
		words("event.unlocked", "Unlocked");
		words("event.archived", "Archived");
		words("event.deleted", "Deleted");
		words("event.resource.deleted", "Deleted a stored file");
		words("event.sent", "Sent by email");
		words("event.barcoded", "Read the barcodes");
		words("event.zonalocrd", "Read by zonal OCR");
		words("event.workflowstatus", "Changed the workflow status");
		words("event.workflowappended", "Added to a workflow");
		words("event.shortcut.stored", "Made a shortcut");
		words("event.shortcut.moved", "Moved a shortcut");
		words("event.shortcut.deleted", "Deleted a shortcut");
		words("event.viewed", "Viewed");
		words("event.restored", "Restored");
		words("event.new.note", "Added a note");
		words("event.signed", "Signed");
		words("event.exportpdf", "Exported as PDF");
		words("event.exported", "Exported");
		words("event.added.to.calevent", "Added to a calendar event");
		words("event.removed.from.calevent", "Removed from a calendar event");
		words("event.subscribed", "Subscribed");
		words("event.stamped", "Stamped");
		words("event.ticket.created", "Made a download link");
		words("event.password.protected", "Protected with a password");
		words("event.password.unprotected", "Took off the password");
		words("event.rating.new", "Rated");
		words("event.converted", "Converted");
		words("event.version.deleted", "Deleted a version");
		words("event.version.replaced", "Replaced a version");
		words("event.compared", "Compared");
		words("event.copyed", "Copied");
		words("event.esigned", "Signed electronically");
		words("event.form.submitted", "Submitted a web form");
		words("event.form.edited", "Edited a web form");
		words("event.reading.confirmed", "Confirmed reading");
		words("event.reading.requested", "Asked for a reading confirmation");
		words("event.permission", "Changed permissions");
		words("event.embedded", "Embedded");
		words("event.filled", "Filled");
		words("event.summarized", "Summarized");

		// A folder's history (FolderEvent)
		words("event.folder.created", "Created a folder");
		words("event.folder.renamed", "Renamed a folder");
		words("event.folder.permission", "Changed permissions");
		words("event.folder.deleted", "Deleted a folder");
		words("event.folder.moved", "Moved a folder");
		words("event.folder.changed", "Changed a folder's properties");
		words("event.folder.subfolder.created", "Created a subfolder");
		words("event.folder.subfolder.renamed", "Renamed a subfolder");
		words("event.folder.subfolder.permission", "Changed a subfolder's permissions");
		words("event.folder.subfolder.deleted", "Deleted a subfolder");
		words("event.folder.subfolder.changed", "Changed a subfolder's properties");
		words("event.folder.subfolder.moved", "Moved a subfolder");
		words("event.folder.subfolder.restored", "Restored a subfolder");
		words("event.folder.restored", "Restored a folder");
		words("event.folder.exported", "Exported a folder");
		words("event.folder.subscribed", "Subscribed to a folder");
		words("event.folder.quota.overthreshold", "Went over the storage threshold");
		words("event.folder.alias.created", "Made a folder alias");
		words("event.folder.document.destroyed", "Destroyed a document");

		// An account's history (UserEvent)
		words("event.user.login", "Signed in");
		words("event.user.login.failed", "Failed to sign in");
		words("event.user.logout", "Signed out");
		words("event.user.timeout", "Session timed out");
		words("event.user.passwordchanged", "Changed the password");
		words("event.user.twofachanged", "Changed two-factor sign-in");
		words("event.user.deleted", "Deleted the account");
		words("event.user.file.conversion", "Converted a file");
		words("event.user.message.received", "Received a message");
		words("event.user.created", "Created the account");
		words("event.user.updated", "Changed the account");
		words("event.user.disabled", "Disabled the account");
		words("event.user.enabled", "Enabled the account");
		words("event.user.newapikey", "Made an API key");
		words("event.user.search", "Searched");
		words("event.user.ai.query", "Asked the AI");
		words("event.user.legal.confirmed", "Confirmed a legal notice");
		words("event.user.impersonators.changed", "Changed who can sign in as the account");
		words("event.user.impersonation", "Signed in as someone else");
		words("event.user.quota.overthreshold", "Went over the storage threshold");
		words("event.user.access", "Opened the application");
		words("event.user.access.ticket", "Used a download link");

		// The administration record: Matters
		words("client.created", "Created a client");
		words("client.renamed", "Renamed a client");
		words("matter.created", "Created a matter");
		words("matter.updated", "Changed a matter");
		words("matter.moved", "Moved a matter");
		words("matter.deleted", "Deleted a matter");
		words("folder.access-changed", "Changed who can use a folder");
		words("folder.access-applied", "Applied a folder's access to its subfolders");
		words("folder.created", "Created a folder");
		words("folder.renamed", "Renamed a folder");
		words("folder.moved", "Moved a folder");
		words("folder.properties-changed", "Changed a folder's properties");
		words("folder.deleted", "Deleted a folder");
		words("search-folder.created", "Created a search folder");
		words("search-folder.rule-changed", "Changed a search folder's rule");
		words("template.created", "Created a workspace template");
		words("template.renamed", "Renamed a workspace template");
		words("template.deleted", "Deleted a workspace template");
		// Stores
		words("store.created", "Created a Store");
		words("store.access-changed", "Changed who can see a Store");
		words("store.renamed", "Renamed a Store");
		words("store.deleted", "Deleted a Store");
		words("store.restored", "Restored a Store");
		// Storage
		words("store.backend-changed", "Changed a Store's backend");
		words("backend.added", "Added a backend");
		words("backend.changed", "Changed a backend");
		words("backend.removed", "Removed a backend");
		words("backend.recovery-key-shown", "Showed a recovery key");
		words("backend.recovery-key-confirmed", "Confirmed a recovery key");
		words("backend.recovery-key-entered", "Entered a recovery key");
		words("storage.stuck-retried", "Retried stuck files");
		words("storage.settings-changed", "Changed the storage settings");
		// People
		words("user.created", "Created a user");
		words("user.changed", "Changed a user");
		words("user.disabled", "Disabled a user");
		words("user.enabled", "Enabled a user");
		words("user.groups-changed", "Changed a user's groups");
		words("user.block-cleared", "Cleared a sign-in block");
		words("user.password-reset", "Reset a password");
		words("user.signed-out", "Signed a user out everywhere");
		words("user.device-revoked", "Revoked a device");
		words("group.created", "Created a group");
		words("group.changed", "Changed a group");
		words("group.deleted", "Deleted a group");
		words("group.member-added", "Added a member to a group");
		words("group.member-removed", "Removed a member from a group");
		// Monitoring
		words("error.resolved", "Marked an error resolved");
		words("error.reopened", "Marked an error unresolved");
		words("job.run-requested", "Ran a job");
		words("job.stop-requested", "Stopped a job");
		words("job.enabled", "Enabled a job");
		words("job.disabled", "Disabled a job");
		words("job.schedule-changed", "Changed a job's schedule");

		// The storage record, from the Classic Interface's Storage tab
		words(AuditFeed.KEY_SHOWN, "Showed a recovery key");
		words(AuditFeed.KEY_ENTERED, "Entered a recovery key");
	}

	private AuditWords() {
	}

	private static void words(String event, String what) {
		if (WHAT.put(event, what) != null)
			throw new IllegalStateException("Two entries for " + event);
	}

	/**
	 * @param event an event as a row keeps it
	 * @return what it says, in US English; the event itself when there are no words for it
	 */
	public static String what(String event) {
		return WHAT.getOrDefault(event, event);
	}

	/**
	 * @param kind one of {@link AuditRow#KINDS}
	 * @return its label, as the Audit Trail and its export show it, such as {@code Sign-In}
	 */
	public static String kind(String kind) {
		return KINDS.getOrDefault(kind, kind);
	}

	/** @return whether the event has words of its own */
	static boolean has(String event) {
		return WHAT.containsKey(event);
	}
}
