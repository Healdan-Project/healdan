// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The fake provider's own behaviour, where a test of the sign-in code leans on
 * it: a test that asks for an answer in chunks, or with a length, must get
 * what it asked for
 */
public class FakeProviderTest {

	private static final String DISCOVERY = "/.well-known/openid-configuration";

	/** Long enough for any answer, so an answer that never ends fails the test rather than holding it */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private FakeProvider fake;

	private HttpClient http;

	@Before
	public void startFake() {
		fake = FakeProvider.start();
		http = HttpClient.newHttpClient();
	}

	/** Closing the fake also resets {@code SignInTestHooks} */
	@After
	public void stopFake() {
		if (http != null)
			http.close();
		if (fake != null)
			fake.close();
	}

	/*
	 * Chunks are per answer: the JDK's server keeps an exchange's attributes on
	 * the context every request shares, so a flag set while answering one
	 * request stayed set for every request after it, on every path
	 */
	@Test
	public void testOnlyTheChosenPathIsAnsweredInChunks() throws Exception {
		fake.chunked("/keys", true);

		assertTrue(inChunks(get("/keys")));
		assertFalse("Discovery was asked for no chunks", inChunks(get(DISCOVERY)));
		assertFalse("An unknown path was asked for no chunks", inChunks(get("/nowhere")));
		assertTrue("The chosen path is still in chunks", inChunks(get("/keys")));
	}

	@Test
	public void testAPathGetsItsLengthBackWhenItIsNoLongerInChunks() throws Exception {
		HttpResponse<String> withLength = get("/keys");
		assertFalse(inChunks(withLength));
		assertTrue(withLength.body(), withLength.body().contains("\"keys\""));

		fake.chunked("/keys", true);
		HttpResponse<String> inPieces = get("/keys");
		assertTrue(inChunks(inPieces));
		assertEquals(200, inPieces.statusCode());
		assertEquals("The same answer, in chunks", withLength.body(), inPieces.body());

		fake.chunked("/keys", false);
		HttpResponse<String> again = get("/keys");
		assertFalse("The length was given again", inChunks(again));
		assertEquals(200, again.statusCode());
		assertEquals(withLength.body(), again.body());
	}

	/*
	 * To the JDK's server a length of 0 asks for chunks, so an empty answer on
	 * a path not in chunks would come in them all the same
	 */
	@Test
	public void testAnEmptyAnswerHasALengthOfZero() throws Exception {
		fake.answerRefreshWith("application/json", "");
		HttpResponse<String> empty = refresh();
		assertEquals(200, empty.statusCode());
		assertFalse("An empty answer has a length", inChunks(empty));
		assertEquals("0", empty.headers().firstValue("Content-Length").orElse(null));
		assertEquals("", empty.body());
		assertEquals("The fake still answers after it", 200, get(DISCOVERY).statusCode());

		fake.chunked("/token", true).answerRefreshWith("application/json", "");
		HttpResponse<String> emptyInChunks = refresh();
		assertTrue("Asked for, an empty answer comes in chunks", inChunks(emptyInChunks));
		assertEquals("", emptyInChunks.body());
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(fake.issuer() + path)).timeout(TIMEOUT).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	/** A refresh by the client the fake expects, which {@code answerRefreshWith} answers */
	private HttpResponse<String> refresh() throws Exception {
		String client = FakeProvider.CLIENT_ID + ":" + FakeProvider.SECRET;
		HttpRequest request = HttpRequest.newBuilder(URI.create(fake.issuer() + "/token")).timeout(TIMEOUT)
				.header("Authorization",
						"Basic " + Base64.getEncoder().encodeToString(client.getBytes(StandardCharsets.UTF_8)))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=refresh_token&refresh_token=any")).build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** A chunked answer has no length, and says it comes in chunks */
	private static boolean inChunks(HttpResponse<String> response) {
		boolean noLength = response.headers().firstValue("Content-Length").isEmpty();
		boolean saysChunked = response.headers().firstValue("Transfer-Encoding").orElse("").equalsIgnoreCase("chunked");
		assertEquals("Chunks and no length go together", noLength, saysChunked);
		return noLength;
	}
}
