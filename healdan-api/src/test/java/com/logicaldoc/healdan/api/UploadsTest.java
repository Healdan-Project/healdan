// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.cxf.attachment.AttachmentDataSource;
import org.apache.cxf.attachment.AttachmentDeserializer;
import org.apache.cxf.attachment.AttachmentUtil;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.jaxrs.interceptor.AttachmentInputInterceptor;
import org.apache.cxf.message.ExchangeImpl;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.logicaldoc.healdan.support.LogCapture;

import jakarta.activation.DataHandler;

/**
 * {@link Uploads#release}, on a request's first part as CXF reads it for the
 * API's server: over {@link ApiServer#MEMORY_THRESHOLD}, in a temporary file.
 * The routes' own tests send real requests.
 */
public class UploadsTest {

	private static final String BOUNDARY = "healdan-boundary";

	private static final int SIZE = ApiServer.MEMORY_THRESHOLD + 1;

	/** Where CXF keeps the parts it reads */
	@Rule
	public TemporaryFolder cxf = new TemporaryFolder();

	@Test
	public void testReleaseLetsGoOfAnUnreadPart() throws Exception {
		DataHandler part = firstPart(request());
		assertEquals(1, cxf.getRoot().list().length);

		try (LogCapture log = LogCapture.of(Uploads.class)) {
			Uploads.release(part);
			assertEquals(List.of(), log.lines());
		}
		assertEquals(0, cxf.getRoot().list().length);
	}

	@Test
	public void testReleaseLeavesAPartReadAndClosedAlone() throws Exception {
		DataHandler part = firstPart(request());
		try (InputStream in = part.getInputStream()) {
			assertEquals(SIZE, in.readAllBytes().length);
		}
		assertEquals(0, cxf.getRoot().list().length);

		// Closing it again would read CXF's copy, deleted by then
		try (LogCapture log = LogCapture.of(Uploads.class)) {
			Uploads.release(part);
			assertEquals(List.of(), log.lines());
		}
	}

	@Test
	public void testReleaseLeavesAPartClosedUnderneathAlone() throws Exception {
		Message request = request();
		DataHandler part = firstPart(request);
		// The request's content is the stream CXF reads the first part from, under the one the part hands out
		request.getContent(InputStream.class).close();
		assertEquals(0, cxf.getRoot().list().length);

		try (LogCapture log = LogCapture.of(Uploads.class)) {
			Uploads.release(part);
			assertEquals(List.of(), log.lines());
		}
	}

	@Test
	public void testReleaseLogsAPartThatCantBeLetGo() {
		ByteArrayInputStream failing = new ByteArrayInputStream(new byte[0]) {
			@Override
			public void close() throws IOException {
				throw new IOException("The disk is gone");
			}
		};
		DataHandler part = new DataHandler(new AttachmentDataSource("application/octet-stream", failing));

		try (LogCapture log = LogCapture.of(Uploads.class)) {
			Uploads.release(part);
			assertEquals(List.of("WARN Can't let go of an upload part"), log.lines());
		}
	}

	/** A request with one part, a file of {@link #SIZE} bytes, read as the API's server has CXF read it */
	private Message request() throws IOException {
		byte[] file = new byte[SIZE];
		Arrays.fill(file, (byte) 'x');
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
				+ "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		body.write(file);
		body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));

		Message request = new MessageImpl();
		request.setExchange(new ExchangeImpl());
		request.put(Message.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY);
		request.put(AttachmentDeserializer.ATTACHMENT_DIRECTORY, cxf.getRoot().getPath());
		request.put(AttachmentDeserializer.ATTACHMENT_MEMORY_THRESHOLD, String.valueOf(ApiServer.MEMORY_THRESHOLD));
		request.setContent(InputStream.class, new ByteArrayInputStream(body.toByteArray()));
		new AttachmentInputInterceptor().handleMessage(request);
		return request;
	}

	/**
	 * @return the request's first part, as CXF's {@code MessageContextImpl}
	 *         makes it, once CXF has read the request's other parts (here
	 *         none), which copies the first to its temporary file
	 */
	private static DataHandler firstPart(Message request) throws IOException {
		Map<String, List<String>> headers = CastUtils
				.cast((Map<?, ?>) request.get(AttachmentDeserializer.ATTACHMENT_PART_HEADERS));
		DataHandler first = AttachmentUtil.createAttachment(request.getContent(InputStream.class), headers, request)
				.getDataHandler();
		assertEquals(0, request.getAttachments().size());
		return first;
	}
}
