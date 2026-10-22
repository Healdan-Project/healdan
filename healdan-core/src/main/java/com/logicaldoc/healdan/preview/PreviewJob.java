package com.logicaldoc.healdan.preview;

/**
 * A file version waiting to be converted to PDF ahead of time (spec section 7.1).
 *
 * @param docId the document
 * @param fileVersion the file version, as in {@code ld_version.ld_fileversion}
 * @param attempts how many tries have failed so far
 * @param lastError the message of the last failure, null if none
 */
public record PreviewJob(long docId, String fileVersion, int attempts, String lastError) {
}
