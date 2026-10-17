package com.logicaldoc.core.communication.oauth;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import javax.net.ssl.SSLException;

import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.ImpersonatingProxy;

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
}
