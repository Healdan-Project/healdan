// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.util.function.Consumer;

/**
 * For other modules' tests: makes the nightly job fail on a member, as
 * {@code TrashDestroyerTest} does with the job's own hook
 */
public final class TrashTestHooks {

	private TrashTestHooks() {
	}

	/**
	 * @param hook told of each member just before it is destroyed; one that
	 *        throws fails it
	 */
	public static void beforeDestroy(Consumer<TrashMember> hook) {
		TrashDestroyer.get().beforeDestroy = hook;
	}

	/**
	 * Puts the job's hook back to doing nothing
	 */
	public static void reset() {
		TrashDestroyer.get().beforeDestroy = member -> {
		};
	}
}
