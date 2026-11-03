// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * A client named in another object
 *
 * @param id the client's id
 * @param number the client number
 * @param name the client's name
 */
public record ClientRef(long id, long number, String name) {
}
