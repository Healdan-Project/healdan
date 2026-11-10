// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * One option of a filter, and how many documents it would leave (M6 spec
 * section 9.2).
 *
 * @param value what the filter's parameter takes, such as {@code pdf}
 * @param count how many documents it would leave
 */
public record FacetCount(String value, long count) {
}
