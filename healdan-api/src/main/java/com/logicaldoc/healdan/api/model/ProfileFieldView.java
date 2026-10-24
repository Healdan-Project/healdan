package com.logicaldoc.healdan.api.model;

import java.util.List;

/**
 * One property of a document, or of the form for filing a new one (spec
 * section 6.7).
 *
 * @param name the attribute's name, as {@code PATCH} names it
 * @param label what to show
 * @param type {@code string}, {@code integer}, {@code decimal}, {@code date},
 *        {@code boolean}, {@code user}, {@code folder} or {@code document}
 * @param required whether it must have a value
 * @param editable false for client, matter, the email fields and read-only
 *        properties; for a document's, false for every property when the
 *        caller may not change them
 * @param value a string, number or boolean; a {@link UserRef} for a user; an
 *        ISO 8601 timestamp for a date; a folder's id; a document's doc #;
 *        null when there is none
 * @param source {@code inherited}, {@code local} or {@code default}; null
 *        when there is no value
 * @param inheritedFrom the folder an inherited value comes from, or null
 * @param options the values to pick from, or null when the property isn't
 *        picked from a list
 */
public record ProfileFieldView(String name, String label, String type, boolean required, boolean editable,
		Object value, String source, FolderRef inheritedFrom, List<OptionView> options) {
}
