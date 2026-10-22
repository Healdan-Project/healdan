package com.logicaldoc.core.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.List;

import org.apache.hc.core5.http.NameValuePair;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * Test case for {@link StatsCollector}
 * 
 * @author Marco Meschieri - LogicalDOC
 * @since 7.0
 */
public class StatsCollectorTest extends AbstractCoreTestCase {

	// Instance under test
	private StatsCollector testSubject;

	@Before
	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		testSubject = Context.get(StatsCollector.class);
		testSubject.setUploadStatistics(false);
	}

	@Test
	public void testRun() {
		testSubject.run();

		List<NameValuePair> stats = testSubject.getStatistics();

		assertEquals("9", stats.stream().filter(p -> "docs".equals(p.getName())).findFirst().orElse(null).getValue());
		assertEquals("18",
				stats.stream().filter(p -> "folders".equals(p.getName())).findFirst().orElse(null).getValue());
	}

	@Test
	public void testSendsNothingOut() throws Exception {
		// Healdan: the statistics stay on the server, even with the upload turned on (owner's call, 2026-10-22)
		try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			proxy.setSoTimeout(1000);
			ContextProperties config = Context.get().getConfig();
			config.setProperty("proxy.enabled", "true");
			config.setProperty("proxy.host", proxy.getInetAddress().getHostAddress());
			config.setProperty("proxy.port", Integer.toString(proxy.getLocalPort()));
			try {
				testSubject.setUploadStatistics(true);
				testSubject.run();
				assertThrows(SocketTimeoutException.class, proxy::accept);
			} finally {
				config.setProperty("proxy.enabled", "false");
			}
		}
	}

}