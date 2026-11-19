// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.junit.Test;

/**
 * What makes two errors one fault (Admin app spec section 9.4): the exception's
 * class, where it was logged, the message without its numbers and quoted
 * names, and the logger when it isn't named for where it was logged; and the
 * documents a message names.
 */
public class ErrorFingerprintTest {

	private static final String LOCATION = "com.logicaldoc.healdan.preview.PreviewWorker:120";

	@Test
	public void testNumbersAndQuotedNamesDontCount() {
		assertEquals(of("Document 10412 can't be read from 'Smoke Cloud': 3 tries"),
				of("Document 77 can't be read from 'Other Store': 12 tries"));
		assertEquals(of("Can't open \"a.pdf\" for user 'bob'"), of("Can't open \"Brief 2.docx\" for user 'ann'"));
		// A name with an apostrophe of its own is still one quoted name
		assertEquals(of("The folder 'John's papers' is missing"), of("The folder 'Wills' is missing"));
		// An apostrophe inside a word quotes nothing, so the words after it still count
		assertNotEquals(of("Can't read the index, it's locked"), of("Can't read the index, it's full"));
		assertNotEquals(of("The store can't be reached"), of("The index can't be reached"));
	}

	@Test
	public void testDifferentExceptionClassesGiveTwo() {
		assertNotEquals(error(IllegalStateException.class.getName(), LOCATION, "It failed"),
				error(java.io.IOException.class.getName(), LOCATION, "It failed"));
		assertNotEquals(error(null, LOCATION, "It failed"),
				error(IllegalStateException.class.getName(), LOCATION, "It failed"));
	}

	@Test
	public void testWhereItWasLoggedCounts() {
		assertNotEquals(error(null, LOCATION, "It failed"), error(null, LOCATION.replace("120", "121"), "It failed"));
		// Without its source, the logger stands for it
		assertEquals(ErrorFingerprint.of(new CapturedError(Instant.now(), "ERROR", "a.B", null, null, "It failed",
				null)), ErrorFingerprint.of(new CapturedError(Instant.EPOCH, "FATAL", "a.B", null, null, "It failed",
						null)));
		assertNotEquals(ErrorFingerprint.of(new CapturedError(Instant.now(), "ERROR", "a.B", null, null, "It failed",
				null)), ErrorFingerprint.of(new CapturedError(Instant.now(), "ERROR", "a.C", null, null, "It failed",
						null)));
	}

	/**
	 * A base class that logs through each subclass's logger, as CE's Task.run does for each task, gives a fault per
	 * logger; a logger named for the class it logs from, or for one that class is nested in, keeps the recipe as it
	 * was, so the faults recorded so far stay one each
	 */
	@Test
	public void testTheLoggerCountsWhenItIsntNamedForWhereItLogged() throws Exception {
		String base = "com.logicaldoc.core.task.Task:238";
		String exception = IllegalStateException.class.getName();
		String indexer = ErrorFingerprint.of(new CapturedError(Instant.now(), "ERROR",
				"com.logicaldoc.core.searchengine.indexer.IndexerTask", base, exception, "Connection refused", null));
		String tags = ErrorFingerprint.of(new CapturedError(Instant.now(), "ERROR",
				"com.logicaldoc.core.document.TagsProcessor", base, exception, "Connection refused", null));
		assertNotEquals(indexer, tags);
		assertEquals(indexer, ErrorFingerprint.of(new CapturedError(Instant.EPOCH, "ERROR",
				"com.logicaldoc.core.searchengine.indexer.IndexerTask", base, exception, "Connection refused", null)));
		assertEquals(sha256(exception + "\n" + base + "\nConnection refused\ncom.logicaldoc.core.document.TagsProcessor"),
				tags);

		// Named for its own class, or the class it is nested in: the recipe as it was
		String own = "com.logicaldoc.healdan.preview.PreviewWorker";
		assertEquals(sha256("\n" + LOCATION + "\nIt failed"), of("It failed"));
		assertEquals(sha256(exception + "\n" + own + "$Round:51\nIt failed"), ErrorFingerprint.of(new CapturedError(
				Instant.now(), "ERROR", own, own + "$Round:51", exception, "It failed", null)));
		// A logger whose name only starts the same is another
		assertNotEquals(sha256("\n" + own + "Test:9\nIt failed"), ErrorFingerprint.of(
				new CapturedError(Instant.now(), "ERROR", own, own + "Test:9", null, "It failed", null)));
		// Without a location the logger stands for it, as before
		assertEquals(sha256("\na.B\nIt failed"),
				ErrorFingerprint.of(new CapturedError(Instant.now(), "ERROR", "a.B", null, null, "It failed", null)));
		// The root logger, whose name is empty, adds nothing
		assertEquals(sha256("\n" + LOCATION + "\nIt failed"), ErrorFingerprint
				.of(new CapturedError(Instant.now(), "ERROR", "", LOCATION, null, "It failed", null)));
	}

	private static String sha256(String text) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
	}

	@Test
	public void testIsSha256InHex() {
		String fingerprint = of("It failed");
		assertTrue(fingerprint, fingerprint.matches("[0-9a-f]{64}"));
	}

	@Test
	public void testTheExceptionComesFromTheStackWhenNotGiven() {
		CapturedError error = new CapturedError(Instant.now(), "ERROR", "a.B", "It failed",
				"java.lang.IllegalStateException: boom\n\tat a.B.c(B.java:1)");
		assertEquals(IllegalStateException.class.getName(), error.exception());
		assertNull(error.location());
		assertEquals("java.io.IOException", new CapturedError(Instant.now(), "ERROR", "a.B", "It failed",
				"java.io.IOException\n\tat a.B.c(B.java:1)").exception());
		assertNull(new CapturedError(Instant.now(), "ERROR", "a.B", "It failed", null).exception());
	}

	@Test
	public void testDocuments() {
		assertEquals(List.of(10412L), ErrorFingerprint.documents("The preview of doc 10412 version 3 failed"));
		assertEquals(List.of(10412L), ErrorFingerprint.documents("Document 10412 version 3.1 can't be read"));
		assertEquals(List.of(5L, 7L, 9L, 11L),
				ErrorFingerprint.documents("docId=5, docId=5, document id 7, doc_id: 9 and DOCUMENT 11"));
		// A doc # isn't a document's id, but the id beside it is
		assertEquals(List.of(10412L), ErrorFingerprint.documents("Can't take back document #998 (id 10412)"));
		assertEquals(List.of(), ErrorFingerprint.documents("The save of document #998 failed"));
		assertEquals(List.of(), ErrorFingerprint.documents("Found undeletable documents in tree 5"));
		assertEquals(List.of(), ErrorFingerprint.documents("The docket 12 and the doc12 are no documents"));
		assertEquals(List.of(), ErrorFingerprint.documents("document 99999999999999999999 is too long"));
		assertEquals(List.of(), ErrorFingerprint.documents(null));
	}

	private static String of(String message) {
		return error(null, LOCATION, message);
	}

	private static String error(String exception, String location, String message) {
		return ErrorFingerprint
				.of(new CapturedError(Instant.now(), "ERROR", "com.logicaldoc.healdan.preview.PreviewWorker",
						location, exception, message, null));
	}
}
