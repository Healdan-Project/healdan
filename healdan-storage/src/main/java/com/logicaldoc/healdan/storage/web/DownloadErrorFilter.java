// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.storage.HealdanStorage;
import com.logicaldoc.healdan.storage.ReadOutages;
import com.logicaldoc.healdan.util.StorageUnreachableException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Answers a download of a file whose storage can't be reached with {@code 503} and a small page that says so
 * (spec section 6.5). CE's download servlets open the response's stream and send {@code 200} before they read the file,
 * and when the read fails they log it and send an error of their own, which Tomcat shows as a bare page, or, for
 * shared links, after an empty {@code 200} was committed. So the servlets get a response that commits nothing until
 * more than its first 1,024 bytes are written: the status, the headers, those bytes and the stream's
 * closing are kept in order, and sent when more is written or the servlet is done. The bytes held cover what CE writes
 * before it reads the file, a gzip header when {@code download.gzip} is on and a multipart range's boundaries. If a
 * read failed with a {@link StorageUnreachableException} before anything was sent, whatever the servlet then did is
 * dropped and the {@code 503} is sent; otherwise what the servlet did is sent as it did it, byte for byte. A read that
 * fails after the file's first bytes were sent is cut off, as before.
 * <p>
 * Everything passes through untouched unless the Store is a {@link HealdanStorage}, so a WAR still on CE's
 * {@code FSStore} is unchanged. The filter covers the routes that open a document's file and send it ({@code /download},
 * {@code /convertpdf} and shared links, {@code /download-ticket}), and not the others the plan leaves out: they don't
 * commit a {@code 200} before they read.
 */
@WebFilter(urlPatterns = { "/download", "/download/*", "/convertpdf/*", "/download-ticket" })
public class DownloadErrorFilter implements Filter {

	private static final Logger log = LoggerFactory.getLogger(DownloadErrorFilter.class);

