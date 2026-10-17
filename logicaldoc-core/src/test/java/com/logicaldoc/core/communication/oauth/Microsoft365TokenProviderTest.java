package com.logicaldoc.core.communication.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.util.Base64;

import javax.net.ssl.SSLException;

import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.ImpersonatingProxy;
import com.logicaldoc.core.LogCapture;

public class Microsoft365TokenProviderTest extends AbstractCoreTestCase {

	@Test
	public void testRejectsServerWithUntrustedCertificate() throws Exception {
		try (ImpersonatingProxy proxy = new ImpersonatingProxy("login.microsoftonline.com",
				"{\"access_token\":\"c3RvbGVu\"}")) {
			proxy.configureContext();

			assertThrows(SSLException.class,
					() -> new Microsoft365TokenProvider().getAccessToken("the-secret", "client", "tenant"));
			assertNull(proxy.getRequest());
		}
	}

	@Test
	public void testDoesNotLogAccessToken() throws Exception {
		String token = "eyJhbGciOiJSUzI1NiJ9."
				+ Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"the-subject\"}".getBytes())
				+ "." + "c2lnbmF0dXJl".repeat(20);

		// Trusted, it stands in for the real endpoint
		try (ImpersonatingProxy proxy = new ImpersonatingProxy("login.microsoftonline.com",
				"{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"%s\"}".formatted(token));
				LogCapture log = new LogCapture(Microsoft365TokenProvider.class)) {
			proxy.configureContext();
			proxy.trust(new File("target/truststore.p12"));

			assertEquals(token, new Microsoft365TokenProvider().getAccessToken("the-secret", "client", "tenant"));
			for (String message : log.getMessages()) {
				assertFalse(message, message.contains(token.substring(token.length() - 40)));
				assertFalse(message, message.contains("the-subject"));
			}
		}
	}
}
