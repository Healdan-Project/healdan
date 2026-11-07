// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.Test;

/** The endpoint rule (spec section 13), with names resolved from a map: no test needs real DNS */
public class EndpointsTest {

	private final List<String> looked = new ArrayList<>();

	private final Map<String, List<String>> names = Map.of( //
			"s3.us-east-2.wasabisys.com", List.of("38.27.106.53"), //
			"localhost", List.of("127.0.0.1", "::1"), //
			"garage", List.of("172.18.0.2"), //
			"nas.lan", List.of("192.168.1.20", "fd12:3456::20"), //
			"split.lan", List.of("10.0.0.7", "52.95.110.1"), //
			"split6.lan", List.of("10.0.0.8", "2001:db8::8"), //
			"linklocal.lan", List.of("169.254.10.1"), //
			"nothing.lan", List.of());

	private final Function<String, InetAddress[]> resolver = host -> {
		looked.add(host);
		List<String> addresses = names.get(host);
		if (addresses == null)
			throw new UncheckedIOException(new UnknownHostException(host));
		return addresses.stream().map(EndpointsTest::literal).toArray(InetAddress[]::new);
	};

	private static InetAddress literal(String address) {
		return InetAddress.ofLiteral(address);
	}

	private void passes(String endpoint) {
		Endpoints.check(URI.create(endpoint), resolver);
	}

	private void fails(String endpoint) {
		URI uri = URI.create(endpoint);
		try {
			Endpoints.check(uri, resolver);
			fail(endpoint + " passed");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage() != null && !expected.getMessage().isBlank());
		}
	}

	@Test
	public void testHttpsPasses() {
		passes("https://s3.us-east-2.wasabisys.com");
		passes("https://s3.us-east-2.wasabisys.com/");
		passes("https://s3.us-east-2.wasabisys.com:443");
		passes("HTTPS://S3.US-EAST-2.WASABISYS.COM");
		// Any address will do over https://, so nothing is looked up
		passes("https://no-such-name.example");
		assertTrue(looked.toString(), looked.isEmpty());
	}

	@Test
	public void testHttpToPublicFails() {
		fails("http://s3.us-east-2.wasabisys.com");
		fails("http://38.27.106.53:9000");
		fails("http://[2001:db8::1]:3900");
		fails("http://8.8.8.8");
	}

	@Test
	public void testHttpToPrivatePasses() {
		passes("http://10.0.0.5:3900");
		passes("http://localhost:3900");
		passes("http://127.0.0.1:3900");
		passes("http://127.8.9.10:3900");
		passes("http://[::1]:3900");
		passes("http://garage:3900");
		passes("http://nas.lan:9000");
		passes("http://192.168.0.1");
		passes("http://[fd00::5]:3900");
		passes("http://[fc00::1]:3900");
		// A literal needs no lookup
		assertTrue(looked.toString(), !looked.contains("10.0.0.5") && !looked.contains("127.0.0.1"));
	}

	@Test
	public void testPrivateRangesEdges() {
		passes("http://172.16.0.1");
		passes("http://172.31.255.255");
		fails("http://172.15.255.255");
		fails("http://172.32.0.1");
		passes("http://10.255.255.255");
		fails("http://11.0.0.1");
		fails("http://9.255.255.255");
		passes("http://192.168.255.255");
		fails("http://192.169.0.1");
		fails("http://192.167.255.255");
		// fc00::/7 is fc and fd; fe80::/10 is link-local, not private
		fails("http://[fe80::1]");
		fails("http://[fb00::1]");
		fails("http://[fe00::1]");
		fails("http://169.254.10.1");
		fails("http://linklocal.lan");
		fails("http://0.0.0.0");
	}

	@Test
	public void testANameWithAnyPublicAddressFails() {
		fails("http://split.lan");
		fails("http://split6.lan");
	}

	@Test
	public void testANameThatDoesNotResolveFails() {
		fails("http://no-such-name.lan:3900");
		fails("http://nothing.lan:3900");
	}

	@Test
	public void testPathQueryOrUserInfoFails() {
		fails("https://s3.us-east-2.wasabisys.com/bucket");
		fails("https://s3.us-east-2.wasabisys.com/bucket/");
		fails("https://s3.us-east-2.wasabisys.com?x=1");
		fails("https://s3.us-east-2.wasabisys.com/?x=1");
		fails("https://user:secret@s3.us-east-2.wasabisys.com");
		fails("https://user@s3.us-east-2.wasabisys.com");
		fails("https://s3.us-east-2.wasabisys.com#part");
		fails("http://user@10.0.0.5:3900");
		fails("http://10.0.0.5:3900/garage");
	}

	@Test
	public void testOtherSchemesFail() {
		fails("ftp://10.0.0.5");
		fails("s3://bucket");
		fails("//s3.us-east-2.wasabisys.com");
		fails("localhost:3900");
		fails("https:///nohost");
	}

	@Test
	public void testNoEndpointFails() {
		try {
			Endpoints.check(null, resolver);
			fail("No endpoint passed");
		} catch (IllegalArgumentException expected) {
			// as it should
		}
	}
}