	/** The most bytes a response holds before it starts: more than a gzip header or a multipart range's boundaries */
	static final int HELD_BYTES = 1024;

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(response instanceof HttpServletResponse http) || !active()) {
			chain.doFilter(request, response);
			return;
		}

		HoldingResponse held = new HoldingResponse(http);
		StorageUnreachableException[] noted = new StorageUnreachableException[1];
		ReadOutages.arm();
		try {
			try {
				chain.doFilter(request, held);
			} finally {
				// Also when the chain throws an Error, so a pooled thread is never left armed
				noted[0] = ReadOutages.disarm();
			}
		} catch (IOException | ServletException | RuntimeException e) {
			StorageUnreachableException outage = noted[0] != null ? noted[0] : StorageUnreachableException.in(e);
			if (outage == null || held.started())
				throw e;
			unreachable(http, held, outage);
			return;
		}
		if (noted[0] != null && !held.started())
			unreachable(http, held, noted[0]);
		else
			held.release();
	}

	/** @return whether the Store is Healdan's; a seam for tests */
	protected boolean active() {
		return Store.get() instanceof HealdanStorage;
	}

	private static void unreachable(HttpServletResponse response, HoldingResponse held, StorageUnreachableException outage)
			throws IOException {
		held.drop();
		if (response.isCommitted())
			return;
		byte[] page = page(outage.getMessage()).getBytes(StandardCharsets.UTF_8);
		response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		response.setContentType("text/html;charset=UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.setContentLength(page.length);
		try (OutputStream out = response.getOutputStream()) {
			out.write(page);
		}
	}

	/** @return the 503 page, with the message escaped as HTML */
	static String page(String message) {
		String text = escape(message == null ? StorageUnreachableException.message(null, false) : message);
		return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>Storage Unreachable</title></head>"
				+ "<body><h1>Storage Unreachable</h1><p>" + text + "</p><p>Try again in a few minutes.</p></body></html>\n";
	}

	private static String escape(String text) {
		StringBuilder escaped = new StringBuilder(text.length() + 16);
		for (char c : text.toCharArray())
			switch (c) {
			case '&' -> escaped.append("&amp;");
			case '<' -> escaped.append("&lt;");
			case '>' -> escaped.append("&gt;");
			case '"' -> escaped.append("&quot;");
			case '\'' -> escaped.append("&#39;");
			default -> escaped.append(c);
			}
		return escaped.toString();
	}

	/**
	 * A response that sends nothing to the real one until more than 1,024 bytes are
	 * written: what the servlet does before then is kept in order, and sent by {@link #release()} or dropped by
	 * {@link #drop()}.
	 */
	private static final class HoldingResponse extends HttpServletResponseWrapper {

		private final HttpServletResponse real;

		private final List<Held> held = new ArrayList<>();

		private boolean started;

		private HoldingStream stream;

		HoldingResponse(HttpServletResponse real) {
			super(real);
			this.real = real;
		}

		/** One thing the servlet did, which can be repeated on the real response */
		@FunctionalInterface
		private interface Step {
			void run() throws IOException;
		}

		/** A held step, with the name of the header it sets, or null */
		private record Held(String header, Step step) {
		}

		/** How many steps were held when the servlet closed its stream before any byte, the close among them; else -1 */
		private int closedAt = -1;

		/** Whether the servlet asked for a writer */
		private boolean writerTaken;

		/** The bytes held, which a write that would pass {@link DownloadErrorFilter#HELD_BYTES} sends with the rest */
		private int heldBytes;

		boolean started() {
			return started;
		}

		/** Sends what is held, in order, as the servlet did it */
		void release() throws IOException {
			List<Held> steps = new ArrayList<>(held);
			held.clear();
			started = true;
			for (Held step : steps)
				try {
					step.step().run();
				} catch (IllegalStateException e) {
					// As the servlet would have been told, had the response been committed by then
					log.debug("Replaying a download's response: {}", e.getMessage());
				}
		}

		/** Forgets what is held */
		void drop() {
			held.clear();
			closedAt = -1;
			heldBytes = 0;
		}

		/**
		 * Forgets what a failed attempt to send the file held up to its close (status, error, headers and the close
		 * itself) when the servlet goes on to write other bytes, as {@code /convertpdf} does with its placeholder,
		 * which sets the headers it needs itself after the close. Only the content type stays: the attempt was for a
		 * file of the type the fallback replaces.
		 */
		void dropFailedAttempt() {
			List<Held> attempt = held.subList(0, closedAt);
			attempt.removeIf(h -> !"Content-Type".equalsIgnoreCase(h.header()));
			closedAt = -1;
		}

		private void hold(Step step) throws IOException {
			hold(null, step);
		}

		private void hold(String header, Step step) throws IOException {
			if (started)
				step.run();
			else
				held.add(new Held(header, step));
		}

		private void holdQuietly(Runnable step) {
			holdQuietly(null, step);
		}

		private void holdQuietly(String header, Runnable step) {
			if (started)
				step.run();
			else
				held.add(new Held(header, step::run));
		}

		@Override
		public void setStatus(int sc) {
			holdQuietly(() -> real.setStatus(sc));
		}

		@Override
		public void sendError(int sc) throws IOException {
			hold(() -> real.sendError(sc));
		}

		@Override
		public void sendError(int sc, String msg) throws IOException {
			hold(() -> real.sendError(sc, msg));
		}

		@Override
		public void sendRedirect(String location) throws IOException {
			release();
			real.sendRedirect(location);
		}

		@Override
		public void setHeader(String name, String value) {
			holdQuietly(name, () -> real.setHeader(name, value));
		}

		@Override
		public void addHeader(String name, String value) {
			holdQuietly(() -> real.addHeader(name, value));
		}

		@Override
		public void setIntHeader(String name, int value) {
			holdQuietly(() -> real.setIntHeader(name, value));
		}

		@Override
		public void addIntHeader(String name, int value) {
			holdQuietly(() -> real.addIntHeader(name, value));
		}

		@Override
		public void setDateHeader(String name, long date) {
			holdQuietly(() -> real.setDateHeader(name, date));
		}

		@Override
		public void addDateHeader(String name, long date) {
			holdQuietly(() -> real.addDateHeader(name, date));
		}

		@Override
		public void addCookie(Cookie cookie) {
			holdQuietly(() -> real.addCookie(cookie));
		}

		@Override
		public void setContentType(String type) {
			holdQuietly("Content-Type", () -> real.setContentType(type));
		}

		@Override
		public void setCharacterEncoding(String charset) {
			holdQuietly(() -> real.setCharacterEncoding(charset));
		}

		@Override
		public void setContentLength(int len) {
			holdQuietly(() -> real.setContentLength(len));
		}

		@Override
		public void setContentLengthLong(long len) {
			holdQuietly(() -> real.setContentLengthLong(len));
		}

		@Override
		public void flushBuffer() throws IOException {
			hold(real::flushBuffer);
		}

		@Override
		public ServletOutputStream getOutputStream() throws IOException {
			if (writerTaken)
				throw new IllegalStateException("getWriter() has already been called for this response");
			if (stream == null)
				stream = new HoldingStream();
			return stream;
		}

		@Override
		public PrintWriter getWriter() throws IOException {
			// As Tomcat does: CE's TicketDownload relies on it to find that its stream was taken, and then sends
			// nothing, where it would send a 401 for a file that is only out of reach
			if (stream != null)
				throw new IllegalStateException("getOutputStream() has already been called for this response");
			writerTaken = true;
			return new PrintWriter(new HoldingWriter());
		}

		/** A writer that opens the real one at its first character, and keeps a flush or close before it */
		private final class HoldingWriter extends java.io.Writer {

			@Override
			public void write(char[] chars, int off, int len) throws IOException {
				if (len == 0)
					return;
				if (!started)
					release();
				real.getWriter().write(chars, off, len);
			}

			@Override
			public void flush() throws IOException {
				hold(() -> real.getWriter().flush());
			}

			@Override
			public void close() throws IOException {
				hold(() -> real.getWriter().close());
			}
		}

		/**
		 * A stream that opens the real one once more than 1,024 bytes are written,
		 * and keeps the bytes and a close before then
		 */
		private final class HoldingStream extends ServletOutputStream {

			private ServletOutputStream out() throws IOException {
				return real.getOutputStream();
			}

			/** @return whether the file couldn't be read and the servlet now writes something else after closing */
			private boolean afterFailedAttempt() {
				return closedAt >= 0 && ReadOutages.noted() != null;
			}

			private void start() throws IOException {
				if (started)
					return;
				if (afterFailedAttempt())
					// The file could not be read and the servlet wrote something else (CE's /convertpdf, its
					// placeholder), to the stream it had closed: that starts the response afresh
					dropFailedAttempt();
				release();
			}

			/**
			 * @return whether these bytes are held, counted: before anything is sent, and up to
			 *         1,024 bytes; never what the servlet writes after a failed attempt,
			 *         which starts the response
			 */
			private boolean holds(int len) {
				if (started || heldBytes + len > HELD_BYTES || afterFailedAttempt())
					return false;
				heldBytes += len;
				return true;
			}

			@Override
			public void write(int b) throws IOException {
				if (holds(1)) {
					held.add(new Held(null, () -> out().write(b)));
					return;
				}
				start();
				out().write(b);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				if (len == 0)
					return;
				if (holds(len)) {
					byte[] bytes = Arrays.copyOfRange(b, off, off + len);
					held.add(new Held(null, () -> out().write(bytes)));
					return;
				}
				start();
				out().write(b, off, len);
			}

			@Override
			public void flush() throws IOException {
				hold(() -> out().flush());
			}

			@Override
			public void close() throws IOException {
				if (!started && closedAt < 0)
					closedAt = held.size() + 1;
				hold(() -> out().close());
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setWriteListener(WriteListener listener) {
				throw new UnsupportedOperationException("Downloads are written in blocking mode");
			}
		}
	}
}
