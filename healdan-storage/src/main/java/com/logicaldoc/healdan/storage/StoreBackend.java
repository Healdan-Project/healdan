// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import com.logicaldoc.core.PersistentObject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The backend a Store's files are kept on. A Store has one row at most (a unique index on the Store); a Store with no
 * row uses Original.
 */
@Entity
@Table(name = "hd_storage_store")
public class StoreBackend extends PersistentObject {

	private static final long serialVersionUID = 1L;

	@Column(name = "hd_storeid", nullable = false)
	private long storeId;

	@Column(name = "hd_backendid", nullable = false)
	private long backendId;

	public long getStoreId() {
		return storeId;
	}

	public void setStoreId(long storeId) {
		this.storeId = storeId;
	}

	public long getBackendId() {
		return backendId;
	}

	public void setBackendId(long backendId) {
		this.backendId = backendId;
	}
}
