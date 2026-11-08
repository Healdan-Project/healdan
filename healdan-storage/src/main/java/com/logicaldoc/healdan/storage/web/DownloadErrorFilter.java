// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * Answers a download of a file whose cloud storage can't be reached with {@code 503} and a small page that says so
 * (spec section 6.5). CE's download servlets open the response's stream and send {@code 200} before they read the file,
 * and when the read fails they log it and send an error of their own, which Tomcat shows as a bare page, or, for
 * shared links, after an empty {@code 200} was committed. So the servlets get a response that commits nothing until
 * its first byte: the status, the headers and the stream's closing are kept in order, and sent when the first byte is
 * written or the servlet is done. If a read failed with a {@link StorageUnreachableException} before the first byte,
 * whatever the servlet then sent is dropped and the {@code 503} is sent; otherwise what the servlet did is sent as it
 * did it, byte for byte. A read that fails after its first bytes is cut off, as before.
 * <p>
 * Everything passes through untouched unless the Store is a {@link HealdanStorage}, so a WAR still on CE's
 * {@code FSStore} is unchanged. The filter covers the routes that open a document's file and send it ({@code /download},
 * {@code /convertpdf} and shared links, {@code /download-ticket}), and not the others the plan leaves out: they don't
 * commit a {@code 200} before they read.
 */
@WebFilter(urlPatterns = { "/download", "/download/*", "/convertpdf/*", "/download-ticket" })
public class DownloadErrorFilter implements Filter {

	private static final Logger log = LoggerFactory.getLogger(DownloadErrorFilter.class);

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(response instanceof HttpServletResponse http) || !active()) {
			chain.doFilter(request, response);
			return;
		}

		HoldingResponse held = new HoldingResponse(http);
		ReadOutages.arm();
		try {
			chain.doFilter(request, held);
		} catch (IOException | ServletException | RuntimeException e) {
			StorageUnreachableException outage = outage(ReadOutages.disarm(), e);
			if (outage == null || held.started())
				throw e;
			unreachable(http, held, outage);
			return;
		}
		StorageUnreachableException outage = ReadOutages.disarm();
		if (outage != null && !held.started())
			unreachable(http, held, outage);
		else
			held.release();
	}

	/** @return whether the Store is Healdan's; a seam for tests */
	protected boolean active() {
		return Store.get() instanceof HealdanStorage;
	}

	/** @return the outage the read noted, or one the servlet let through */
	private static StorageUnreachableException outage(StorageUnreachableException noted, Throwable thrown) {
		if (noted != null)
			return noted;
		// Bounded, in case of a loop of causes
		Throwable t = thrown;
		for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause())
			if (t instanceof StorageUnreachableException outage)
				return outage;
		return null;
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
		String text = escape(message == null ? StorageUnreachableException.message(null) : message);
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
	 * A response that sends nothing to the real one until its first byte: what the servlet does before then is kept in
	 * order, and sent by {@link #release()} or dropped by {@link #drop()}.
	 */
	private static final class HoldingResponse extends HttpServletResponseWrapper {

		private final HttpServletResponse real;

		private final List<Step> held = new ArrayList<>();

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

		boolean started() {
			return started;
		}

		/** Sends what is held, in order, as the servlet did it */
		void release() throws IOException {
			List<Step> steps = new ArrayList<>(held);
			held.clear();
			started = true;
			for (Step step : steps)
				try {
					step.run();
				} catch (IllegalStateException e) {
					// As the servlet would have been told, had the response been committed by then
					log.debug("Replaying a download's response: {}", e.getMessage());
				}
		}

		/** Forgets what is held */
		void drop() {
			held.clear();
		}

		private void hold(Step step) throws IOException {
			if (started)
				step.run();
			else
				held.add(step);
		}

		private void holdQuietly(Runnable step) {
			if (started)
				step.run();
			else
				held.add(step::run);
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
			holdQuietly(() -> real.setHeader(name, value));
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
			holdQuietly(() -> real.setContentType(type));
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
			if (stream == null)
				stream = new HoldingStream();
			return stream;
		}

		@Override
		public PrintWriter getWriter() throws IOException {
			// A page, not a download: nothing is held from here on
			release();
			return real.getWriter();
		}

		/** A stream that opens the real one at its first byte, and keeps a close before it */
		private final class HoldingStream extends ServletOutputStream {

			private ServletOutputStream out() throws IOException {
				return real.getOutputStream();
			}

			private void start() throws IOException {
				if (!started)
					release();
			}

			@Override
			public void write(int b) throws IOException {
				start();
				out().write(b);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				if (len == 0)
					return;
				start();
				out().write(b, off, len);
			}

			@Override
			public void flush() throws IOException {
				hold(() -> out().flush());
			}

			@Override
			public void close() throws IOException {
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
