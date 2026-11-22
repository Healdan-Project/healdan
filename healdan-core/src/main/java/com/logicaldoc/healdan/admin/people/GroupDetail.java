// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

/**
 * A group's page (Admin app spec section 8.7): its row, and how many folders it's used on.
 *
 * @param row what the Groups list shows of it
 * @param folders how many folders and search folders not in the Trash have rows of their own that give it at least
 *        one permission, Stores and template folders among them
 */
public record GroupDetail(GroupRow row, long folders) {
}
