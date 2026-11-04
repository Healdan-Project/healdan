// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;

/**
 * The answer of {@code GET /v1/emails} (M5 spec §6.7).
 *
 * @param items one entry for each Message-ID asked for, in the order asked
 */
public record EmailCopies(List<EmailCopy> items) {
}
