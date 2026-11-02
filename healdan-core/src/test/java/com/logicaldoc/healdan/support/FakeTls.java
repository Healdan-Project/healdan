package com.logicaldoc.healdan.support;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * A self-signed certificate for {@code 127.0.0.1}, for {@link FakeProvider}
 * over HTTPS. The client side trusts this one certificate and nothing else,
 * and still checks the address against it, so a test over HTTPS runs the
 * JVM's usual checks. Kept apart from {@code FakeProvider} so that only a test
 * that asks for HTTPS loads BouncyCastle.
 */
final class FakeTls {

	private static final char[] PASSWORD = "fake-tls".toCharArray();

	/** For the fake provider's server */
	final SSLContext server;

	/** For the client: trusts the fake provider's certificate only */
	final SSLSocketFactory trusting;

	private FakeTls(SSLContext server, SSLSocketFactory trusting) {
		this.server = server;
		this.trusting = trusting;
	}

	static FakeTls create() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair pair = generator.generateKeyPair();

			X500Name name = new X500Name("CN=127.0.0.1");
			Instant now = Instant.now();
			JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
					BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minus(Duration.ofDays(1))),
					Date.from(now.plus(Duration.ofDays(1))), name, pair.getPublic());
			builder.addExtension(Extension.subjectAlternativeName, false,
					new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
			X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
					builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));

			KeyStore keys = KeyStore.getInstance("PKCS12");
			keys.load(null, null);
			keys.setKeyEntry("fake", pair.getPrivate(), PASSWORD, new Certificate[] { certificate });
			KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keyManagers.init(keys, PASSWORD);
			SSLContext server = SSLContext.getInstance("TLS");
			server.init(keyManagers.getKeyManagers(), null, null);

			KeyStore trusted = KeyStore.getInstance("PKCS12");
			trusted.load(null, null);
			trusted.setCertificateEntry("fake", certificate);
			TrustManagerFactory trustManagers = TrustManagerFactory
					.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trustManagers.init(trusted);
			SSLContext client = SSLContext.getInstance("TLS");
			client.init(null, trustManagers.getTrustManagers(), null);

			return new FakeTls(server, client.getSocketFactory());
		} catch (Exception e) {
			throw new IllegalStateException("The fake provider's certificate couldn't be made", e);
		}
	}
}
