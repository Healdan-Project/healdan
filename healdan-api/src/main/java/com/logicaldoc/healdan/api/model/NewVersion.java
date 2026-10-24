package com.logicaldoc.healdan.api.model;

/**
 * The {@code version} part of {@code POST /v1/documents/{docNumber}/versions}
 * (spec section 6.6).
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
 */
public record NewVersion(String kind, String name, String comment, Boolean keepCheckedOut) {
}
