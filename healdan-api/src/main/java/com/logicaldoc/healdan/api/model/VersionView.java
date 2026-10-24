package com.logicaldoc.healdan.api.model;

import java.util.Date;

/**
 * One entry of a document's timeline (spec section 6.4).
 *
 * @param version the version, such as {@code 2.1}
 * @param fileVersion the version the file content was saved as
 * @param kind {@code version} for a whole number, {@code snapshot} otherwise
 * @param date when the version was saved
 * @param by who saved it, or null
 * @param comment the comment saved with it, or null
 * @param event what happened, as CE keys it, such as {@code event.checkedin}
 */
public record VersionView(String version, String fileVersion, String kind, Date date, UserRef by, String comment,
		String event) {
}
