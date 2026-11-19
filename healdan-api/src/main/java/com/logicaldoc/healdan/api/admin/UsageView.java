// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.logicaldoc.healdan.admin.dashboard.Usage;
import com.logicaldoc.healdan.admin.dashboard.Usage.BusyMatter;
import com.logicaldoc.healdan.admin.dashboard.Usage.StoreUsage;
import com.logicaldoc.healdan.admin.dashboard.Usage.Total;

/**
 * {@code openapi-admin.yaml}'s {@code Usage}: the summary as {@link Usage} holds it, with its time as the API writes
 * timestamps and its days as ISO dates.
 *
 * @param computedAt when it was worked out; null until it has been once
 * @param failedAt when the last try to work it out failed; null once one works again
 * @param documents the documents
 * @param storage their files' bytes
 * @param matters the live matters
 * @param clients the clients
 * @param people the enabled people
 * @param signedIn how many people are signed in now
 * @param filedPerDay the documents filed on each of the last 30 days
 * @param stores the Stores
 * @param busiestMatters the busiest matters of the last 7 days
 */
public record UsageView(Date computedAt, Date failedAt, Total documents, Total storage, Total matters,
		Total clients, Total people, int signedIn, List<DayCount> filedPerDay, List<StoreUsage> stores,
		List<BusyMatter> busiestMatters) {

	/**
	 * @param date the day, as {@code 2026-11-19}
	 * @param count how many documents were filed on it
	 */
	public record DayCount(String date, long count) {
	}

	/**
	 * @param usage the summary
	 * @return its view
	 */
	static UsageView of(Usage usage) {
		return new UsageView(date(usage.computedAt()), date(usage.failedAt()), usage.documents(), usage.storage(),
				usage.matters(), usage.clients(), usage.people(), usage.signedIn(),
				usage.filedPerDay().stream().map(day -> new DayCount(day.date().toString(), day.count())).toList(),
				usage.stores(), usage.busiestMatters());
	}

	private static Date date(Instant instant) {
		return instant != null ? Date.from(instant) : null;
	}
}
