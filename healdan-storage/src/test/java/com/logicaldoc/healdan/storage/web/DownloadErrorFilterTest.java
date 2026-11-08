// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import static com.logicaldoc.healdan.storage.StorageFixtures.moveToBucket;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.SmallCache;
import com.logicaldoc.healdan.storage.StorageFixtures;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.plugin.PluginException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The filter around stand-in servlets that repeat the order of CE's download servlets, with the real
 * {@code HealdanStorage} behind them. The real {@code /download} and its {@code 503} are checked on the stack.
 */
public class DownloadErrorFilterTest extends AbstractStorageTestCase {

	private static final String SENT = "inline; filename=\"big.bin\"";

	private HealdanFixtures fx;

	private Side cloud;

	private byte[] content;

	private StoreResource resource;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			StorageFixtures sf = new StorageFixtures();
			fx = sf.fx;
			cloud = sf.s3Side("Cloud", false);
			// Over a tenth of this cache, so the file is read from the bucket as it goes
			SmallCache.megabytes(20);
			content = new byte[3 << 20];
			new Random(9).nextBytes(content);
			Document doc = fx.upload(cloud.folder(), "big.bin", content, fx.admin());
			moveToBucket(cloud, doc.getId(), "1.0");
			resource = resource(doc, "1.0");
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** What a servlet does with the response */
	@FunctionalInterface
	private interface Servlet {
		void run(HttpServletResponse response) throws IOException;
	}

