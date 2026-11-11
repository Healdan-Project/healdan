// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * The old UI's way back to the User app (M6 spec 8.6): Open in Healdan, a
 * one-line hook in upstream's {@code MainMenu} (spec 14.1).
 */
public class HealdanAppLinkTest {

	@Test
	public void testGoesToTheApp() {
		assertEquals("http://localhost:8080/app/", HealdanAppLink.address("http://localhost:8080/"));
		assertEquals("Open in Healdan", HealdanAppLink.LABEL);
	}

	// The hook is a line in an upstream class: a sync that drops or moves it fails here, not in the smoke test
	@Test
	public void testMainMenuAddsTheButtonBeforeSignOut() throws IOException {
		String menu = Files
				.readString(Path.of("src/main/java/com/logicaldoc/gui/frontend/client/menu/MainMenu.java"));
		int hook = menu.indexOf("HealdanAppLink.add(this);");
		assertTrue("MainMenu no longer adds Open in Healdan", hook >= 0);
		assertTrue("Open in Healdan is no longer before Sign Out", hook < menu.indexOf("addLogoutButton();"));
	}
}
