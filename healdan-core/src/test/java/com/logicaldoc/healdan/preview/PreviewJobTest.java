package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.OptionalInt;

import org.junit.Test;

public class PreviewJobTest {

	private static PreviewJob failedWith(String error) {
		return new PreviewJob(1, "1.0", 1, error);
	}

	@Test
	public void testTimedOutAfterReadsTheMessage() {
		assertEquals(OptionalInt.of(120), LibreOfficeRunner.timedOutAfter(LibreOfficeRunner.timedOut(120, "a.docx")));
		assertEquals(OptionalInt.empty(), LibreOfficeRunner.timedOutAfter("LibreOffice exited 1 without a PDF"));
		assertEquals(OptionalInt.empty(), LibreOfficeRunner.timedOutAfter(null));
	}

	@Test
	public void testTimesOutAtTheSameOrAShorterTimeout() {
		PreviewJob job = failedWith(LibreOfficeRunner.timedOut(120, "a.docx"));
		assertTrue(job.timesOutAt(120));
		assertTrue(job.timesOutAt(60));
		// A raised timeout gives it a chance
		assertFalse(job.timesOutAt(121));
	}

	@Test
	public void testOtherFailuresNeverTimeOut() {
		assertFalse(failedWith("LibreOffice exited 1 without a PDF for a.docx").timesOutAt(1));
		assertFalse(failedWith(null).timesOutAt(1));
		// Not tried yet
		assertFalse(new PreviewJob(1, "1.0", 0, LibreOfficeRunner.timedOut(120, "a.docx")).timesOutAt(1));
	}

	@Test
	public void testATimeoutWithoutANumberCounts() {
		assertTrue(failedWith("timed out").timesOutAt(10_000));
	}
}
