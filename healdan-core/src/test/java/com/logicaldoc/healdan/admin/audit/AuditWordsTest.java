// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.security.user.UserEvent;

/**
 * The Audit Trail's words (ruling P5-R14): one table, in US English, for every event the history, the administration
 * record and the storage record hold today; an event with no words shows itself.
 */
public class AuditWordsTest {

	/** Every action the administration record holds, Phase 5's own included */
	static final List<String> ACTIONS = List.of("client.created", "client.renamed", "matter.created",
			"matter.updated", "matter.moved", "matter.deleted", "folder.access-changed", "folder.access-applied",
			"folder.created", "folder.renamed", "folder.moved", "folder.properties-changed", "folder.deleted",
			"search-folder.created", "search-folder.rule-changed", "template.created", "template.renamed",
			"template.deleted", "store.created", "store.access-changed", "store.renamed", "store.deleted",
			"store.restored", "store.backend-changed", "backend.added", "backend.changed", "backend.removed",
			"backend.recovery-key-shown", "backend.recovery-key-confirmed", "backend.recovery-key-entered",
			"storage.stuck-retried", "storage.settings-changed", "user.created", "user.changed", "user.disabled",
			"user.enabled", "user.groups-changed", "user.block-cleared", "user.password-reset", "user.signed-out",
			"user.device-revoked", "group.created", "group.changed", "group.deleted", "group.member-added",
			"group.member-removed", "error.resolved", "error.reopened", "job.run-requested", "job.stop-requested",
			"job.enabled", "job.disabled", "job.schedule-changed");

	@Test
	public void testEveryEventHasWords() {
		List<String> events = new ArrayList<>(ACTIONS);
		for (DocumentEvent event : DocumentEvent.values())
			events.add(event.toKey());
		for (FolderEvent event : FolderEvent.values())
			events.add(event.toKey());
		for (UserEvent event : UserEvent.values())
			events.add(event.toKey());
		events.add("key-shown");
		events.add("key-entered");
		// The User app's previews record it
		assertTrue(events.contains("event.viewed"));

		for (String event : events) {
			assertTrue(event, AuditWords.has(event));
			String what = AuditWords.what(event);
			assertFalse(event, what.isBlank());
			assertFalse(event + ": " + what, what.equals(event));
			// Sentence case: a capital first, and the rest as it is
			assertTrue(event + ": " + what, Character.isUpperCase(what.charAt(0)));
			assertFalse(event + ": " + what, what.endsWith("."));
		}
	}

	@Test
	public void testTheMockupsWords() {
		assertEquals("Downloaded", AuditWords.what("event.downloaded"));
		assertEquals("Saved a new version", AuditWords.what("event.checkedin"));
		assertEquals("Filed", AuditWords.what("event.stored"));
		assertEquals("Viewed", AuditWords.what("event.viewed"));
		assertEquals("Signed in", AuditWords.what("event.user.login"));
		assertEquals("Failed to sign in", AuditWords.what("event.user.login.failed"));
		assertEquals("Created a matter", AuditWords.what("matter.created"));
		assertEquals("Renamed a client", AuditWords.what("client.renamed"));
		assertEquals("Showed a recovery key", AuditWords.what("key-shown"));
		assertEquals("Showed a recovery key", AuditWords.what("backend.recovery-key-shown"));
	}

	@Test
	public void testAnEventWithNoWordsShowsItself() {
		assertFalse(AuditWords.has("event.workflow.start"));
		assertEquals("event.workflow.start", AuditWords.what("event.workflow.start"));
		assertEquals("", AuditWords.what(""));
	}

	@Test
	public void testKinds() {
		assertEquals(List.of("Sign-In", "Document", "Folder", "User", "Administration"),
				AuditRow.KINDS.stream().map(AuditWords::kind).toList());
	}
}
