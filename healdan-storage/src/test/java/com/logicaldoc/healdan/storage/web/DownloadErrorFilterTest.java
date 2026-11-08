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
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Random;

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

	private static final byte[] PLACEHOLDER = "%PDF- not available".getBytes(StandardCharsets.US_ASCII);

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

	/** Flips the backend off after the first chunk reaches the client, to fail the read after its first bytes */
	private OutputStream outageAfterFirstChunk(OutputStream out) {
		return new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				out.write(b);
				cloud.s3().unreachable(true);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len);
				cloud.s3().unreachable(true);
			}
		};
	}

	/**
	 * Repeats ServletUtil.downloadDocument's order (headers, the response's stream in a try-with-resources, then
	 * CE's ranged or whole copy, with the Content-Range and 206 a Range request sets), which closes the stream
	 * before its failure leaves.
	 *
	 * @param range start and length of the Range asked for, or null for the whole file
	 */
	private void serve(HttpServletResponse response, long[] range, boolean outageAfterFirstChunk) throws IOException {
		response.setContentType("application/octet-stream");
		response.setHeader("Content-Disposition", SENT);
		response.setHeader("Accept-Ranges", "bytes");
		response.setHeader("ETag", "1_1.0_1");
		response.setHeader("Cache-Control", "no-cache,no-store,must-revalidate");
		try (OutputStream output = response.getOutputStream()) {
			OutputStream to = outageAfterFirstChunk ? outageAfterFirstChunk(output) : output;
			if (range == null)
				Store.get().writeToStream(resource, to);
			else {
				response.setHeader("Content-Range",
						"bytes " + range[0] + "-" + (range[0] + range[1] - 1) + "/" + content.length);
				response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
				Store.get().writeToStream(resource, to, range[0], range[1]);
			}
		}
	}

	/** DownloadServlet: ServletUtil.downloadDocument, then sendError(500) when it fails */
	private Servlet downloadLike(long[] range, boolean outageAfterFirstChunk) {
		return response -> {
			try {
				serve(response, range, outageAfterFirstChunk);
			} catch (IOException e) {
				sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}
		};
	}

	/**
	 * ConvertPdf: ServletUtil.downloadDocument, and when it fails the placeholder, written to the response's stream
	 * again after downloadDocument closed it, with a Content-Disposition of its own
	 */
	private Servlet convertPdfLike() {
		return response -> {
			try {
				serve(response, null, false);
			} catch (Exception r) {
				try (OutputStream os = response.getOutputStream()) {
					response.setHeader("Content-Disposition", "inline; filename=\"notavailable.pdf\"");
					os.write(PLACEHOLDER);
				} catch (Exception e) {
					// As ConvertPdf does
				}
			}
		};
	}

	/**
	 * TicketDownload: the file's stream, the headers, the response's stream, the read byte by byte, flush and close
	 * in finally; and when it fails the 401 through response.getWriter(), whose failure is swallowed
	 */
	private Servlet ticketLike(Runnable afterOpening) {
		return response -> {
			try {
				OutputStream os = null;
				try (InputStream is = Store.get().getStream(resource)) {
					afterOpening.run();
					response.setContentType("application/octet-stream");
					response.setHeader("Content-Disposition", SENT);
					response.setHeader("Accept-Ranges", "bytes");
					response.setHeader("Content-Length", Long.toString(content.length));
					os = response.getOutputStream();
					is.transferTo(os);
				} finally {
					try {
						if (os != null) {
							os.flush();
							os.close();
						}
					} catch (Exception t) {
						// Nothing to do
					}
				}
			} catch (Exception e) {
				try (PrintWriter out = response.getWriter()) {
					response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Ticket not authorized");
				} catch (Exception t) {
					// Nothing to do
				}
			}
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

	private static MockHttpServletResponse run(Servlet servlet) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (request, wrapped) -> new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
				servlet.run(res);
			}
		}.service(request, wrapped);
		new DownloadErrorFilter().doFilter(new MockHttpServletRequest("GET", "/download"), response, chain);
		return response;
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

		MockHttpServletResponse response = run(downloadLike(null, false));

		assertUnreachablePage(response);
		assertNull(response.getHeader("ETag"));
	}

	@Test
	public void testRangedDownloadFailingBeforeFirstByteIs503() throws Exception {
		cloud.s3().unreachable(true);

		MockHttpServletResponse response = run(downloadLike(new long[] { 1000, 5000 }, false));

		assertUnreachablePage(response);
		assertNull("the 206's header is dropped", response.getHeader("Content-Range"));
	}

	@Test
	public void testTicketFailingWhileOpeningIs503() throws Exception {
		// The servlet answers 401 through getWriter()
		cloud.s3().unreachable(true);

		assertUnreachablePage(run(ticketLike(() -> {
		})));
	}

	@Test
	public void testTicketFailingOnFirstReadIs503() throws Exception {
		// After the stream opened: the servlet's own getWriter() is refused, as its stream was taken, and it sends
		// nothing
		assertUnreachablePage(run(ticketLike(() -> cloud.s3().unreachable(true))));
	}

	@Test
	public void testConvertPdfServesItsPlaceholderDuringAnOutage() throws Exception {
		cloud.s3().unreachable(true);

		MockHttpServletResponse response = run(convertPdfLike());

		assertEquals(200, response.getStatus());
		assertArrayEquals(PLACEHOLDER, response.getContentAsByteArray());
		assertEquals("inline; filename=\"notavailable.pdf\"", response.getHeader("Content-Disposition"));
		assertEquals("application/octet-stream", response.getContentType());
		assertNull("not the failed attempt's", response.getHeader("ETag"));
		assertNull(response.getHeader("Content-Length"));
	}

	@Test
	public void testFailingAfterFirstBytesIsCutOff() throws Exception {
		MockHttpServletResponse response = run(downloadLike(null, true));

		assertEquals("as before: the 200 it had committed", 200, response.getStatus());
		assertEquals(SENT, response.getHeader("Content-Disposition"));
		byte[] sent = response.getContentAsByteArray();
		assertTrue(sent.length > 0 && sent.length < content.length);
		assertArrayEquals(java.util.Arrays.copyOf(content, sent.length), sent);
		assertFalse(response.getContentAsString().contains("Storage Unreachable"));
	}

	@Test
	public void testWorkingReadIsUntouched() throws Exception {
		MockHttpServletResponse response = run(downloadLike(null, false));

		assertEquals(200, response.getStatus());
		assertEquals("application/octet-stream", response.getContentType());
		assertEquals(SENT, response.getHeader("Content-Disposition"));
		assertEquals("1_1.0_1", response.getHeader("ETag"));
		assertArrayEquals(content, response.getContentAsByteArray());

		MockHttpServletResponse ticket = run(ticketLike(() -> {
		}));
		assertEquals(200, ticket.getStatus());
		assertEquals(SENT, ticket.getHeader("Content-Disposition"));
		assertEquals(Integer.toString(content.length), ticket.getHeader("Content-Length"));
		assertArrayEquals(content, ticket.getContentAsByteArray());
	}

	@Test
	public void testWorkingRangedReadIsUntouched() throws Exception {
		MockHttpServletResponse response = run(downloadLike(new long[] { 1000, 5000 }, false));

		assertEquals(206, response.getStatus());
		assertEquals("bytes 1000-5999/" + content.length, response.getHeader("Content-Range"));
		assertEquals(SENT, response.getHeader("Content-Disposition"));
		assertArrayEquals(java.util.Arrays.copyOfRange(content, 1000, 6000), response.getContentAsByteArray());
	}

	@Test
	public void testConvertPdfServesTheFileWhenItReads() throws Exception {
		MockHttpServletResponse response = run(convertPdfLike());

		assertEquals(200, response.getStatus());
		assertArrayEquals(content, response.getContentAsByteArray());
		assertEquals(SENT, response.getHeader("Content-Disposition"));
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
