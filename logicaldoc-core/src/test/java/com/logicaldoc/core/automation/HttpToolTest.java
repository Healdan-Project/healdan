package com.logicaldoc.core.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Map;
import java.util.function.Function;

import org.apache.hc.client5.http.entity.mime.AbstractContentBody;
import org.apache.hc.client5.http.entity.mime.StringBody;
import org.apache.hc.core5.http.ContentType;
import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.ImpersonatingProxy;

public class HttpToolTest extends AbstractCoreTestCase {

	private static final String URL = "https://api.example.com/";

	private static final Map<String, String> HEADERS = Map.of("Authorization", "Bearer the-token");

	private static final Map<String, AbstractContentBody> PARTS = Map.of("part",
			new StringBody("value", ContentType.TEXT_PLAIN));

	@Test
	public void testGetRejectsUntrustedCertificate() throws Exception {
		assertNull(callThroughImpersonator(tool -> tool.get(URL, HEADERS, 5)));
	}

	@Test
	public void testDownloadRejectsUntrustedCertificate() throws Exception {
		assertNull(callThroughImpersonator(tool -> tool.download(URL, HEADERS, 5)));
	}

	@Test
	public void testPostRejectsUntrustedCertificate() throws Exception {
		assertNull(callThroughImpersonator(tool -> tool.post(URL, HEADERS, "{}", 5)));
	}

	@Test
	public void testPostFormRejectsUntrustedCertificate() throws Exception {
		assertNull(callThroughImpersonator(tool -> tool.postForm(URL, HEADERS, Map.of("field", "value"), 5)));
	}

	@Test
	public void testPostMultipartRejectsUntrustedCertificate() throws Exception {
		assertNull(callThroughImpersonator(tool -> tool.postMultipart(URL, HEADERS, PARTS, 5)));
	}

	@Test
	public void testMultipartDownloadRejectsUntrustedCertificate() throws Exception {
		assertNull(callThroughImpersonator(tool -> tool.download(URL, HEADERS, PARTS, 5)));
	}

	@Test
	public void testTrustsCertificateAddedToTrustStore() throws Exception {
		try (ImpersonatingProxy proxy = new ImpersonatingProxy("api.example.com", "{}")) {
			proxy.configureContext();
			proxy.trust(new File("target/truststore.p12"));

			assertEquals("{}", new HttpTool().get(URL, HEADERS, 5));
			assertTrue(proxy.getRequest().contains("Bearer the-token"));
		}
	}

	/**
	 * Makes a call through a proxy impersonating the server, checks that it
	 * failed and returns what the proxy got
	 */
	private String callThroughImpersonator(Function<HttpTool, Object> call) throws Exception {
		try (ImpersonatingProxy proxy = new ImpersonatingProxy("api.example.com", "{}")) {
			proxy.configureContext();
			assertNull(call.apply(new HttpTool()));
			return proxy.getRequest();
		}
	}
}
