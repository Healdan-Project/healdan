// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.time.Instant;

/**
 * A device a person is signed in on, through a device token, as their page lists it. Never the token, nor its hash.
 *
 * @param id the token's id, which Revoke names
 * @param name the name the device gave itself; null for none
 * @param device the device's own id, which the Healdan Helper sends; null for none
 * @param provider the sign-in provider the token came from; null for one made with a password
 * @param created when the device signed in
 * @param lastUsed when it last used the token
 * @param expires when the token expires, unless it goes unused longer than the idle limit first
 */
public record DeviceRow(long id, String name, String device, SignsInWith.Provider provider, Instant created,
		Instant lastUsed, Instant expires) {
}
