package com.logicaldoc.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * An HTTP proxy that answers a CONNECT itself and impersonates the requested
 * server with a self-signed certificate, as an attacker on the network would.
 * A client that checks certificates aborts the handshake. One that doesn't
 * sends its request, which the proxy records and answers with a fixed body.
 * It serves a single connection.
 */
public class ImpersonatingProxy implements AutoCloseable {

	private static final char[] PASSWORD = "changeit".toCharArray();

	private static final String TRUST_STORE = "javax.net.ssl.trustStore";

	private static final String TRUST_STORE_PASSWORD = "javax.net.ssl.trustStorePassword";

	private static final String TRUST_STORE_TYPE = "javax.net.ssl.trustStoreType";

	private final ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());

	private final KeyStore keyStore;

	private final AtomicReference<String> request = new AtomicReference<>();

	private final Thread thread;

	private boolean configured = false;

	private final Map<String, String> savedTrustStore = new HashMap<>();

	/**
	 * @param host the host name the certificate is issued to
	 * @param responseBody what the proxy answers, as JSON
	 */
	public ImpersonatingProxy(String host, String responseBody) throws Exception {
		keyStore = selfSignedKeyStore(host);
		thread = new Thread(() -> serve(responseBody));
		thread.start();
	}

	public String getHost() {
		return socket.getInetAddress().getHostAddress();
	}

	public int getPort() {
		return socket.getLocalPort();
	}

	/**
	 * Points the application's proxy settings at this proxy, until it is
	 * closed
	 */
	public void configureContext() {
		ContextProperties config = Context.get().getConfig();
		config.setProperty("proxy.enabled", "true");
		config.setProperty("proxy.host", getHost());
		config.setProperty("proxy.port", Integer.toString(getPort()));
		config.setProperty("proxy.username", "");
		configured = true;
	}

	/**
	 * Until this proxy is closed, replaces the JVM's trust store with one that
	 * trusts only the certificate this proxy presents, the way an
	 * administrator adds a server's private certificate
	 *
	 * @param file where to save the trust store
	 */
	public void trust(File file) throws Exception {
		KeyStore trustStore = KeyStore.getInstance("PKCS12");
		trustStore.load(null, null);
		trustStore.setCertificateEntry("impersonated", keyStore.getCertificate("server"));
		try (OutputStream out = new FileOutputStream(file)) {
			trustStore.store(out, PASSWORD);
		}

		for (String property : List.of(TRUST_STORE, TRUST_STORE_PASSWORD, TRUST_STORE_TYPE))
			savedTrustStore.put(property, System.getProperty(property));
		System.setProperty(TRUST_STORE, file.getAbsolutePath());
		System.setProperty(TRUST_STORE_PASSWORD, new String(PASSWORD));
		System.setProperty(TRUST_STORE_TYPE, "PKCS12");
	}

	/**
	 * Waits for the client to finish
	 *
	 * @return the request the client sent through the tunnel, head and body,
	 *         or null if it aborted the handshake
	 */
	public String getRequest() throws InterruptedException {
		thread.join(10000);
		return request.get();
	}

	@Override
	public void close() throws IOException {
		socket.close();
		if (configured)
			Context.get().getConfig().setProperty("proxy.enabled", "false");
		for (Map.Entry<String, String> property : savedTrustStore.entrySet())
			if (property.getValue() == null)
				System.clearProperty(property.getKey());
			else
				System.setProperty(property.getKey(), property.getValue());
	}

	private void serve(String responseBody) {
		try (Socket client = socket.accept()) {
			client.setSoTimeout(10000);
			readHead(client.getInputStream());
			client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes());
			client.getOutputStream().flush();

			KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keyManagers.init(keyStore, PASSWORD);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(keyManagers.getKeyManagers(), null, null);

			try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(client, null, true)) {
				tls.setUseClientMode(false);
				String head = readHead(tls.getInputStream());
				Matcher length = Pattern.compile("(?i)content-length: *(\\d+)").matcher(head);
				int bodyLength = length.find() ? Integer.parseInt(length.group(1)) : 0;
				request.set(head + new String(tls.getInputStream().readNBytes(bodyLength), StandardCharsets.UTF_8));

				byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
				OutputStream out = tls.getOutputStream();
				out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n"
						+ "Connection: close\r\n\r\n").formatted(body.length).getBytes());
				out.write(body);
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

	private static KeyStore selfSignedKeyStore(String host) throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keys = generator.generateKeyPair();

		X500Name name = new X500Name("CN=" + host);
		Date now = new Date();
		Certificate certificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
				name, BigInteger.ONE, now, new Date(now.getTime() + 86400000L), name, keys.getPublic())
				.addExtension(Extension.subjectAlternativeName, false,
						new GeneralNames(new GeneralName(GeneralName.dNSName, host)))
				.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));

		KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(null, null);
		store.setKeyEntry("server", keys.getPrivate(), PASSWORD, new Certificate[] { certificate });
		return store;
	}
}
