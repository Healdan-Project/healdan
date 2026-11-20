// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.Test;

/**
 * The Dashboard's lines, in the Admin app's own words: sizes as its {@code formatSize} writes them, lists, and lengths
 * of time with no clock time.
 */
public class WordsTest {

	private static final long KB = 1024;

	private static final long GB = KB * KB * KB;

	// The examples of @healdan/ui's formatSize tests, and its rule: a decimal under 10, whole above
	@Test
	public void testSizes() {
		assertEquals("0 B", Words.size(0));
		assertEquals("512 B", Words.size(512));
		assertEquals("1,023 B", Words.size(1023));
		assertEquals("1 KB", Words.size(KB));
		assertEquals("209 KB", Words.size(214_000));
		assertEquals("1.4 MB", Words.size(1_468_006));
		assertEquals("10 MB", Words.size(10 * KB * KB));
		assertEquals("3 GB", Words.size(3 * GB));
		assertEquals("195 GB", Words.size(195 * GB));
		assertEquals("1.1 GB", Words.size(Math.round(1.1 * GB)));
		assertEquals("2 TB", Words.size(2048 * GB));
		assertEquals("195 GB of 500 GB used", Words.usedOf(195 * GB, 500 * GB));
	}

	@Test
	public void testCounts() {
		assertEquals("1,284", Words.number(1284));
		assertEquals("1 file", Words.count(1, "file", "files"));
		assertEquals("3 files", Words.count(3, "file", "files"));
		assertEquals("0 files", Words.count(0, "file", "files"));
	}

	@Test
	public void testLists() {
		assertEquals("", Words.list(List.of()));
		assertEquals("Previews", Words.list(List.of("Previews")));
		assertEquals("Previews and Indexer", Words.list(List.of("Previews", "Indexer")));
		assertEquals("A, B and C", Words.list(List.of("A", "B", "C")));
		assertEquals("A, B, C and 2 more", Words.list(List.of("A", "B", "C", "D", "E")));
	}

	@Test
	public void testTimes() {
		assertEquals("less than a minute", Words.duration(Duration.ofSeconds(59)));
		assertEquals("1 minute", Words.duration(Duration.ofSeconds(60)));
		assertEquals("59 minutes", Words.duration(Duration.ofMinutes(59)));
		assertEquals("1 hour", Words.duration(Duration.ofMinutes(119)));
		assertEquals("6 days", Words.duration(Duration.ofDays(6).plusHours(23)));

		Instant now = Instant.parse("2026-11-19T12:00:00Z");
		assertEquals("just now", Words.ago(now.minusSeconds(30), now));
		assertEquals("12 minutes ago", Words.ago(now.minusSeconds(12 * 60 + 5), now));
		assertEquals("2 hours ago", Words.ago(now.minus(Duration.ofHours(2)), now));
		// A time ahead of the server's is no time ago
		assertEquals("just now", Words.ago(now.plusSeconds(90), now));
	}

	@Test
	public void testCapitalised() {
		assertEquals("Repository 91% full", Words.capitalised("repository 91% full"));
		assertEquals("", Words.capitalised(""));
	}
}
