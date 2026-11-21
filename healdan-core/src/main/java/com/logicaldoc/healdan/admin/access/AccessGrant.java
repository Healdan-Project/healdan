// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.access;

import java.util.Set;

import com.logicaldoc.core.security.Permission;

/**
 * What a folder is to grant one group or person, {@code openapi-admin.yaml}'s {@code AccessGrant}: exact flags, no
 * level (Admin app spec section 8.4). It names a group by {@code groupId}, which may be a person's own group, or a
 * person by {@code userId}, whose own group gets it: one of the two.
 *
 * @param groupId the group, or a person's own group; null when {@code userId} names the person
 * @param userId the person; null when {@code groupId} names the group
 * @param permissions exactly what it grants
 */
public record AccessGrant(Long groupId, Long userId, Set<Permission> permissions) {
}
