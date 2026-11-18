// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * One client or matter to filter by, and how many documents it would leave.
 *
 * @param value the number, which the filter's parameter takes, such as
 *        {@code 998.1}
 * @param displayName the client or matter as people read it; the number alone
 *        when no matter of it is one whose own folder the caller can read, or
 *        when no client or matter has the number any more
 * @param count how many documents it would leave
 */
public record NamedFacetCount(String value, String displayName, long count) {
}
