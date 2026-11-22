// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only
package com.logicaldoc.healdan.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** {@link UrlCredentials}: plain JUnit, no Spring and no database. */
public class UrlCredentialsTest {

	@Test
	public void testNullAndEmpty() {
		assertNull(UrlCredentials.cut(null));
		assertEquals("", UrlCredentials.cut(""));
	}

	@Test
	public void testUserAndPassword() {
		assertEquals("scheme://host/path", UrlCredentials.cut("scheme://user:secret@host/path"));
	}

	@Test
	public void testUserAlone() {
		assertEquals("scheme://host", UrlCredentials.cut("scheme://user@host"));
	}

	@Test
	public void testEmailOutsideAUrlStays() {
		assertEquals("write to ann@example.com now", UrlCredentials.cut("write to ann@example.com now"));
	}

	@Test
	public void testAtInPathOrQueryStays() {
		assertEquals("https://host/a@b", UrlCredentials.cut("https://host/a@b"));
		assertEquals("https://host/?q=a@b", UrlCredentials.cut("https://host/?q=a@b"));
		assertEquals("https://host#a@b", UrlCredentials.cut("https://host#a@b"));
	}

	@Test
	public void testNoUrl() {
		assertEquals("plain text: a@b and c://", UrlCredentials.cut("plain text: a@b and c://"));
	}

	@Test
	public void testSeveralUrls() {
		assertEquals("a https://h1/x and smtp://h2:25 then s3://b/k",
				UrlCredentials.cut("a https://u:p@h1/x and smtp://me@h2:25 then s3://k:s@b/k"));
	}

	@Test
	public void testSchemes() {
		assertEquals("jdbc:postgresql://db:5432/x", UrlCredentials.cut("jdbc:postgresql://u:p@db:5432/x"));
		assertEquals("s3://bucket/key", UrlCredentials.cut("s3://a:b@bucket/key"));
		assertEquals("svn+ssh://host", UrlCredentials.cut("svn+ssh://u@host"));
		assertEquals("a.b-c://host", UrlCredentials.cut("a.b-c://u@host"));
	}

	@Test
	public void testPasswordWithColonAndEscapes() {
		assertEquals("https://host/p", UrlCredentials.cut("https://u:pa:ss%40w%2Fd@host/p"));
	}

	@Test
	public void testIpv6Host() {
		assertEquals("http://[::1]:8080/", UrlCredentials.cut("http://u:p@[::1]:8080/"));
	}

	@Test
	public void testLastAtEndsTheUserPart() {
		assertEquals("https://host/", UrlCredentials.cut("https://a@b:c@host/"));
	}

	@Test
	public void testAuthorityEnds() {
		assertEquals("\"https://host\" next@x", UrlCredentials.cut("\"https://u@host\" next@x"));
		assertEquals("<https://host>", UrlCredentials.cut("<https://u@host>"));
		assertEquals("'https://host' a@b", UrlCredentials.cut("'https://u@host' a@b"));
		assertEquals("https://host a@b", UrlCredentials.cut("https://u@host a@b"));
	}

	@Test(timeout = 10000)
	public void testLongLineOfSchemes() {
		String text = "a://".repeat(250_000);
		assertEquals(text, UrlCredentials.cut(text));
	}

	@Test(timeout = 10000)
	public void testLongLineOfAts() {
		String text = "@".repeat(1_000_000);
		assertEquals(text, UrlCredentials.cut(text));
		assertEquals("a://" + "@".repeat(0), UrlCredentials.cut("a://" + text));
	}

	@Test(timeout = 10000)
	public void testLongLineOfAtsAndSlashes() {
		String text = "x://u@h/@/@/".repeat(80_000);
		String expected = "x://h/@/@/".repeat(80_000);
		assertEquals(expected, UrlCredentials.cut(text));
	}
}
