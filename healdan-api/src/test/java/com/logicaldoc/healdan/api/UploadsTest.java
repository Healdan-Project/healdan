// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import org.apache.cxf.attachment.AttachmentDataSource;
import org.junit.Test;

import com.logicaldoc.healdan.support.LogCapture;

import jakarta.activation.DataHandler;

/**
 * {@link Uploads#release}, on parts made as CXF makes them. The routes' own
 * tests send real requests.
 */
public class UploadsTest {

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
}
