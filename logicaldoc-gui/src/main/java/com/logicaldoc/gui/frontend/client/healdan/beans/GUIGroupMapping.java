// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

/**
 * One row of a sign-in provider's group map, as the Sign-In tab shows it (M4
 * spec section 8): a person whose groups claim holds the value is put in the
 * Healdan group.
 */
public class GUIGroupMapping implements Serializable {

	private static final long serialVersionUID = 1L;

	/** A value of the provider's groups claim */
	private String value;

	private long groupId;

	/** The Healdan group's name, for showing; its name before it was deleted, for a deleted one */
	private String groupName;

	/** True if the Healdan group was deleted: the row is ignored, and a save drops it */
	private boolean deleted;

	public GUIGroupMapping() {
	}

	public GUIGroupMapping(String value, long groupId) {
		this.value = value;
		this.groupId = groupId;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public long getGroupId() {
		return groupId;
	}

	public void setGroupId(long groupId) {
		this.groupId = groupId;
	}

	public String getGroupName() {
		return groupName;
	}

	public void setGroupName(String groupName) {
		this.groupName = groupName;
	}

	public boolean isDeleted() {
		return deleted;
	}

	public void setDeleted(boolean deleted) {
		this.deleted = deleted;
	}
}
