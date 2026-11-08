// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;
import java.util.Date;

/**
 * One showing or entering of a storage backend's recovery key, as the Storage
 * tab's Key History lists it (question 8)
 */
public class GUIStorageEvent implements Serializable {

	private static final long serialVersionUID = 1L;

	/** {@code key-shown} */
	public static final String KEY_SHOWN = "key-shown";

	/** {@code key-entered} */
	public static final String KEY_ENTERED = "key-entered";

	private long backendId;

	/** {@link #KEY_SHOWN} or {@link #KEY_ENTERED} */
	private String event;

	private Date date;

	private long userId;

	/** The administrator's username, as it was then */
	private String userName;

	public long getBackendId() {
		return backendId;
	}

	public void setBackendId(long backendId) {
		this.backendId = backendId;
	}

	public String getEvent() {
		return event;
	}

	public void setEvent(String event) {
		this.event = event;
	}

	public Date getDate() {
		return date;
	}

	public void setDate(Date date) {
		this.date = date;
	}

	public long getUserId() {
		return userId;
	}

	public void setUserId(long userId) {
		this.userId = userId;
	}

	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}
}
