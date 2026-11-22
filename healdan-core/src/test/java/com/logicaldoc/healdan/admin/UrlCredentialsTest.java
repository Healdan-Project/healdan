// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only
package com.logicaldoc.healdan.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

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

	/** A list of hosts joined by a comma or a semicolon, as several clients write their addresses */
	@Test
	public void testAUrlRightAfterAnothersHost() {
		assertEquals("https://h,https://h2", UrlCredentials.cut("https://u:p@h,https://u2:p2@h2"));
		assertEquals("https://h;https://h2/x", UrlCredentials.cut("https://u:p@h;https://u2:p2@h2/x"));
		assertEquals("amqp://h1:5672,amqp://h2:5672", UrlCredentials.cut("amqp://u:p@h1:5672,amqp://u:p@h2:5672"));
		assertEquals("ftp://y,s3://b/k", UrlCredentials.cut("ftp://x@y,s3://k:s@b/k"));
	}

	@Test
	public void testAUrlAsAnothersAuthority() {
		assertEquals("x://a://host/p", UrlCredentials.cut("x://a://user:pw@host/p"));
		assertEquals("proxy http://http://host/p", UrlCredentials.cut("proxy http://http://user:pw@host/p"));
	}

	@Test
	public void testAUrlInAnothersQuery() {
		assertEquals("https://h/?next=https://h2/x", UrlCredentials.cut("https://h/?next=https://u:p@h2/x"));
		assertEquals("https://h/login?next=https://h2", UrlCredentials.cut("https://u:p@h/login?next=https://me@h2"));
	}

	/** A comma or a bracket doesn't end an authority: what follows up to its end is the authority's */
	@Test
	public void testAUrlFollowedByACommaOrABracket() {
		assertEquals("(see https://h), then", UrlCredentials.cut("(see https://u:p@h), then"));
		assertEquals("https://h, then", UrlCredentials.cut("https://u:p@h, then"));
		// An @ after the comma, before the authority ends, is the last @: so the user part runs to it
		assertEquals("https://x then", UrlCredentials.cut("https://u:p@h,next@x then"));
	}

	/** The limits: what isn't recognised as a URL's user part stays as it was */
	@Test
	public void testAPasswordHoldingWhatEndsTheAuthorityIsntRecognised() {
		for (String text : List.of("https://u:pa/ss@host/", "https://u:pa?ss@host/", "https://u:pa#ss@host/",
				"https://u:pa ss@host/", "https://u:pa\"ss@host/", "https://u:pa'ss@host/", "https://u:pa<ss@host/",
				"https://u:pa>ss@host/"))
			assertEquals(text, UrlCredentials.cut(text));
	}

	@Test
	public void testAnEscapedOrEncodedUrlIsntRecognised() {
		assertEquals("{\"url\":\"https:\\/\\/u:p@h\"}", UrlCredentials.cut("{\"url\":\"https:\\/\\/u:p@h\"}"));
		assertEquals("next=https%3A%2F%2Fu%3Ap%40h", UrlCredentials.cut("next=https%3A%2F%2Fu%3Ap%40h"));
	}

	@Test
	public void testAUrlCutShortBeforeItsAtIsntRecognised() {
		assertEquals("Can't connect to https://user:secr", UrlCredentials.cut("Can't connect to https://user:secr"));
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
		assertEquals("a://", UrlCredentials.cut("a://" + text));
	}

	@Test(timeout = 10000)
	public void testLongLineOfHostsJoinedByCommas() {
		String text = "a://u:p@h,".repeat(100_000);
		assertEquals("a://h,".repeat(100_000), UrlCredentials.cut(text));
	}

	@Test(timeout = 10000)
	public void testLongLineOfAtsAndSlashes() {
		String text = "x://u@h/@/@/".repeat(80_000);
		String expected = "x://h/@/@/".repeat(80_000);
		assertEquals(expected, UrlCredentials.cut(text));
	}
}
