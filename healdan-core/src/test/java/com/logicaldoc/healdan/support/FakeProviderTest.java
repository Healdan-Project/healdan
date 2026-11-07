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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.healdan.signin.SignInTestHooks;

/**
 * The fake provider's own behaviour, where a test of the sign-in code leans on
 * it: a test that asks for an answer in chunks, or with a length, must get
 * what it asked for
 */
public class FakeProviderTest {

	private static final String DISCOVERY = "/.well-known/openid-configuration";

	private FakeProvider fake;

	private HttpClient http;

	@Before
	public void startFake() {
		fake = FakeProvider.start();
		http = HttpClient.newHttpClient();
	}

	@After
	public void stopFake() {
		if (fake != null)
			fake.close();
		SignInTestHooks.reset();
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
		assertFalse(inChunks(get("/keys")));

		fake.chunked("/keys", true);
		assertTrue(inChunks(get("/keys")));

		fake.chunked("/keys", false);
		assertFalse("The length was given again", inChunks(get("/keys")));
		assertEquals(200, get("/keys").statusCode());
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(fake.issuer() + path)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	/** A chunked answer has no length, and says it comes in chunks */
	private static boolean inChunks(HttpResponse<String> response) {
		boolean noLength = response.headers().firstValue("Content-Length").isEmpty();
		boolean saysChunked = response.headers().firstValue("Transfer-Encoding").orElse("").equalsIgnoreCase("chunked");
		assertEquals("Chunks and no length go together", noLength, saysChunked);
		return noLength;
	}
}
