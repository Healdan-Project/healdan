// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import java.util.List;

/**
 * One of the Dashboard's ten Server Health tiles (Admin app spec section 8.1), {@code openapi-admin.yaml}'s
 * {@code HealthTile}.
 *
 * @param name which tile, one of {@link #NAMES}
 * @param status {@link #OK}, {@link #ATTENTION} when something needs acting on or the tile's part couldn't be read, or
 *        {@link #OFF} when what it shows isn't set up or is switched off
 * @param detail the tile's line in English; null when {@code problem} is set
 * @param used bytes: the Disk's and the Memory's used, and the Database's size; null for the other tiles, and when
 *        {@code problem} is set
 * @param total bytes: the Disk's and the Memory's total; null for the other tiles, and when {@code problem} is set
 * @param link the screen behind the tile: the Admin app's once it has shipped, the Classic Interface until then
 * @param problem why the tile's part couldn't be read, a fixed line in English, never an error's own text; null when
 *        it was read
 */
public record HealthTile(String name, String status, String detail, Long used, Long total, String link,
		String problem) {

	public static final String SERVER = "server";

	public static final String DATABASE = "database";

	public static final String DISK = "disk";

	public static final String MEMORY = "memory";

	public static final String SIGN_IN = "sign-in";

	public static final String STORAGE_BACKENDS = "storage-backends";

	public static final String SEARCH_INDEX = "search-index";

	public static final String PREVIEWS = "previews";

	public static final String BACKGROUND_JOBS = "background-jobs";

	public static final String OUTGOING_EMAIL = "outgoing-email";

	/** The ten tiles, in the order the Dashboard shows them, as the contract's {@code HealthTile.name} lists them */
	public static final List<String> NAMES = List.of(SERVER, DATABASE, DISK, MEMORY, SIGN_IN, STORAGE_BACKENDS,
			SEARCH_INDEX, PREVIEWS, BACKGROUND_JOBS, OUTGOING_EMAIL);

	public static final String OK = "ok";

	public static final String ATTENTION = "attention";

	public static final String OFF = "off";

	/**
	 * @param name the tile
	 * @param link the screen behind it
	 * @param problem why its part couldn't be read
	 * @return a tile whose part couldn't be read: {@link #ATTENTION}, with no line and no bytes
	 */
	static HealthTile unreadable(String name, String link, String problem) {
		return new HealthTile(name, ATTENTION, null, null, null, link, problem);
	}
}
