// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Moments that fall on different days in the server's time zone and in a far one, for the tests of the Edit Date
 * filters' {@code zone} (M6 R90). The far zone is at least 13 hours from the server's, whatever the machine's zone.
 */
final class ZoneMoments {

	private ZoneMoments() {
	}

	/**
	 * @return 12 hours behind UTC for a server at or east of Greenwich, else 14 hours ahead of it
	 */
	static ZoneId far() {
		int offset = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds();
		return ZoneId.of(offset >= 0 ? "Etc/GMT+12" : "Pacific/Kiritimati");
	}

	/**
	 * @return half past midnight on 11 June 2026 in whichever of the two zones is ahead, which is still 10 June in the
	 *         other
	 */
	static Instant onDifferentDays(ZoneId one, ZoneId other) {
		LocalDateTime early = LocalDateTime.of(2026, 6, 11, 0, 30);
		Instant inOne = early.atZone(one).toInstant();
		Instant inOther = early.atZone(other).toInstant();
		// The zone ahead reaches that hour first
		return inOne.isBefore(inOther) ? inOne : inOther;
	}

	/**
	 * @return a minute before the later of today's two midnights: today in the zone whose day began first, and still
	 *         yesterday in the other
	 */
	static Instant todayInOneOnly(ZoneId one, ZoneId other) {
		Instant first = midnight(one);
		Instant second = midnight(other);
		return (first.isAfter(second) ? first : second).minus(Duration.ofMinutes(1));
	}

	/**
	 * @return whether the moment falls today in the zone
	 */
	static boolean today(Instant moment, ZoneId zone) {
		return !moment.isBefore(midnight(zone));
	}

	/**
	 * @return the day the moment falls on in the zone, as the API takes it
	 */
	static String day(Instant moment, ZoneId zone) {
		return LocalDate.ofInstant(moment, zone).toString();
	}

	/**
	 * @return {@code &zone=} and the zone's id, encoded for an address ({@code +} would be read as a space)
	 */
	static String param(ZoneId zone) {
		return "&zone=" + URLEncoder.encode(zone.getId(), StandardCharsets.UTF_8);
	}

	private static Instant midnight(ZoneId zone) {
		return LocalDate.now(zone).atStartOfDay(zone).toInstant();
	}
}
