package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.web.PublicAddress.Address;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * The server's public address, which the Office add-in's manifest and the
 * sign-in pages are built from (M4, ruling R2): the cases the add-in's
 * manifest had, and plain HTTP anywhere only while
 * {@code HEALDAN_SIGNIN_ALLOW_HTTP} is set.
 */
public class PublicAddressTest extends AbstractHealdanTestCase {

	private static final String SERVER_URL = "server.url";

	private String savedServerUrl;

	@Before
	public void saveTheAddress() {
		savedServerUrl = config().getProperty(SERVER_URL);
	}

	@Override
	public void tearDown() throws IOException {
		SignInTestHooks.reset();
		if (savedServerUrl == null)
			config().remove(SERVER_URL);
		else
			config().setProperty(SERVER_URL, savedServerUrl);
		super.tearDown();
	}

	private static ContextProperties config() {
		return Context.get().getConfig();
	}

	/** A request as Tomcat hands it over, after RemoteIpValve applied X-Forwarded-Proto */
	private static MockHttpServletRequest request(String scheme, String host, int port) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/signin/fake");
		request.setScheme(scheme);
		request.setServerName(host);
		request.setServerPort(port);
		return request;
	}

	private static MockHttpServletRequest request() {
		return new MockHttpServletRequest("GET", "/api/signin/fake");
	}

	private static void assertAddress(String origin, String base, Optional<Address> address) {
		assertTrue("no address, where " + base + " was expected", address.isPresent());
		assertEquals(origin, address.get().origin());
		assertEquals(base, address.get().base());
	}

	@Test
	public void testTheConfiguredAddress() {
		// The test context's server.url is http://localhost:8080
		assertAddress("http://localhost:8080", "http://localhost:8080", PublicAddress.of(request()));

		config().setProperty(SERVER_URL, "https://healdan.example/");
		assertAddress("https://healdan.example", "https://healdan.example", PublicAddress.of(request()));
		// The configured address wins over the request's
		assertAddress("https://healdan.example", "https://healdan.example",
				PublicAddress.of(request("http", "127.0.0.1", 8080)));

		// An address with a path: the origin is its scheme, host and port
		config().setProperty(SERVER_URL, "https://healdan.example:8443/dms/");
		assertAddress("https://healdan.example:8443", "https://healdan.example:8443/dms", PublicAddress.of(request()));

		// Scheme and host in lower case, the path as it is
		config().setProperty(SERVER_URL, "HTTPS://Healdan.Example/DMS");
		assertAddress("https://healdan.example", "https://healdan.example/DMS", PublicAddress.of(request()));
	}

	@Test
	public void testTheRequestsAddressWithoutAConfiguredOne() {
		config().remove(SERVER_URL);

		assertAddress("https://healdan.example", "https://healdan.example",
				PublicAddress.of(request("https", "healdan.example", 443)));
		assertAddress("https://healdan.example:8443", "https://healdan.example:8443",
				PublicAddress.of(request("https", "healdan.example", 8443)));

		// What a stack on one's own machine is
		assertAddress("http://127.0.0.1:8080", "http://127.0.0.1:8080",
				PublicAddress.of(request("http", "127.0.0.1", 8080)));
		assertAddress("http://[::1]:8080", "http://[::1]:8080", PublicAddress.of(request("http", "[::1]", 8080)));
		// A bare IPv6 address, as a container may give it, gets its brackets
		assertAddress("http://[::1]:8080", "http://[::1]:8080", PublicAddress.of(request("http", "::1", 8080)));
		assertAddress("http://localhost", "http://localhost", PublicAddress.of(request("http", "localhost", 80)));

		// The context path is part of the base
		MockHttpServletRequest inAContext = request("https", "healdan.example", 443);
		inAContext.setContextPath("/dms");
		assertAddress("https://healdan.example", "https://healdan.example/dms", PublicAddress.of(inAContext));
	}

	@Test
	public void testLogicalDOCsPlaceholderIsNoAddress() {
		// An image's settings keep LogicalDOC's default until LOGICALDOC_PUBLIC_URL is set
		config().setProperty(SERVER_URL, "http://server:port/");

		assertAddress("http://127.0.0.1:8080", "http://127.0.0.1:8080",
				PublicAddress.of(request("http", "127.0.0.1", 8080)));
		assertAddress("https://healdan.example", "https://healdan.example",
				PublicAddress.of(request("https", "healdan.example", 443)));

		config().setProperty(SERVER_URL, "  ");
		assertAddress("https://healdan.example", "https://healdan.example",
				PublicAddress.of(request("https", "healdan.example", 443)));
	}

	@Test
	public void testPlainHttpOnlyOnThisMachine() {
		config().remove(SERVER_URL);
		assertTrue(PublicAddress.of(request("http", "healdan.example", 80)).isEmpty());

		config().setProperty(SERVER_URL, "http://healdan.example/");
		assertTrue(PublicAddress.of(request()).isEmpty());
	}

	@Test
	public void testAnAddressThatCantBeWrittenOut() {
		for (String address : new String[] { "https://healdan.example\"><x/>", "https://healdan.example/a b",
				"https://user@healdan.example/", "https://healdan.example/?q=1", "https://healdan.example/#top",
				"ftp://healdan.example/", "healdan.example", "https://healdan.example/\r\nX-Header: 1" }) {
			config().setProperty(SERVER_URL, address);
			assertTrue(address, PublicAddress.of(request()).isEmpty());
		}
	}

	@Test
	public void testPlainHttpAnywhereOnlyWhenAllowed() {
		// The sign-in overlay's stand-in provider reaches Healdan by its service name
		config().setProperty(SERVER_URL, "http://keycloak:8080");
		assertTrue(PublicAddress.of(request()).isEmpty());

		SignInTestHooks.allowHttp(true);
		assertAddress("http://keycloak:8080", "http://keycloak:8080", PublicAddress.of(request()));

		config().remove(SERVER_URL);
		assertAddress("http://healdan.example", "http://healdan.example",
				PublicAddress.of(request("http", "healdan.example", 80)));

		SignInTestHooks.allowHttp(false);
		assertTrue(PublicAddress.of(request("http", "healdan.example", 80)).isEmpty());
	}
}
