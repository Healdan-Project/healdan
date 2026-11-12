// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The counts asked for with {@code facets=}, each a list of options, most
 * documents first. A facet that wasn't asked for is left out.
 *
 * @param type the file kinds
 * @param author the Authors
 * @param docClass the classes
 * @param client the client numbers
 * @param matter the matters, by client and matter number, such as
 *        {@code 998.1}
 * @param partial true when the counts were taken over the first results
 *        only, as a search's past its counting window are; left out when
 *        they cover every result
 */
public record FacetsView(@JsonInclude(JsonInclude.Include.NON_NULL) List<FacetCount> type,
		@JsonInclude(JsonInclude.Include.NON_NULL) List<AuthorFacetCount> author,
		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("class") List<FacetCount> docClass,
		@JsonInclude(JsonInclude.Include.NON_NULL) List<FacetCount> client,
		@JsonInclude(JsonInclude.Include.NON_NULL) List<FacetCount> matter,
		@JsonInclude(JsonInclude.Include.NON_NULL) Boolean partial) {
}
