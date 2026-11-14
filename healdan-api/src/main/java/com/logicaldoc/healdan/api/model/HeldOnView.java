// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * The computer and app a check-out is held on, for showing: no device id, so
 * a client can't take it for its own, as it does {@link DeviceView}. Given to
 * the user who holds the check-out and to administrators (changed after M6:
 * the owner's dogfooding punch list, 2026-11-14).
 *
 * @param name what the computer is called: the name of the token it last
 *        wrote through, or null if the server's data doesn't say
 * @param app the app the check-out named, such as {@code LibreOffice}, or
 *        null
 */
public record HeldOnView(String name, String app) {
}
