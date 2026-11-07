// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.net.URI;
import java.time.Duration;

import org.junit.Test;

/** An S3 backend's settings: the uploads' timeout and the prefixes allowed */
public class S3SettingsTest {

	private static final long MIB = 1L << 20;

	private static S3Settings settings(String prefix) {
		return new S3Settings(URI.create("https://s3.us-east-2.wasabisys.com"), "us-east-2", "bucket", prefix, true,
				"AK");
	}

	@Test
	public void testUploadTimeoutScalesWithTheBytes() {
		S3Settings inUse = settings("healdan");
		assertEquals(Duration.ofMinutes(10), inUse.uploadTimeout());
		assertEquals(64L << 10, inUse.uploadRate());
		// Ten minutes, or the bytes at 64 KiB/s if that is longer
		assertEquals(Duration.ofMinutes(10), inUse.uploadTimeoutFor(0));
		assertEquals(Duration.ofMinutes(10), inUse.uploadTimeoutFor(1));
		assertEquals("A 16 MiB part", Duration.ofMinutes(10), inUse.uploadTimeoutFor(S3Backend.PART_SIZE));
		assertEquals("37.5 MiB at 64 KiB/s is ten minutes", Duration.ofMinutes(10),
				inUse.uploadTimeoutFor(600L * (64L << 10)));
		assertEquals(Duration.ofSeconds(601), inUse.uploadTimeoutFor(601L * (64L << 10)));
		assertEquals("A 64 MiB put, about 17 minutes", Duration.ofSeconds(1024),
				inUse.uploadTimeoutFor(S3Backend.SINGLE_PUT_LIMIT));
		// Rounded up, to the millisecond
		assertEquals(Duration.ofMillis(1024_001), inUse.uploadTimeoutFor(S3Backend.SINGLE_PUT_LIMIT + 1));

		S3Settings quick = inUse.withTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
		assertEquals(Duration.ofSeconds(1), quick.uploadTimeoutFor(0));
		assertEquals(Duration.ofSeconds(16), quick.uploadTimeoutFor(MIB));
		assertEquals(Duration.ofSeconds(1), quick.withUploadRate(1L << 30).uploadTimeoutFor(40 * MIB));
		assertEquals(Duration.ofSeconds(2), quick.withUploadRate(MIB).uploadTimeoutFor(2 * MIB));
	}

	/**
	 * CE's layout starts every key with a digit, so a prefix with a folder starting with one would let another
	 * backend's objects sit among this one's: one at {@code P + "123/"} would put document 45's file where document
	 * 12345's is, and no marker would see it.
	 */
	@Test
	public void testNestedPrefixesRefused() {
		for (String prefix : new String[] { "123", "123/", "/1/", "a/45", "a/45/", "healdan/2026", "1a", "a/9z/b", ".x",
				"a/.b", "./a", "a/..", "a/./b" })
			try {
				settings(prefix);
				fail("The prefix " + prefix + " was taken");
			} catch (IllegalArgumentException expected) {
				// as it should
			}
		for (String prefix : new String[] { "", "healdan", "healdan/", "a1/b2", "x-1", "Healdan/backups", "firm_2026" })
			settings(prefix);
	}
}
