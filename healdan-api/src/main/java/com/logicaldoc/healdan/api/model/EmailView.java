// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.Date;

/**
 * An email's From, To and Sent, as its document's properties hold them and a
 * list shows them (list columns spec section 5.2).
 *
 * @param from the From property, or null
 * @param to the To property, or null
 * @param sent the Sent property, or null
 */
public record EmailView(String from, String to, Date sent) {
}
