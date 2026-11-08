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
import java.util.zip.GZIPOutputStream;

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
import jakarta.servlet.ServletOutputStream;
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
		return run(servlet, new MockHttpServletResponse());
	}

	/** Runs the servlet behind the filter, which wraps the response given, the server's own */
	private static MockHttpServletResponse run(Servlet servlet, MockHttpServletResponse response) throws Exception {
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

	private static final String BOUNDARY = "MULTIPART_BYTERANGES";

	/** The most bytes the filter holds before the response starts */
	private static final int HELD = 1024;

	/**
	 * Repeats ServletUtil.downloadDocument with {@code download.gzip} on: the GZIP stream writes its 10-byte header as
	 * it is made, before the read, and its trailer as it is closed
	 */
	private void serveGzipped(HttpServletResponse response) throws IOException {
		response.setContentType("application/octet-stream");
		response.setHeader("Content-Disposition", SENT);
		try (OutputStream output = new GZIPOutputStream(response.getOutputStream(), 10240)) {
			response.setHeader("Content-Encoding", "gzip");
			Store.get().writeToStream(resource, output);
		}
	}

	/** Repeats ServletUtil.downloadDocument for several ranges: each one's boundary lines go before its read */
	private void serveRanges(HttpServletResponse response, long[][] ranges) throws IOException {
		response.setContentType("application/octet-stream");
		response.setHeader("Content-Disposition", SENT);
		try (OutputStream output = response.getOutputStream()) {
			response.setContentType("multipart/byteranges; boundary=" + BOUNDARY);
			response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
			ServletOutputStream sos = (ServletOutputStream) output;
			for (long[] range : ranges) {
				sos.println();
				sos.println("--" + BOUNDARY);
				sos.println("Content-Type: application/octet-stream");
				sos.println("Content-Range: bytes " + range[0] + "-" + (range[0] + range[1] - 1) + "/" + content.length);
				Store.get().writeToStream(resource, sos, range[0], range[1]);
				sos.println();
				sos.println("--" + BOUNDARY + "--");
			}
		}
	}

	private static byte[] random(int length, int seed) {
		byte[] bytes = new byte[length];
		new Random(seed).nextBytes(bytes);
		return bytes;
	}

	@Test
	public void testGzipHeaderBeforeAnOutageIs503() throws Exception {
		cloud.s3().unreachable(true);

		MockHttpServletResponse response = run(r -> {
			try {
				serveGzipped(r);
			} catch (IOException e) {
				sendError(r, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}
		});

		assertUnreachablePage(response);
		assertNull("nothing the servlet wrote reaches the client", response.getHeader("Content-Encoding"));
		assertTrue(response.getContentAsString(StandardCharsets.UTF_8).startsWith("<!DOCTYPE html>"));
	}

	@Test
	public void testMultipartBoundariesBeforeAnOutageAre503() throws Exception {
		cloud.s3().unreachable(true);

		MockHttpServletResponse response = run(r -> {
			try {
				serveRanges(r, new long[][] { { 0, 100 }, { 2000, 100 } });
			} catch (IOException e) {
				sendError(r, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}
		});

		assertUnreachablePage(response);
		String page = response.getContentAsString(StandardCharsets.UTF_8);
		assertTrue(page, page.startsWith("<!DOCTYPE html>"));
		assertFalse(page, page.contains(BOUNDARY));
	}

	@Test
	public void testSmallFileIsSentIntactWhenTheServletEnds() throws Exception {
		byte[] small = random(700, 3);
		Document doc = fx.upload(cloud.folder(), "small.bin", small, fx.admin());
		moveToBucket(cloud, doc.getId(), "1.0");
		StoreResource smallFile = resource(doc, "1.0");
		// A server that sends each byte as it is written
		MockHttpServletResponse real = new MockHttpServletResponse();
		real.setBufferSize(1);
		boolean[] committed = new boolean[1];
		int[] sent = new int[1];

		run(r -> {
			r.setContentType("application/octet-stream");
			r.setHeader("Content-Disposition", SENT);
			r.setHeader("ETag", "1_1.0_1");
			try (OutputStream out = r.getOutputStream()) {
				Store.get().writeToStream(smallFile, out);
			}
			committed[0] = real.isCommitted();
			sent[0] = real.getContentAsByteArray().length;
		}, real);

		assertFalse("held while the servlet runs", committed[0]);
		assertEquals(0, sent[0]);
		assertEquals(200, real.getStatus());
		assertEquals("application/octet-stream", real.getContentType());
		assertEquals(SENT, real.getHeader("Content-Disposition"));
		assertEquals("1_1.0_1", real.getHeader("ETag"));
		assertArrayEquals(small, real.getContentAsByteArray());
	}

	@Test
	public void testWritePastTheHeldBytesSendsThemFirst() throws Exception {
		byte[] head = random(1000, 4);
		byte[] singles = random(HELD - head.length, 5);
		byte[] tail = random(76, 6);
		MockHttpServletResponse real = new MockHttpServletResponse();
		real.setBufferSize(1);
		boolean[] committed = new boolean[2];
		int[] status = new int[1];
		String[] range = new String[1];
		byte[][] sent = new byte[2][];

		run(r -> {
			r.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
			r.setHeader("Content-Range", "bytes 0-1099/1100");
			ServletOutputStream out = r.getOutputStream();
			out.write(head);
			for (byte b : singles)
				out.write(b);
			// 1,024 bytes, the most it holds
			committed[0] = real.isCommitted();
			sent[0] = real.getContentAsByteArray();
			out.write(tail);
			committed[1] = real.isCommitted();
			status[0] = real.getStatus();
			range[0] = real.getHeader("Content-Range");
			sent[1] = real.getContentAsByteArray();
			out.close();
		}, real);

		assertFalse(committed[0]);
		assertEquals(0, sent[0].length);
		assertTrue("committed before the servlet ends", committed[1]);
		assertEquals(206, status[0]);
		assertEquals("bytes 0-1099/1100", range[0]);
		byte[] all = new byte[1100];
		System.arraycopy(head, 0, all, 0, head.length);
		System.arraycopy(singles, 0, all, head.length, singles.length);
		System.arraycopy(tail, 0, all, HELD, tail.length);
		assertArrayEquals("what was held, in order, then the write", all, sent[1]);
		assertArrayEquals(all, real.getContentAsByteArray());
	}

	@Test
	public void testFlushBeforeTheHeldBytesCommitsNothing() throws Exception {
		byte[] header = random(10, 7);
		byte[] rest = random(200, 8);
		MockHttpServletResponse real = new MockHttpServletResponse();
		boolean[] committed = new boolean[1];
		int[] sent = new int[1];

		run(r -> {
			r.setContentType("application/octet-stream");
			ServletOutputStream out = r.getOutputStream();
			out.write(header);
			out.flush();
			r.flushBuffer();
			committed[0] = real.isCommitted();
			sent[0] = real.getContentAsByteArray().length;
			out.write(rest);
		}, real);

		assertFalse(committed[0]);
		assertEquals(0, sent[0]);
		assertTrue("the flushes are sent with the rest", real.isCommitted());
		byte[] all = new byte[210];
		System.arraycopy(header, 0, all, 0, 10);
		System.arraycopy(rest, 0, all, 10, 200);
		assertArrayEquals(all, real.getContentAsByteArray());
	}

	@Test
	public void testConvertPdfPlaceholderReplacesAnAttemptThatHeldBytes() throws Exception {
		cloud.s3().unreachable(true);

		// ConvertPdf with download.gzip on: the failed attempt held a GZIP header and trailer
		MockHttpServletResponse response = run(r -> {
			try {
				serveGzipped(r);
			} catch (Exception e) {
				try (OutputStream os = r.getOutputStream()) {
					r.setHeader("Content-Disposition", "inline; filename=\"notavailable.pdf\"");
					os.write(PLACEHOLDER);
				} catch (Exception t) {
					// As ConvertPdf does
				}
			}
		});

		assertEquals(200, response.getStatus());
		assertArrayEquals(PLACEHOLDER, response.getContentAsByteArray());
		assertEquals("inline; filename=\"notavailable.pdf\"", response.getHeader("Content-Disposition"));
		assertNull("not the failed attempt's", response.getHeader("Content-Encoding"));
	}
}
