// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.logicaldoc.core.security.Permission;
import com.logicaldoc.healdan.admin.access.AccessLevel;
import com.logicaldoc.healdan.admin.access.AccessRow;
import com.logicaldoc.healdan.admin.access.FolderAccess;

/**
 * {@code openapi-admin.yaml}'s {@code FolderAccess}: who can use a workspace's folder, with the levels and the
 * permissions as the contract names them, in kebab case ({@code read-only}) and in lower case ({@code readingreq}).
 *
 * @param folderId the folder
 * @param ownPermissions true when it has permissions of its own
 * @param followsFolderId when it follows, the folder whose permissions it takes; null with its own
 * @param rows what it grants, groups first, then people, each by name
 * @param canApplyToSubfolders whether Apply to Subfolders is offered
 */
public record AccessView(long folderId, boolean ownPermissions, Long followsFolderId, List<Row> rows,
		boolean canApplyToSubfolders) {

	/**
	 * {@code openapi-admin.yaml}'s {@code AccessRow}.
	 *
	 * @param groupId the group, or the person's own group
	 * @param name the group's name, or the person's
	 * @param person true for a person's own group
	 * @param userId the person; null for a group
	 * @param level the permissions as a level, such as {@code read-only}
	 * @param permissions what it grants, in {@link Permission}'s order
	 * @param owner true for the matter's owner's own group
	 */
	public record Row(long groupId, String name, boolean person, Long userId, String level, List<String> permissions,
			boolean owner) {

		/**
		 * @param row a row
		 * @return it, as the contract gives it
		 */
		public static Row of(AccessRow row) {
			return new Row(row.groupId(), row.name(), row.person(), row.userId(), AccessView.level(row.level()),
					AccessView.permissions(row.permissions()), row.owner());
		}
	}

	/**
	 * @param access who can use a folder
	 * @return it, as the contract gives it
	 */
	public static AccessView of(FolderAccess access) {
		return new AccessView(access.folderId(), access.ownPermissions(), access.followsFolderId(),
				access.rows().stream().map(Row::of).toList(), access.canApplyToSubfolders());
	}

	/**
	 * @return the level as the contract names it, such as {@code read-only} or {@code can-see}
	 */
	public static String level(AccessLevel level) {
		return level.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	/**
	 * @return the permissions in {@link Permission}'s order, as the contract names them
	 */
	public static List<String> permissions(Set<Permission> granted) {
		return List.of(Permission.values()).stream().filter(granted::contains).map(AccessView::name).toList();
	}

	/**
	 * @param name a permission's name, as the contract gives it
	 * @return the permission, null for a name the contract doesn't list, such as {@code READ}
	 */
	public static Permission permission(String name) {
		for (Permission flag : Permission.values())
			if (name(flag).equals(name))
				return flag;
		return null;
	}

	private static String name(Permission flag) {
		return flag.name().toLowerCase(Locale.ROOT);
	}
}
