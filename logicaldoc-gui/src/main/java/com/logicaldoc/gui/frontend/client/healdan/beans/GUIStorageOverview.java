// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

/**
 * What the Storage tab shows (spec section 11): the backends, the Stores, the
 * files with no Store, and the settings
 */
public class GUIStorageOverview implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * Whether CE keeps its files through Healdan's storage backends; if not, the
	 * tab says storage backends are off and shows nothing else
	 */
	private boolean on;

	/** The backends, Original first */
	private GUIStorageBackend[] backends = new GUIStorageBackend[0];

	/** The Stores, by name */
	private GUIStoreBackend[] stores = new GUIStoreBackend[0];

	/** How many files with no Store wait to move, as a document saved before it had a folder */
	private long otherWaiting;

	/** Their size in bytes */
	private long otherWaitingSize;

	/** How many files with no Store the mover gave up on */
	private long otherStuck;

	/** The holding period, in days */
	private int holdDays;

	/** The read cache's size, in GB */
	private int cacheGb;

	/** How many files move at once */
	private int transfers;

	public boolean isOn() {
		return on;
	}

	public void setOn(boolean on) {
		this.on = on;
	}

	public GUIStorageBackend[] getBackends() {
		return backends;
	}

	public void setBackends(GUIStorageBackend[] backends) {
		this.backends = backends;
	}

	public GUIStoreBackend[] getStores() {
		return stores;
	}

	public void setStores(GUIStoreBackend[] stores) {
		this.stores = stores;
	}

	public long getOtherWaiting() {
		return otherWaiting;
	}

	public void setOtherWaiting(long otherWaiting) {
		this.otherWaiting = otherWaiting;
	}

	public long getOtherWaitingSize() {
		return otherWaitingSize;
	}

	public void setOtherWaitingSize(long otherWaitingSize) {
		this.otherWaitingSize = otherWaitingSize;
	}

	public long getOtherStuck() {
		return otherStuck;
	}

	public void setOtherStuck(long otherStuck) {
		this.otherStuck = otherStuck;
	}

	public int getHoldDays() {
		return holdDays;
	}

	public void setHoldDays(int holdDays) {
		this.holdDays = holdDays;
	}

	public int getCacheGb() {
		return cacheGb;
	}

	public void setCacheGb(int cacheGb) {
		this.cacheGb = cacheGb;
	}

	public int getTransfers() {
		return transfers;
	}

	public void setTransfers(int transfers) {
		this.transfers = transfers;
	}
}
