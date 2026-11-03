// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One value of a property picked from a list (spec section 6.7).
 *
 * @param value the value
 * @param label what to show
 * @param docClass for a Subclass, the Class it belongs to; otherwise null
 */
public record OptionView(String value, String label, @JsonProperty("class") String docClass) {
}
