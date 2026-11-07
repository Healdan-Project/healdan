// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.Date;

import com.logicaldoc.core.PersistentObject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

/**
 * One showing or entering of a backend's recovery key (question 8). Never deleted: it is the record of who saw a key.
 */
@Entity
@Table(name = "hd_storage_event")
public class StorageEvent extends PersistentObject {

	private static final long serialVersionUID = 1L;

	/** The recovery key was shown to an administrator */
	public static final String KEY_SHOWN = "key-shown";

	/** An administrator entered a recovery key */
	public static final String KEY_ENTERED = "key-entered";

	@Column(name = "hd_backendid", nullable = false)
	private long backendId;

	/** {@code key-shown} or {@code key-entered} */
	@Column(name = "hd_event", length = 20, nullable = false)
	private String event;

	@Column(name = "hd_date", nullable = false)
	@Temporal(TemporalType.TIMESTAMP)
	private Date date;

	@Column(name = "hd_userid", nullable = false)
	private long userId;

	@Column(name = "hd_username", length = 255, nullable = false)
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
