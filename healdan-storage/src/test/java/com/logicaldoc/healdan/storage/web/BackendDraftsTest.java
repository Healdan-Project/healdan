// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.healdan.storage.web.BackendDrafts.Draft;

/**
 * A backend's settings read from the Storage tab ({@link BackendDrafts}), without a server: the draft keeps the
 * secret typed for the save, and shows it nowhere else.
 */
public class BackendDraftsTest {

	private static final String SECRET = "s3cr3t-in-a-draft";

	@Test
	public void testToStringOmitsTheSecret() throws Exception {
		GUIStorageBackend typed = new GUIStorageBackend();
		typed.setKind(GUIStorageBackend.S3);
		typed.setName("Cloud");
		typed.setEndpoint("https://s3.example.test");
		typed.setRegion("test-1");
		typed.setBucket("healdan-tests");
		typed.setPrefix("healdan/");
		typed.setAccessKey("AKTEST");

		Draft draft = BackendDrafts.draft(typed, " " + SECRET + " ", null);

		assertEquals(SECRET, draft.secret());
		String text = draft.toString();
		assertFalse(text, text.contains(SECRET));
		// What it is and where, to find it in a log
		assertTrue(text, text.contains("Cloud"));
		assertTrue(text, text.contains("healdan-tests"));
	}
}
