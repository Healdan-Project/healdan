// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * A client in Recent Clients
 *
 * @param number the client's number
 * @param name the client's name
 */
public record ClientEntryView(long number, String name) {
}
