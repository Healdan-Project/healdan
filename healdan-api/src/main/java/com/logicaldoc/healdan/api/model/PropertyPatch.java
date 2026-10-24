package com.logicaldoc.healdan.api.model;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The body of {@code PATCH /v1/documents/{docNumber}/properties} (spec section
 * 6.7).
 *
 * @param values the new value of each property to change, by name; JSON
 *        {@code null} clears one, so that it inherits again
 */
public record PropertyPatch(Map<String, JsonNode> values) {
}
