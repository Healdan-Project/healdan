// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import org.junit.Test;

import com.logicaldoc.healdan.listing.ListingQuery.Edited;
import com.logicaldoc.healdan.listing.ListingQuery.Sort;

/**
 * {@link ListingQuery}: the edit-date presets and what a query refuses.
 */
public class ListingQueryTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

	private static final Clock CLOCK = Clock.fixed(TODAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(),
			ZoneId.systemDefault());

	@Test
	public void testEditedPresets() {
		ZoneId zone = CLOCK.getZone();
		assertEquals(new Edited(TODAY, TODAY, zone), Edited.preset("today", CLOCK));
		assertEquals(new Edited(TODAY.minusDays(6), TODAY, zone), Edited.preset("7d", CLOCK));
		assertEquals(new Edited(TODAY.minusDays(29), TODAY, zone), Edited.preset("30d", CLOCK));
		assertEquals(new Edited(LocalDate.of(2026, 1, 1), TODAY, zone), Edited.preset("year", CLOCK));
		// The days are the clock's, so they are cut in its zone: at noon UTC it is already the 16th on Kiritimati
		Clock far = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneId.of("Pacific/Kiritimati"));
		assertEquals(new Edited(TODAY.plusDays(1), TODAY.plusDays(1), far.getZone()), Edited.preset("today", far));
		// Without a zone, the server's
		assertEquals(ZoneId.systemDefault(), new Edited(TODAY, TODAY).zoneOrServers());
		for (String name : new String[] { "week", "", null })
			try {
				Edited.preset(name, CLOCK);
				fail(name + " isn't a preset");
			} catch (IllegalArgumentException expected) {
				// As it should
			}
	}

	@Test
	public void testRefusals() {
		refused(() -> new Edited(TODAY, TODAY.minusDays(1)));
		refused(() -> ListingQuery.of(Sort.TITLE, false, -1, 10));
		refused(() -> ListingQuery.of(Sort.TITLE, false, 0, -1));
		refused(() -> ListingQuery.of(Sort.TITLE, false, 0, 10).withKinds(Set.of("folder")));
	}

	@Test
	public void testFiltered() {
		ListingQuery plain = ListingQuery.of(Sort.TITLE, false, 0, 10);
		assertFalse(plain.filtered());
		assertFalse(plain.withEdited(new Edited(null, null)).filtered());
		assertTrue(plain.withKinds(Set.of("pdf")).filtered());
		assertTrue(plain.withMatters(Set.of("998.1")).filtered());
		assertTrue(plain.withEdited(new Edited(TODAY, null)).filtered());
		assertFalse(plain.withAuthors(Set.of("abe")).without(Facets.AUTHOR).filtered());
		assertEquals(Set.of("pdf"), plain.withKinds(Set.of("pdf")).page(5, 7).kinds());
		assertEquals(5, plain.page(5, 7).offset());
	}

	private static void refused(Runnable action) {
		try {
			action.run();
			fail("Not refused");
		} catch (IllegalArgumentException expected) {
			// As it should
		}
	}
}
