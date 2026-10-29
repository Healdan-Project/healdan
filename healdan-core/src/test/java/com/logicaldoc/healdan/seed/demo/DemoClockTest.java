package com.logicaldoc.healdan.seed.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;

import org.junit.Test;

public class DemoClockTest {

	private static final ZoneId LONDON = ZoneId.of("Europe/London");

	private final DemoClock clock = new DemoClock(LocalDate.of(2026, 10, 29), LONDON);

	private static Date date(int y, int m, int d, int h, int min) {
		return Date.from(LocalDateTime.of(y, m, d, h, min).atZone(LONDON).toInstant());
	}

	@Test
	public void testADayAndTime() {
		assertEquals(date(2026, 10, 28, 14, 30), clock.at(-1, "14:30"));
		assertEquals(date(2026, 9, 17, 8, 5), clock.at(-42, "08:05"));
	}

	@Test
	public void testNoTimeIsNineInTheMorning() {
		assertEquals(date(2026, 10, 26, 9, 0), clock.at(-3, null));
	}

	@Test
	public void testTodayAndLaterAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> clock.at(0, "08:00"));
		assertThrows(IllegalArgumentException.class, () -> clock.at(2, null));
	}

	@Test
	public void testABadTimeIsRefused() {
		assertThrows(DateTimeParseException.class, () -> clock.at(-1, "2pm"));
	}
}
