// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.healdan.storage.backend.Backend;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.util.spring.Context;

/**
 * Staging (spec section 5.2): where a save into an S3 Store lands, and one whose Store can't be told yet, until the
 * mover takes it to its Store's backend. It is a built-in local backend, number 0 in the rows, with no row of its own,
 * in CE's layout: the system property {@code healdan.storageStaging}, which {@code docker/setenv.sh} sets, or else
 * {@code staging/} beside Original's folder ({@code store.1.dir}). It is on the server, so its files are plain (spec
 * section 6).
 * <p>
 * Its folder is apart from Original's and the key files' folder: it can't be either, hold either or sit inside either,
 * as it holds and deletes files of its own. Beside Original's folder, the default, is apart.
 */
public final class Staging {

	/** Staging's number in {@code hd_storage_file.hd_backendid} */
	public static final long ID = 0L;

	/** The system property that names the folder */
	public static final String FOLDER_PROPERTY = "healdan.storageStaging";

	private static final String NAME = "Staging's folder";

	private Staging() {
	}

	/**
	 * @return staging's folder
	 * @throws IllegalStateException if it isn't apart from Original's folder and the key files' ({@link #checkPlace})
	 */
	public static Path folder() {
		Path folder;
		String configured = System.getProperty(FOLDER_PROPERTY);
		if (StringUtils.isNotBlank(configured))
			folder = Paths.get(configured.trim()).toAbsolutePath().normalize();
		else
			folder = original().resolveSibling("staging");
		checkPlace(folder, original(), keys());
		return folder;
	}

	/** @return Original's folder, {@code store.1.dir}, absolute */
	public static Path original() {
		return Paths.get(Context.get().getConfig().getProperty("store.1.dir")).toAbsolutePath().normalize();
	}

	/** @return the folder of the backends' key files ({@code healdan.storageKeys}) */
	static Path keys() {
		return Context.get(BackendKeys.class).folder().toAbsolutePath().normalize();
	}

	/**
	 * @param staging staging's folder
	 * @param original Original's folder
	 * @param keys the key files' folder
	 * @throws IllegalStateException if staging's folder is, holds or sits inside either, naming both folders
	 */
	static void checkPlace(Path staging, Path original, Path keys) {
		apart(NAME, staging, "Original's folder", original);
		apart(NAME, staging, "the storage key files' folder", keys);
	}

	/**
	 * @param name what the folder is, for the message
	 * @param otherName what the other is
	 * @throws IllegalStateException if the folder is, holds or sits inside the other, naming both, before either is
	 *         written to
	 */
	static void apart(String name, Path folder, String otherName, Path other) {
		Path one = folder.toAbsolutePath().normalize();
		Path two = other.toAbsolutePath().normalize();
		if (one.startsWith(two) || two.startsWith(one))
			throw new IllegalStateException(name + " (" + one + ") can't be, hold or sit inside " + otherName + " (" + two
					+ "): give it a folder of its own");
	}

	/**
	 * @param installId this install's id, which staging's marker names
	 * @return staging as a backend, its folder and marker made if missing
	 * @throws IllegalStateException if its folder isn't apart from Original's folder and the key files'
	 */
	static Backend backend(String installId) {
		return new LocalBackend(ID, installId, folder(), true);
	}
}
