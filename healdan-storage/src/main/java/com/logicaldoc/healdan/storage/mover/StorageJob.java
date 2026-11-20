// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import java.util.function.BooleanSupplier;

import com.logicaldoc.healdan.admin.jobs.HealdanJob;
import com.logicaldoc.healdan.storage.StoreTracking;

/**
 * One of the storage's background jobs (Admin app spec section 9.5): the mover, the retests, the purge, the alert and
 * the nightly check. None runs on a server whose Store isn't {@code HealdanStorage}, so there each is listed switched
 * off, and the Dashboard counts none as on schedule.
 */
public abstract class StorageJob implements HealdanJob {

	/** Whether CE keeps its files through {@code HealdanStorage}; tests turn it off */
	volatile BooleanSupplier inUse = StoreTracking::active;

	@Override
	public boolean enabled() {
		return inUse.getAsBoolean();
	}
}
