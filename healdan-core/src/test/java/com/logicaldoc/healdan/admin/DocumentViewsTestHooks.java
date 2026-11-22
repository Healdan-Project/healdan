// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.springframework.dao.DataAccessResourceFailureException;

/**
 * For other modules' tests: moves the clock {@link DocumentViews} times views by, and makes its writes fail. Both are
 * the bean's, which each test's context makes afresh; {@link #reset} puts them back.
 */
public final class DocumentViewsTestHooks {

	private DocumentViewsTestHooks() {
	}

	/**
	 * Stops the clock views are timed by where it is, until {@link #reset}, so that only {@link #passTime} moves it.
	 */
	public static void stopTime() {
		DocumentViews views = DocumentViews.get();
		long now = views.nanoTime.getAsLong();
		views.nanoTime = () -> now;
	}

	/**
	 * Moves the clock views are timed by, as if that much time had passed, until {@link #reset}. Each call moves it
	 * further.
	 *
	 * @param by how far to move it
	 */
	public static void passTime(Duration by) {
		DocumentViews views = DocumentViews.get();
		LongSupplier before = views.nanoTime;
		long nanos = by.toNanos();
		views.nanoTime = () -> before.getAsLong() + nanos;
	}

	/**
	 * Makes each write of a view fail, as with the history store down, until {@link #reset}.
	 */
	public static void failWrites() {
		failWrites(() -> {
			throw new DataAccessResourceFailureException("The history store is down");
		});
	}

	/**
	 * @param failure run inside each write's transaction just before its row is stored, until {@link #reset}; one
	 *        that throws fails the write
	 */
	public static void failWrites(Runnable failure) {
		DocumentViews.get().beforeStore = failure;
	}

	/**
	 * Puts the clock and the writes back
	 */
	public static void reset() {
		DocumentViews views = DocumentViews.get();
		views.nanoTime = System::nanoTime;
		views.beforeStore = () -> {
		};
	}
}
