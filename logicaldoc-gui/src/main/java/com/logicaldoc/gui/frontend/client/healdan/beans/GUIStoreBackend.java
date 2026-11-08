// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

/**
 * A Store and the storage backend it keeps its files on, as the Storage tab
 * lists it (spec section 11), with what waits to move and what is stuck
 */
public class GUIStoreBackend implements Serializable {

	private static final long serialVersionUID = 1L;

	/** The Store's folder id */
	private long storeId;

	private String name;

	/** Its backend: Original's for a Store that was never given one */
	private long backendId;

	private String backendName;

	/** How many of its files are recorded */
	private long files;

	/** Their size in bytes */
	private long size;

	/** How many wait to move to its backend */
	private long waiting;

	/** Their size in bytes */
	private long waitingSize;

	/** How many the mover gave up on */
	private long stuck;

	public long getStoreId() {
		return storeId;
	}

	public void setStoreId(long storeId) {
		this.storeId = storeId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public long getBackendId() {
		return backendId;
	}

	public void setBackendId(long backendId) {
		this.backendId = backendId;
	}

	public String getBackendName() {
		return backendName;
	}

	public void setBackendName(String backendName) {
		this.backendName = backendName;
	}

	public long getFiles() {
		return files;
	}

	public void setFiles(long files) {
		this.files = files;
	}

	public long getSize() {
		return size;
	}

	public void setSize(long size) {
		this.size = size;
	}

	public long getWaiting() {
		return waiting;
	}

	public void setWaiting(long waiting) {
		this.waiting = waiting;
	}

	public long getWaitingSize() {
		return waitingSize;
	}

	public void setWaitingSize(long waitingSize) {
		this.waitingSize = waitingSize;
	}

	public long getStuck() {
		return stuck;
	}

	public void setStuck(long stuck) {
		this.stuck = stuck;
	}
}
