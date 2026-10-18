package com.logicaldoc.healdan.docnumber;

/**
 * What a doc # lookup found.
 *
 * @param docId the document, null if nothing was found
 * @param folderId the document's folder
 * @param versionId the version row asked for, null for the current version
 * @param version the version found, such as {@code 2.10}
 * @param fileVersion the file version of that version
 * @param message why nothing was found, for the user; null when found
 */
public record LookupResult(Long docId, Long folderId, Long versionId, String version, String fileVersion,
		String message) {

	/**
	 * @param message why nothing was found
	 * @return a result that found nothing
	 */
	static LookupResult notFound(String message) {
		return new LookupResult(null, null, null, null, null, message);
	}

	/**
	 * @return true if a document was found
	 */
	public boolean found() {
		return docId != null;
	}
}
