package com.logicaldoc.util.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Date;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

public class HttpUtilTest {

	private HttpsServer server;

	private String url;

	@Before
	public void startServer() throws Exception {
		server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setHttpsConfigurator(new HttpsConfigurator(selfSignedContext()));
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(200, 2);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write("ok".getBytes());
			}
		});
		server.start();
		// An IP, because HttpClient tries the next address of a name when the
		// handshake fails, and would report that one's error instead
		url = "https://127.0.0.1:%d/".formatted(server.getAddress().getPort());
	}

	@After
	public void stopServer() {
		server.stop(0);
	}

	@Test
	public void testNotValidatingClientAcceptsAnyCertificate() throws IOException {
		try (CloseableHttpClient client = HttpUtil.getNotValidatingClient(5)) {
			assertEquals(200, (int) client.execute(new HttpGet(url), response -> response.getCode()));
		}
	}

	@Test
	public void testValidatingClientRejectsUntrustedCertificate() throws IOException {
		try (CloseableHttpClient client = HttpUtil.getValidatingClient(5)) {
			assertThrows(SSLException.class, () -> client.execute(new HttpGet(url), response -> response.getCode()));
		}
	}

	/**
	 * A TLS context presenting a self-signed certificate
	 */
	static SSLContext selfSignedContext() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keys = generator.generateKeyPair();

		X500Name name = new X500Name("CN=localhost");
		Date now = new Date();
		Certificate certificate = new JcaX509CertificateConverter()
				.getCertificate(new JcaX509v3CertificateBuilder(name, BigInteger.ONE, now,
						new Date(now.getTime() + 86400000L), name, keys.getPublic())
						.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));

		char[] password = "changeit".toCharArray();
		KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(null, null);
		store.setKeyEntry("server", keys.getPrivate(), password, new Certificate[] { certificate });

		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(store, password);
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(keyManagers.getKeyManagers(), null, null);
		return context;
	}
}
