package com.logicaldoc.core.communication.oauth;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.After;
import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

public class Microsoft365TokenProviderTest extends AbstractCoreTestCase {

	@After
	public void disableProxy() {
		Context.get().getConfig().setProperty("proxy.enabled", "false");
	}

	@Test
	public void testRejectsServerWithUntrustedCertificate() throws Exception {
		// A proxy that answers the CONNECT itself and impersonates
		// login.microsoftonline.com with a self-signed certificate
		AtomicReference<String> intercepted = new AtomicReference<>();
		try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread thread = new Thread(() -> intercept(proxy, intercepted));
			thread.start();

			ContextProperties config = Context.get().getConfig();
			config.setProperty("proxy.enabled", "true");
			config.setProperty("proxy.host", proxy.getInetAddress().getHostAddress());
			config.setProperty("proxy.port", Integer.toString(proxy.getLocalPort()));
			config.setProperty("proxy.username", "");

			assertThrows(SSLException.class,
					() -> new Microsoft365TokenProvider().getAccessToken("the-secret", "client", "tenant"));
			thread.join(10000);
		}

		assertNull(intercepted.get());
	}

	private static void intercept(ServerSocket proxy, AtomicReference<String> intercepted) {
		try (Socket socket = proxy.accept()) {
			socket.setSoTimeout(10000);
			readHead(socket.getInputStream());
			socket.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes());
			socket.getOutputStream().flush();

			try (SSLSocket tls = (SSLSocket) selfSignedContext().getSocketFactory().createSocket(socket, null,
					true)) {
				tls.setUseClientMode(false);
				String head = readHead(tls.getInputStream());
				Matcher length = Pattern.compile("(?i)content-length: *(\\d+)").matcher(head);
				int bodyLength = length.find() ? Integer.parseInt(length.group(1)) : 0;
				intercepted.set(new String(tls.getInputStream().readNBytes(bodyLength), StandardCharsets.UTF_8));

				String body = "{\"access_token\":\"c3RvbGVu\"}";
				OutputStream out = tls.getOutputStream();
				out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n"
						+ "Connection: close\r\n\r\n%s").formatted(body.length(), body).getBytes());
				out.flush();
			}
		} catch (Exception e) {
			// A validating client aborts the handshake
		}
	}

	private static String readHead(InputStream in) throws IOException {
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		while (!head.toString().endsWith("\r\n\r\n")) {
			int b = in.read();
			if (b < 0)
				throw new IOException("Connection closed");
			head.write(b);
		}
		return head.toString();
	}

	private static SSLContext selfSignedContext() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keys = generator.generateKeyPair();

		X500Name name = new X500Name("CN=login.microsoftonline.com");
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
