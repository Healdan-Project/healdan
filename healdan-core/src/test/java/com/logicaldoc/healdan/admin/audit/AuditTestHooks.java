// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.dao.DataAccessResourceFailureException;

/**
 * For other modules' tests: what happens as {@link AuditFeed} begins each page, through its seam: a page that can't
 * be read, or something done as one begins, such as taking the reader out of {@code admin} while an export goes on.
 * The pages are counted from the hook's setting; {@link #reset} puts the seam back.
 */
public final class AuditTestHooks {

	private static final AtomicInteger pages = new AtomicInteger();

	private AuditTestHooks() {
	}

	/**
	 * Makes the {@code n}th page from now, and each after it, fail to be read, as with the database gone, until
	 * {@link #reset}.
	 *
	 * @param n which page, from 1
	 */
	public static void failPage(int n) {
		pages.set(0);
		AuditFeed.get().paged = cursor -> {
			if (pages.incrementAndGet() >= n)
				throw new DataAccessResourceFailureException("The test's database is gone");
		};
	}

	/**
	 * Runs an action as the {@code n}th page from now begins to be read, until {@link #reset}.
	 *
	 * @param n which page, from 1
	 * @param action what to do then
	 */
	public static void onPage(int n, Runnable action) {
		pages.set(0);
		AuditFeed.get().paged = cursor -> {
			if (pages.incrementAndGet() == n)
				action.run();
		};
	}

	/** @return how many pages began to be read since {@link #failPage} or {@link #onPage} */
	public static int pages() {
		return pages.get();
	}

	/** Puts the seam back */
	public static void reset() {
		AuditFeed.get().paged = cursor -> {
		};
		pages.set(0);
	}
}
