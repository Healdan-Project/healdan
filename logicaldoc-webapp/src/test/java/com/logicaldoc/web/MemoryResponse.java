package com.logicaldoc.web;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import com.logicaldoc.util.servlet.MockServletResponse;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

/**
 * A servlet response kept in memory, with the error it was sent if any. The
 * upstream data servlet tests' {@code MockServletResponse} writes to
 * {@code target/response.xml} instead, which fails where that file can't be
 * written.
 */
public class MemoryResponse extends MockServletResponse {

	private final ByteArrayOutputStream body = new ByteArrayOutputStream();

	private PrintWriter writer;

	private Integer status;

	private String error;

	public MemoryResponse() {
		// Never written: both ways to write are overridden
		super(new File("target/unused-response"));
	}

	@Override
	public ServletOutputStream getOutputStream() {
		return new ServletOutputStream() {

			@Override
			public void write(int b) {
				body.write(b);
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setWriteListener(WriteListener listener) {
				// Nothing to do
			}
		};
	}

	@Override
	public PrintWriter getWriter() {
		if (writer == null)
			writer = new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8), true);
		return writer;
	}

	@Override
	public void sendError(int sc, String message) {
		status = sc;
		error = message;
	}

	@Override
	public void sendError(int sc) {
		status = sc;
	}

	@Override
	public void setStatus(int sc) {
		status = sc;
	}

	@Override
	public int getStatus() {
		return status != null ? status : 200;
	}

	/**
	 * @return the message of the error sent, null if none was
	 */
	public String error() {
		return error;
	}

	/**
	 * @return what was written, as bytes
	 */
	public byte[] bytes() {
		flushWriter();
		return body.toByteArray();
	}

	/**
	 * @return what was written, as UTF-8 text
	 */
	public String text() {
		flushWriter();
		return body.toString(StandardCharsets.UTF_8);
	}

	private void flushWriter() {
		if (writer != null)
			writer.flush();
	}
}
