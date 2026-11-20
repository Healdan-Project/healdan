// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.workspace;

/**
 * A workspace template, as the Workspace Templates list shows it ({@code openapi-admin.yaml}'s {@code TemplateInfo}).
 *
 * @param id its id, which is its folder's: New Matter's {@code templateId}
 * @param name its name
 * @param folders the live folders and search folders in it, at every level, its own and aliases left out
 * @param matters the live matters made from it, not deleted or in the Trash
 */
public record TemplateInfo(long id, String name, long folders, long matters) {
}
