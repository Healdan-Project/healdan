package com.logicaldoc.healdan.api.model;

/**
 * What {@code GET /v1/lookup} found for a typed doc #.
 *
 * @param docNumber the document's doc #
 * @param version the version the input names, or the document's current
 *        version when it names none
 */
public record LookupView(long docNumber, String version) {
}
