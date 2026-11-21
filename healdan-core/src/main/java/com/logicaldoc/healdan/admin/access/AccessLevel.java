// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.access;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.logicaldoc.core.security.Permission;

/**
 * A row's permissions as a level (Admin app spec section 8.4; owner: as in iManage), {@code openapi-admin.yaml}'s
 * {@code AccessLevel}. The match is exact: a row with one flag more or fewer than a level's is {@link #CUSTOM}, which
 * Show Every Permission edits flag by flag. The Workspace Editor offers Read Only, Read/Write and Full Access, and
 * shows {@link #CAN_SEE}, a Store's one level, as Custom on a folder.
 */
public enum AccessLevel {

	/** Read, Preview, Download, Print and Email */
	READ_ONLY(EnumSet.of(Permission.READ, Permission.PREVIEW, Permission.DOWNLOAD, Permission.PRINT,
			Permission.EMAIL)),

	/** Read Only's, plus Write, Add, Rename, Move and Delete */
	READ_WRITE(EnumSet.of(Permission.READ, Permission.PREVIEW, Permission.DOWNLOAD, Permission.PRINT,
			Permission.EMAIL, Permission.WRITE, Permission.ADD, Permission.RENAME, Permission.MOVE, Permission.DELETE)),

	/** All 25, which is what a matter's owner gets */
	FULL_ACCESS(EnumSet.allOf(Permission.class)),

	/** Read alone: who can see a Store */
	CAN_SEE(EnumSet.of(Permission.READ)),

	/** Any other set of permissions, none included */
	CUSTOM(null);

	private final Set<Permission> permissions;

	AccessLevel(Set<Permission> permissions) {
		this.permissions = permissions != null ? Collections.unmodifiableSet(permissions) : null;
	}

	/**
	 * @param granted what a row grants
	 * @return the level whose permissions are exactly those, {@link #CUSTOM} if none's are
	 */
	public static AccessLevel of(Set<Permission> granted) {
		Objects.requireNonNull(granted, "granted");
		for (AccessLevel level : values())
			if (level.permissions != null && level.permissions.equals(granted))
				return level;
		return CUSTOM;
	}

	/**
	 * @return the level's permissions, which can't be changed
	 * @throws UnsupportedOperationException for {@link #CUSTOM}, which is any set but the others'
	 */
	public Set<Permission> permissions() {
		if (permissions == null)
			throw new UnsupportedOperationException("Custom is any set of permissions but the levels'");
		return permissions;
	}
}
