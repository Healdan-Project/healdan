package com.logicaldoc.healdan.preview;

import java.util.OptionalInt;

/**
 * A file version waiting to be converted to PDF ahead of time (spec section 7.1).
 *
 * @param docId the document
 * @param fileVersion the file version, as in {@code ld_version.ld_fileversion}
 * @param attempts how many tries have failed so far
 * @param lastError the message of the last failure, null if none
 */
public record PreviewJob(long docId, String fileVersion, int attempts, String lastError) {

	/**
	 * A try that timed out would most likely time out again, unless the
	 * timeout has been raised since.
	 *
	 * @param timeout the timeout now in force, in seconds
	 * @return true if the last try timed out under a timeout at least as long;
	 *         also when its message says no timeout
	 */
	public boolean timesOutAt(int timeout) {
		if (attempts < 1 || lastError == null || !lastError.contains("timed out"))
			return false;
		OptionalInt after = LibreOfficeRunner.timedOutAfter(lastError);
		return after.isEmpty() || after.getAsInt() >= timeout;
	}
}
