package com.logicaldoc.core.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import javax.net.ssl.SSLException;

import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.ImpersonatingProxy;
import com.logicaldoc.core.LogCapture;

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

	@Test
	public void testSyncDBDoesNotLogLicenseKey() throws Exception {
		try (ImpersonatingProxy proxy = new ImpersonatingProxy("download.maxmind.com", "{}");
				LogCapture log = new LogCapture(Geolocation.class)) {
			proxy.configureContext();

			assertThrows(SSLException.class, () -> Geolocation.get().syncDB("the-license-key"));
			for (String message : log.getMessages())
				assertFalse(message, message.contains("the-license-key"));
		}
	}
}
