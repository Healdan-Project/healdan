package com.logicaldoc.healdan.api.model;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code document} part of {@code POST /v1/documents} (spec section 6.5).
 *
 * @param folderId the folder to file the document into
 * @param name the name to file it under; the file part's name when blank
 * @param properties property values, by name, in the form
 *        {@code PATCH .../properties} takes; a property left out, sent as
 *        {@code null} or sent as it would inherit, inherits
 * @param checkOut true to check the document out to the caller once it's
 *        filed
 */
public record NewDocument(Long folderId, String name, Map<String, JsonNode> properties, Boolean checkOut) {
}