	/** Repeats ServletUtil.downloadDocument's order, then DownloadServlet's sendError(500) */
	private Servlet downloadLike(boolean outageAfterFirstChunk) {
		return response -> {
			boolean failed = false;
			OutputStream out = response.getOutputStream();
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("application/octet-stream");
			response.setHeader("Content-Disposition", SENT);
			try (InputStream in = Store.get().getStream(resource)) {
				byte[] buffer = new byte[64 << 10];
				for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
					out.write(buffer, 0, n);
					if (outageAfterFirstChunk)
						cloud.s3().unreachable(true);
				}
			} catch (IOException e) {
				failed = true;
			} finally {
				out.close();
			}
			if (failed)
				sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		};
	}

	/** Repeats TicketDownload's order: the file's stream, the headers, the response's stream, the read, close in finally */
	private Servlet ticketLike(Runnable afterOpening) {
		return response -> {
			InputStream in;
			try {
				in = Store.get().getStream(resource);
			} catch (IOException e) {
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Ticket not authorized");
				return;
			}
			afterOpening.run();
			response.setContentType("application/octet-stream");
			response.setHeader("Content-Disposition", SENT);
			OutputStream out = response.getOutputStream();
			boolean failed = false;
			try (in) {
				in.transferTo(out);
			} catch (IOException e) {
				failed = true;
			} finally {
				out.close();
			}
			if (failed)
				sendError(response, HttpServletResponse.SC_UNAUTHORIZED);
		};
	}

	/** As the container takes it: an error after the response was committed is refused, and the response stays as sent */
	private static void sendError(HttpServletResponse response, int status) throws IOException {
		try {
			response.sendError(status);
		} catch (IllegalStateException committed) {
			// Nothing to send
		}
	}

	private static MockHttpServletResponse run(DownloadErrorFilter filter, Servlet servlet,
			AtomicReference<jakarta.servlet.ServletResponse> seen) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (request, wrapped) -> {
			if (seen != null)
				seen.set(wrapped);
			try {
				new HttpServlet() {
					private static final long serialVersionUID = 1L;

					@Override
					protected void service(HttpServletRequest req, HttpServletResponse res)
							throws ServletException, IOException {
						servlet.run(res);
					}
				}.service(request, wrapped);
			} catch (ServletException e) {
				throw e;
			}
		};
		filter.doFilter(new MockHttpServletRequest("GET", "/download"), response, chain);
		return response;
	}

	private static MockHttpServletResponse run(Servlet servlet) throws Exception {
		return run(new DownloadErrorFilter(), servlet, null);
	}

	private void assertUnreachablePage(MockHttpServletResponse response) throws Exception {
		assertEquals(503, response.getStatus());
		assertTrue(response.getContentType(), response.getContentType().startsWith("text/html"));
		assertNull("the download's headers are dropped", response.getHeader("Content-Disposition"));
		String page = response.getContentAsString(StandardCharsets.UTF_8);
		assertTrue(page, page.contains("This file is in Cloud&#39;s cloud storage, which can&#39;t be reached right now."));
		assertEquals(page.getBytes(StandardCharsets.UTF_8).length, response.getContentLength());
	}

	@Test
	public void testDownloadFailingBeforeFirstByteIs503() throws Exception {
		cloud.s3().unreachable(true);

		assertUnreachablePage(run(downloadLike(false)));
	}

	@Test
	public void testTicketFailingBeforeFirstByteIs503() throws Exception {
		// Failing while its file's stream opens, which the servlet answers 401
		cloud.s3().unreachable(true);
		assertUnreachablePage(run(ticketLike(() -> {
		})));
		cloud.s3().unreachable(false);

		// And failing on the first read, after the stream opened, which the servlet answers after an empty 200
		assertUnreachablePage(run(ticketLike(() -> cloud.s3().unreachable(true))));
	}

	@Test
	public void testFailingAfterFirstBytesIsCutOff() throws Exception {
		MockHttpServletResponse response = run(downloadLike(true));

		assertEquals("as before: the 200 it had committed", 200, response.getStatus());
		assertEquals(SENT, response.getHeader("Content-Disposition"));
		byte[] sent = response.getContentAsByteArray();
		assertTrue(sent.length > 0 && sent.length < content.length);
		assertArrayEquals(java.util.Arrays.copyOf(content, sent.length), sent);
		assertFalse(response.getContentAsString().contains("Storage Unreachable"));
	}

	@Test
	public void testWorkingReadIsUntouched() throws Exception {
		AtomicReference<jakarta.servlet.ServletResponse> seen = new AtomicReference<>();

		MockHttpServletResponse response = run(new DownloadErrorFilter(), downloadLike(false), seen);

		assertEquals(200, response.getStatus());
		assertEquals("application/octet-stream", response.getContentType());
		assertEquals(SENT, response.getHeader("Content-Disposition"));
		assertArrayEquals(content, response.getContentAsByteArray());

		MockHttpServletResponse ticket = run(ticketLike(() -> {
		}));
		assertEquals(200, ticket.getStatus());
		assertArrayEquals(content, ticket.getContentAsByteArray());
	}

	@Test
	public void testPasswordChallengePassesThrough() throws Exception {
		MockHttpServletResponse response = run(r -> {
			r.setHeader("WWW-Authenticate", "Basic realm=\"Healdan\"");
			r.sendError(HttpServletResponse.SC_UNAUTHORIZED);
		});

		assertEquals(401, response.getStatus());
		assertEquals("Basic realm=\"Healdan\"", response.getHeader("WWW-Authenticate"));
	}

	@Test
	public void testAServletThatLetsTheOutageThroughIs503() throws Exception {
		cloud.s3().unreachable(true);

		assertUnreachablePage(run(response -> {
			response.setHeader("Content-Disposition", SENT);
			Store.get().getBytes(resource);
		}));
	}

	@Test
	public void testAnotherStoreChangesNothing() throws Exception {
		cloud.s3().unreachable(true);
		DownloadErrorFilter inactive = new DownloadErrorFilter() {
			@Override
			protected boolean active() {
				return false;
			}
		};
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		inactive.doFilter(new MockHttpServletRequest("GET", "/download"), response, chain);

		assertSame("the servlet gets the server's own response", response, chain.getResponse());
	}

	@Test
	public void testPageEscapesTheMessage() {
		String page = DownloadErrorFilter.page("This file is in <script>alert(\"x\")</script> & Co's cloud storage");

		assertFalse(page, page.contains("<script>"));
		assertTrue(page, page.contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; Co&#39;s"));
	}
}
