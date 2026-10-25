package com.logicaldoc.healdan.api.model;

/**
 * The {@code version} part of {@code POST /v1/documents/{docNumber}/versions}
 * (spec section 6.6, M3 spec section 9.5).
 *
 * @param kind {@code snapshot} for an ordinary save (2.1 becomes 2.2), or
 *        {@code version} for "Save as new version" (2.2 becomes 3.0);
 *        required
 * @param name the name to save under, with the document's extension; the
 *        file part's name when blank. A browser names a file part it has no
 *        name for {@code blob}, so a browser-based client sends this.
 * @param comment the comment saved with the version, or null
 * @param keepCheckedOut true for a save while editing, which leaves the
 *        document checked out to the caller; false or null for the save on
 *        closing, which checks it in
 * @param ifVersion the document's {@code version} the save started from: a
 *        document at another is {@code 409 version-moved}; null for any
 * @param ifHeld true when the document must be checked out to the caller,
 *        else {@code 409 not-held} (a check-out held on another of the
 *        caller's devices is {@code 409 held-elsewhere} whatever this is);
 *        false or null to check out a free document for the save, as in M2
 * @param saveId a UUID the client makes for the save and sends again with
 *        it, so that a save repeating the last one stored for the document
 *        is answered as stored and stores nothing; null for none
 */
public record NewVersion(String kind, String name, String comment, Boolean keepCheckedOut, String ifVersion,
		Boolean ifHeld, String saveId) {
}
