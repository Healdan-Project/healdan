// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The Dashboard's Usage (Admin app spec section 8.1), as {@link UsageSummary} worked it out.
 * <p>
 * Days are the server's, in its time zone. "The last 7 days" and "the last 30 days" are whole days, today included:
 * the last 7 and the 30 of {@link #filedPerDay} add up to {@link #documents}' changes. Documents are the live ones: not
 * shortcuts, and not in the Trash.
 *
 * @param computedAt when it was worked out; null until it has been once since the start, when the totals are null and
 *        the lists empty
 * @param documents the documents, and those filed in the last 7 and 30 days
 * @param storage the bytes of the documents' files, the current version of each, and of those filed in the last 7
 *        and 30 days
 * @param matters the live matters (not deleted, their folders not in the Trash), and those created in the last 7 and
 *        30 days
 * @param clients the clients, and those created in the last 7 and 30 days
 * @param people the enabled user accounts, not system accounts such as {@code _system}, and those created in the last
 *        7 and 30 days
 * @param signedIn how many people have a session open on this server: counted when asked, not when worked out
 * @param filedPerDay the documents filed on each of the last 30 days, oldest first
 * @param stores the Stores, most bytes first, with what their matters hold
 * @param busiestMatters the matters most documents were filed in in the last 7 days, most first, at most
 *        {@link UsageSummary#BUSIEST}
 */
public record Usage(Instant computedAt, Total documents, Total storage, Total matters, Total clients, Total people,
		int signedIn, List<Day> filedPerDay, List<StoreUsage> stores, List<BusyMatter> busiestMatters) {

	public Usage {
		filedPerDay = List.copyOf(filedPerDay);
		stores = List.copyOf(stores);
		busiestMatters = List.copyOf(busiestMatters);
	}

	/** Nothing worked out yet */
	static final Usage NONE = new Usage(null, null, null, null, null, null, 0, List.of(), List.of(), List.of());

	/**
	 * @param count how many people are signed in now
	 * @return this, with that count
	 */
	Usage signedIn(int count) {
		return new Usage(computedAt, documents, storage, matters, clients, people, count, filedPerDay, stores,
				busiestMatters);
	}

	/**
	 * A total and what was added to it lately.
	 *
	 * @param total how many there are, or for storage how many bytes
	 * @param last7Days how many of them came in the last 7 days
	 * @param last30Days how many of them came in the last 30 days
	 */
	public record Total(long total, long last7Days, long last30Days) {
	}

	/**
	 * @param date the day, in the server's time zone
	 * @param count how many documents were filed on it
	 */
	public record Day(LocalDate date, long count) {
	}

	/**
	 * A Store and what its matters hold.
	 *
	 * @param id the Store's folder
	 * @param name its name
	 * @param documents how many documents its matters hold
	 * @param bytes the size of their files, the current version of each
	 * @param backend the storage backend it keeps its files on; null when the server doesn't use storage backends, or
	 *        they couldn't be read
	 */
	public record StoreUsage(long id, String name, long documents, long bytes, Backend backend) {
	}

	/**
	 * A storage backend, as a Store names it.
	 *
	 * @param id its id; Original is 1
	 * @param name its name
	 * @param kind {@code local}, a folder, or {@code s3}, a bucket
	 * @param encrypted whether its files are encrypted
	 */
	public record Backend(long id, String name, String kind, boolean encrypted) {
	}

	/**
	 * @param id the matter's id
	 * @param number its number with its client's, such as {@code 1001.1}
	 * @param name its name
	 * @param filed how many documents were filed in it in the last 7 days
	 */
	public record BusyMatter(long id, String number, String name, long filed) {
	}
}
