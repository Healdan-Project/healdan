// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.access;

import java.util.Set;

import com.logicaldoc.core.security.Permission;

/**
 * What a folder grants one group or person (Admin app spec section 8.4), {@code openapi-admin.yaml}'s
 * {@code AccessRow}.
 *
 * @param groupId the group, or the person's own group
 * @param name the group's name, or the person's first and last name (the username when both are blank)
 * @param person true for a person's own group
 * @param userId the person, for a person's own group; null for a group
 * @param level the permissions as a level, {@link AccessLevel#of}
 * @param permissions what it grants, at least one
 * @param owner true for the matter's owner's own group, on any folder of the matter
 */
public record AccessRow(long groupId, String name, boolean person, Long userId, AccessLevel level,
		Set<Permission> permissions, boolean owner) {
}
