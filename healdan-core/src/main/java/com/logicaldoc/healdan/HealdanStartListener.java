// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan;

/**
 * Told once Healdan's migrations and seeds have run at a real start, beside M1.5's preview worker and M5's email-index
 * worker ({@link HealdanStartup}): a bean that implements it starts its own threads here. Every test's {@code setUp}
 * calls {@link HealdanStartup#run()} itself, which tells none, so tests start no thread.
 */
public interface HealdanStartListener {

	/**
	 * Called once at a real start, after the migrations and seeds ran.
	 */
	void started();
}
