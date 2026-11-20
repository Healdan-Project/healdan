// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.logicaldoc.core.security.Permission;

/**
 * A row's permissions as a level (Admin app spec section 8.4): an exact match, and Custom for anything else.
 */
public class AccessLevelTest {

	private static final Set<Permission> READ_ONLY = EnumSet.of(Permission.READ, Permission.PREVIEW,
			Permission.DOWNLOAD, Permission.PRINT, Permission.EMAIL);

	private static final Set<Permission> READ_WRITE = EnumSet.of(Permission.READ, Permission.PREVIEW,
			Permission.DOWNLOAD, Permission.PRINT, Permission.EMAIL, Permission.WRITE, Permission.ADD,
			Permission.RENAME, Permission.MOVE, Permission.DELETE);

	private static final List<AccessLevel> NAMED = List.of(AccessLevel.READ_ONLY, AccessLevel.READ_WRITE,
			AccessLevel.FULL_ACCESS, AccessLevel.CAN_SEE);

	@Test
	public void testEachLevelsPermissions() {
		assertEquals(READ_ONLY, AccessLevel.READ_ONLY.permissions());
		assertEquals(READ_WRITE, AccessLevel.READ_WRITE.permissions());
		assertEquals(Permission.all(), AccessLevel.FULL_ACCESS.permissions());
		assertEquals(25, AccessLevel.FULL_ACCESS.permissions().size());
		assertEquals(Set.of(Permission.READ), AccessLevel.CAN_SEE.permissions());
		// Custom is any other set, so it has none of its own
		assertThrows(UnsupportedOperationException.class, AccessLevel.CUSTOM::permissions);
	}

	@Test
	public void testEachSetMapsBothWays() {
		for (AccessLevel level : NAMED) {
			assertEquals(level, AccessLevel.of(level.permissions()));
			// Whatever kind of set it comes in
			assertEquals(level, AccessLevel.of(new HashSet<>(level.permissions())));
		}
	}

	@Test
	public void testOneFlagMoreOrFewerIsCustom() {
		for (AccessLevel level : NAMED)
			for (Permission flag : Permission.values()) {
				Set<Permission> changed = EnumSet.noneOf(Permission.class);
				changed.addAll(level.permissions());
				if (!changed.remove(flag))
					changed.add(flag);
				assertEquals(level + " with " + flag + " flipped", AccessLevel.CUSTOM, AccessLevel.of(changed));
			}
		assertEquals(AccessLevel.CUSTOM, AccessLevel.of(EnumSet.noneOf(Permission.class)));
		assertEquals(AccessLevel.CUSTOM, AccessLevel.of(EnumSet.of(Permission.READ, Permission.WRITE)));
	}

	// The demo firm's folders grant Read Only's five and the five that change things
	@Test
	public void testTheDemoFirmsTenFlagRowIsReadWrite() {
		Set<Permission> demo = Permission.match("READ", "PREVIEW", "DOWNLOAD", "PRINT", "EMAIL", "WRITE", "ADD",
				"RENAME", "MOVE", "DELETE");
		assertEquals(10, demo.size());
		assertEquals(AccessLevel.READ_WRITE, AccessLevel.of(demo));
	}

	@Test
	public void testThePermissionsCantBeChanged() {
		assertThrows(UnsupportedOperationException.class,
				() -> AccessLevel.READ_ONLY.permissions().add(Permission.WRITE));
		assertEquals(READ_ONLY, AccessLevel.READ_ONLY.permissions());
	}
}
