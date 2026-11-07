// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.healdan.storage.backend.Backend;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.util.spring.Context;

/**
 * Staging (spec section 5.2): where a save into an S3 Store lands, and one whose Store can't be told yet, until the
 * mover takes it to its Store's backend. It is a built-in local backend, number 0 in the rows, with no row of its own,
 * in CE's layout: the system property {@code healdan.storageStaging}, which {@code docker/setenv.sh} sets, or else
 * {@code staging/} beside Original's folder ({@code store.1.dir}). It is on the server, so its files are plain (spec
 * section 6).
 */
public final class Staging {

	/** Staging's number in {@code hd_storage_file.hd_backendid} */
	public static final long ID = 0L;

	/** The system property that names the folder */
	public static final String FOLDER_PROPERTY = "healdan.storageStaging";

	private Staging() {
	}

	/** @return staging's folder */
	public static Path folder() {
		String configured = System.getProperty(FOLDER_PROPERTY);
		if (StringUtils.isNotBlank(configured))
			return Paths.get(configured.trim()).toAbsolutePath().normalize();
		return original().resolveSibling("staging");
	}

	/** @return Original's folder, {@code store.1.dir} */
	static Path original() {
		return Paths.get(Context.get().getConfig().getProperty("store.1.dir")).toAbsolutePath().normalize();
	}

	/**
	 * @param installId this install's id, which staging's marker names
	 * @return staging as a backend, its folder and marker made if missing
	 */
	static Backend backend(String installId) {
		return new LocalBackend(ID, installId, folder(), true);
	}
}
