package com.logicaldoc.core.security;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import javax.net.ssl.SSLException;

import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.ImpersonatingProxy;

public class GeolocationTest extends AbstractCoreTestCase {

	@Test
	public void testSyncDBRejectsServerWithUntrustedCertificate() throws Exception {
		// The license key goes in the download URL
		try (ImpersonatingProxy proxy = new ImpersonatingProxy("download.maxmind.com", "{}")) {
			proxy.configureContext();

			assertThrows(SSLException.class, () -> Geolocation.get().syncDB("the-license-key"));
			assertNull(proxy.getRequest());
		}
	}
}
