// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.security.user.UserEvent;

/**
 * The Audit Trail's words (ruling P5-R14): one table, in US English, for every event the history, the administration
 * record and the storage record hold today; an event with no words shows itself.
 */
public class AuditWordsTest {

	/** The modules whose main sources record the administration record's rows */
	private static final List<String> RECORDING = List.of("healdan-core", "healdan-storage", "healdan-api",
			"healdan-web");

	/** An action as the administration record names one: a kind and a verb, lower case, with hyphens */
	private static final Pattern ACTION = Pattern.compile("\"([a-z]+(?:-[a-z]+)*\\.[a-z]+(?:-[a-z]+)*)\"");

	/**
	 * @return every action the modules record: each action-shaped text in a main source file that makes an
	 *         {@code AdminEvent}, so one a helper in the file passes on counts too
	 */
	static Set<String> recordedActions() throws IOException {
		Path modules = Path.of("").toAbsolutePath().getParent();
		Set<String> actions = new TreeSet<>();
		for (String module : RECORDING) {
			Path sources = modules.resolve(module).resolve("src/main/java");
			assertTrue(sources.toString(), Files.isDirectory(sources));
			try (Stream<Path> files = Files.walk(sources)) {
				for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
					String text = Files.readString(file, StandardCharsets.ISO_8859_1);
					if (!text.contains("new AdminEvent("))
						continue;
					Matcher matcher = ACTION.matcher(text);
					while (matcher.find())
						actions.add(matcher.group(1));
				}
			}
		}
		return actions;
	}

	@Test
	public void testTheRecordedActionsAreFound() throws IOException {
		Set<String> actions = recordedActions();
		// Each area's, so a scan that found less than it should fails here
		for (String action : List.of("client.renamed", "store.created", "backend.recovery-key-shown",
				"storage.settings-changed", "user.password-reset", "group.member-added", "error.resolved",
				"job.stop-requested", "folder.access-applied", "template.deleted"))
			assertTrue(action + " in " + actions, actions.contains(action));
		assertTrue(actions.toString(), actions.size() >= 53);
	}

	@Test
	public void testEveryEventHasWords() throws IOException {
		List<String> events = new ArrayList<>(recordedActions());
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

	/** An account's events but its sign-ins read true whoever made them, as Who is whoever did (ruling P5-R30) */
	@Test
	public void testAnAccountsEventsAreTrueWhoeverMadeThem() {
		assertEquals("Account disabled", AuditWords.what("event.user.disabled"));
		assertEquals("Password changed", AuditWords.what("event.user.passwordchanged"));
		assertEquals("Account created", AuditWords.what("event.user.created"));
		assertEquals("Account deleted", AuditWords.what("event.user.deleted"));
		assertEquals("Sign-in-as list changed", AuditWords.what("event.user.impersonators.changed"));
	}

	/** Run Now and Stop are requests: a run may do nothing, and a stop comes at the run's next check */
	@Test
	public void testAJobsRequestsReadAsRequests() {
		assertEquals("Asked a job to stop", AuditWords.what("job.stop-requested"));
		assertEquals("Asked a job to run", AuditWords.what("job.run-requested"));
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
