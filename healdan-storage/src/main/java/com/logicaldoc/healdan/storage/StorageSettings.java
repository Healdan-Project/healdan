// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * The storage settings in {@code hd_setting} (spec section 5.4), with their defaults and ranges. A value that is
 * missing or not a number reads as the default; a setter refuses a number outside its range.
 */
@Component
public class StorageSettings {

	public static final String HOLD_DAYS = "storage.holdDays";

	public static final String CACHE_GB = "storage.cacheGb";

	public static final String TRANSFERS = "storage.transfers";

	public static final String SWEEP_SECONDS = "storage.sweepSeconds";

	public static final String RETEST_SECONDS = "storage.retestSeconds";

	/** The day the last alert went */
	public static final String ALERT_SENT = "storage.alert.sent";

	/** Names this install in every backend's marker */
	public static final String INSTALL_ID = "storage.installId";

	public static final int DEFAULT_HOLD_DAYS = 30;

	public static final int DEFAULT_CACHE_GB = 20;

	public static final int DEFAULT_TRANSFERS = 4;

	public static final int DEFAULT_SWEEP_SECONDS = 300;

	public static final int DEFAULT_RETEST_SECONDS = 60;

	/** The longest holding period, in days */
	public static final int MAX_HOLD_DAYS = 365;

	/** The largest cache, in GB */
	public static final int MAX_CACHE_GB = 10000;

	/** The most transfers at once */
	public static final int MAX_TRANSFERS = 16;

	private final HealdanSettings settings;

	@Autowired
	public StorageSettings(HealdanSettings settings) {
		this.settings = settings;
	}

	/**
	 * @return the bean
	 */
	public static StorageSettings get() {
		return Context.get(StorageSettings.class);
	}

	/**
	 * @return how many days a deleted or replaced copy is held, 1 to 365
	 */
	public int holdDays() {
		return read(HOLD_DAYS, DEFAULT_HOLD_DAYS, 1, MAX_HOLD_DAYS);
	}

	public void setHoldDays(int days) {
		write(HOLD_DAYS, days, 1, MAX_HOLD_DAYS);
	}

	/**
	 * @return the cache's size in gigabytes, 1 to 10000
	 */
	public int cacheGb() {
		return read(CACHE_GB, DEFAULT_CACHE_GB, 1, MAX_CACHE_GB);
	}

	public void setCacheGb(int gigabytes) {
		write(CACHE_GB, gigabytes, 1, MAX_CACHE_GB);
	}

	/**
	 * @return how many files move at once, 1 to 16
	 */
	public int transfers() {
		return read(TRANSFERS, DEFAULT_TRANSFERS, 1, MAX_TRANSFERS);
	}

	public void setTransfers(int transfers) {
		write(TRANSFERS, transfers, 1, MAX_TRANSFERS);
	}

	/**
	 * @return how often the mover sweeps, in seconds, 5 to 3600
	 */
	public int sweepSeconds() {
		return read(SWEEP_SECONDS, DEFAULT_SWEEP_SECONDS, 5, 3600);
	}

	public void setSweepSeconds(int seconds) {
		write(SWEEP_SECONDS, seconds, 5, 3600);
	}

	/**
	 * @return how often a failing backend is tested again, in seconds, 5 to 3600
	 */
	public int retestSeconds() {
		return read(RETEST_SECONDS, DEFAULT_RETEST_SECONDS, 5, 3600);
	}

	public void setRetestSeconds(int seconds) {
		write(RETEST_SECONDS, seconds, 5, 3600);
	}

	/**
	 * @return this install's id, which names the install in every backend's marker
	 * @throws IllegalStateException if there is none: only the seed makes it, since a new one would make every
	 *         backend's marker name another install
	 */
	public String installId() {
		String id = settings.get(INSTALL_ID);
		if (id == null || id.isBlank())
			throw new IllegalStateException("The setting " + INSTALL_ID + " is missing. It names this install in every "
					+ "storage backend's marker, so it isn't made again: restore it from a backup, or from a backend's "
					+ ".healdan-backend marker.");
		return id;
	}

	/**
	 * Makes this install's id if there is none. Only the seed calls it.
	 *
	 * @return true if it was made
	 */
	public boolean makeInstallId() {
		return settings.setIfMissing(INSTALL_ID, UUID.randomUUID().toString());
	}

	private int read(String key, int fallback, int min, int max) {
		String value = settings.get(key);
		if (value == null)
			return fallback;
		try {
			int number = Integer.parseInt(value.trim());
			return number < min || number > max ? fallback : number;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void write(String key, int value, int min, int max) {
		if (value < min || value > max)
			throw new IllegalArgumentException("%s must be from %d to %d".formatted(key, min, max));
		settings.set(key, Integer.toString(value));
	}
}
