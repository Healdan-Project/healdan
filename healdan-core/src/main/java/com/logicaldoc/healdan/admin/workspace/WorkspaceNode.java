// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.workspace;

import java.util.List;

/**
 * A folder or search folder of a workspace, with what's in it, as the Workspace Editor's tree shows it
 * ({@code openapi-admin.yaml}'s {@code WorkspaceNode}).
 *
 * @param id the folder's id
 * @param name its name
 * @param kind {@link #FOLDER} or {@link #SEARCH_FOLDER}
 * @param ownPermissions true when it has permissions of its own, false when it follows another folder's
 * @param children its folders, then its search folders, each by name, ignoring case; none for a search folder
 */
public record WorkspaceNode(long id, String name, String kind, boolean ownPermissions,
		List<WorkspaceNode> children) {

	/** A plain folder */
	public static final String FOLDER = "folder";

	/** A search folder, which lists documents by its rule and holds no folders */
	public static final String SEARCH_FOLDER = "search-folder";
}
