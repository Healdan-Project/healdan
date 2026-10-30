package com.logicaldoc.healdan.seed.demo;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Turns a manifest's day and time into a date, counted back from the day the
 * stack first starts, so the demo always looks a few weeks old (demo spec
 * §4.6).
 */
public final class DemoClock {

	private static final LocalTime DEFAULT_TIME = LocalTime.of(9, 0);

	private final LocalDate today;

	private final ZoneId zone;

	public DemoClock(LocalDate today, ZoneId zone) {
		this.today = today;
		this.zone = zone;
	}

	/**
	 * @param day days before today, so -1 is yesterday. Today and later are
	 *        refused, so nothing is dated after the stack started (call)
	 * @param time {@code HH:mm}, or null for 09:00
	 *
	 * @return the date
	 */
	public Date at(int day, String time) {
		if (day >= 0)
			throw new IllegalArgumentException(
					"Day %d isn't in the past: the timeline's days count back from -1".formatted(day));
		LocalTime at = time == null ? DEFAULT_TIME : LocalTime.parse(time);
		return Date.from(today.plusDays(day).atTime(at).atZone(zone).toInstant());
	}
}
