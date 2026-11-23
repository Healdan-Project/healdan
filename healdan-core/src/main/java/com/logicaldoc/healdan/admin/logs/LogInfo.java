// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import java.time.Instant;

/**
 * One of the server's logs, for the Server Log's menu (Admin app spec section 8.8).
 *
 * @param name its appender's name, by which the routes take it
 * @param label its name as the menu shows it
 * @param size the size of its current file and the files it rolled over to, together, in bytes
 * @param modified when its current file last changed; null when there's none
 */
public record LogInfo(String name, String label, long size, Instant modified) {
}
