package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanStartup;
import com.logicaldoc.healdan.support.FakeProvider;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * Break glass (M4 spec sections 7.5 and 16.1): Healdan starts with every
 * provider unreachable, asking none of them anything, and {@code admin} signs
 * in with its password meanwhile, which sends the alert, as it's on here.
 * <p>
 * A provider request is seen by the JVM's proxy selector, which every
 * {@code HttpURLConnection} asks before it connects, Nimbus's included: the
 * test puts one there that records each address it's asked about.
 */
public class BreakGlassTest extends AbstractHealdanTestCase {

	private final List<URI> asked = new CopyOnWriteArrayList<>();

	private final List<EMail> emails = new CopyOnWriteArrayList<>();

	private final Map<String, String> savedConfig = new HashMap<>();

	private ProxySelector savedSelector;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		for (String name : List.of("default.smtp.host", "default.smtp.sender", "smtp.nosend"))
			savedConfig.put(name, config().getProperty(name));
		config().setProperty("default.smtp.host", "smtp.healdan.example");
		config().setProperty("default.smtp.sender", "office@healdan.example");
		config().setProperty("smtp.nosend", "true");
		OutgoingEmail.sender = emails::add;

		savedSelector = ProxySelector.getDefault();
		ProxySelector.setDefault(new ProxySelector() {
			@Override
			public List<Proxy> select(URI uri) {
				asked.add(uri);
				return List.of(Proxy.NO_PROXY);
			}

			@Override
			public void connectFailed(URI uri, SocketAddress address, IOException e) {
				// Nothing to do
			}
		});
	}

	@Override
	public void tearDown() throws IOException {
		ProxySelector.setDefault(savedSelector);
		SignInTestHooks.reset();
		for (Map.Entry<String, String> saved : savedConfig.entrySet())
			if (saved.getValue() == null)
				config().remove(saved.getKey());
			else
				config().setProperty(saved.getKey(), saved.getValue());
		// CE writes the file whenever admin is stored, as a sign-in can, so the next test would read these
		config().write();
		super.tearDown();
	}

	private static ContextProperties config() {
		return Context.get().getConfig();
	}

	/** The requests to either provider's address */
	private List<URI> providerRequests(int... ports) {
		return asked.stream().filter(uri -> "127.0.0.1".equals(uri.getHost()))
				.filter(uri -> Arrays.stream(ports).anyMatch(port -> port == uri.getPort())).toList();
	}

	@Test
	public void testHealdanStartsAndAdminSignsInWhileEveryProviderIsUnreachable() throws Exception {
		// A provider that was there and has gone, and one whose address nothing listens on
		FakeProvider fake = FakeProvider.start();
		fake.asProvider("fake");
		int fakePort = URI.create(fake.issuer()).getPort();
		fake.close();
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		// Closing the fake put the test hooks back: plain HTTP off and CE's sender. With HTTP on, nothing but the
		// network stands between Healdan and the providers
		SignInTestHooks.allowHttp(true);
		OutgoingEmail.sender = emails::add;
		SignInProvider other = SignInProvider.fromPreset("gone", Preset.OTHER);
		SignInProviders.get().save(new SignInProvider("gone", Preset.OTHER, other.label(), true,
				"http://127.0.0.1:" + closedPort, "client-gone", false, other.identityClaim(), other.usernameClaim(),
				other.emailClaim(), other.groupsClaim(), other.linkByEmail()), "secret-gone");
		assertEquals(2, SignInProviders.get().enabled().size());
		SignInProviders.get().saveOptions(SignInProviders.DEFAULT_PASSWORD_LABEL, true, List.of("admin"));
		asked.clear();

		// What a start runs once the context is up: the migrations, the seeds and the alert's registration
		assertTrue(Context.get(HealdanStartup.class).run());

		Client client = new Client("c1", "192.0.2.7", "office.firm.example");
		Session session = SessionManager.get().newSession("admin", "admin", client);
		assertNotNull(session);
		assertEquals("admin", session.getUsername());
		for (long deadline = System.currentTimeMillis() + 10000L; emails.isEmpty()
				&& System.currentTimeMillis() < deadline;)
			Thread.sleep(50L);
		assertEquals(1, emails.size());
		assertEquals("Healdan - admin signed in with its password", emails.get(0).getSubject());

		assertEquals(List.of(), providerRequests(fakePort, closedPort));

		// The watch does see a provider request: the Sign-In tab's check asks for the discovery document
		ProviderClient.Check check = ProviderClient.get().check(SignInProviders.get().find("gone").orElseThrow(),
				SignInProviders.callback("https://healdan.example", "gone"));
		assertFalse(check.ok());
		assertFalse(providerRequests(fakePort, closedPort).isEmpty());
	}
}
