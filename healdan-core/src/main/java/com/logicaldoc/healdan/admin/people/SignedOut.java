// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

/**
 * What Sign Out Everywhere closed.
 *
 * @param sessions the open sessions it closed on this server: the web page's, the Classic Interface's and the
 *        devices'
 * @param devices the device tokens it revoked
 */
public record SignedOut(int sessions, int devices) {
}
