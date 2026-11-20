// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import java.util.List;
import java.util.Map;

/**
 * The Errors screen's areas (Admin app spec sections 8.8 and 9.4), each
 * error's from the package or class of the logger it was logged through, by
 * the longest entry of a table that names it or a package it's in; anything
 * the table doesn't name is the Server's.
 */
public final class ErrorAreas {

	public static final String PREVIEWS = "Previews";

	public static final String STORAGE = "Storage";

	public static final String SEARCH_INDEX = "Search Index";

	public static final String EMAIL = "Email";

	public static final String SIGN_IN = "Sign-In";

	public static final String BACKGROUND_JOBS = "Background Jobs";

	public static final String SERVER = "Server";

	/** Every area, in the order the Errors screen offers them */
	public static final List<String> ALL = List.of(PREVIEWS, STORAGE, SEARCH_INDEX, EMAIL, SIGN_IN, BACKGROUND_JOBS,
			SERVER);

	/** Logger names, or the packages and classes they start with, and their areas */
	private static final Map<String, String> TABLE = Map.ofEntries(
			// Previews: Healdan's worker, and CE's converters and thumbnails it calls
			Map.entry("com.logicaldoc.healdan.preview", PREVIEWS),
			Map.entry("com.logicaldoc.core.conversion", PREVIEWS),
			Map.entry("com.logicaldoc.core.document.thumbnail", PREVIEWS),
			// Storage: healdan-storage, CE's store, and the log of its deletions
			Map.entry("com.logicaldoc.healdan.storage", STORAGE),
			Map.entry("com.logicaldoc.core.store", STORAGE),
			Map.entry("STORE_DELETIONS", STORAGE),
			Map.entry("software.amazon.awssdk", STORAGE),
			// Search Index: CE's search engine with its Indexer, the parsers that read documents for it, and Solr
			Map.entry("com.logicaldoc.core.searchengine", SEARCH_INDEX),
			Map.entry("com.logicaldoc.core.parser", SEARCH_INDEX),
			Map.entry("com.logicaldoc.healdan.parser", SEARCH_INDEX),
			Map.entry("org.apache.solr", SEARCH_INDEX),
			Map.entry("org.apache.lucene", SEARCH_INDEX),
			// Email: Healdan's email rules and their API, and CE's mail
			Map.entry("com.logicaldoc.healdan.email", EMAIL),
			Map.entry("com.logicaldoc.healdan.api.EmailFiling", EMAIL),
			Map.entry("com.logicaldoc.healdan.api.EmailResource", EMAIL),
			Map.entry("com.logicaldoc.healdan.api.MessageResource", EMAIL),
			Map.entry("com.logicaldoc.core.communication", EMAIL),
			// Sign-In: providers, device tokens, CE's authentication, sessions and keys
			Map.entry("com.logicaldoc.healdan.signin", SIGN_IN),
			Map.entry("com.logicaldoc.healdan.token", SIGN_IN),
			Map.entry("com.logicaldoc.healdan.api.SignInResource", SIGN_IN),
			Map.entry("com.logicaldoc.healdan.api.TokenFilter", SIGN_IN),
			Map.entry("com.logicaldoc.core.security.authentication", SIGN_IN),
			Map.entry("com.logicaldoc.core.security.apikey", SIGN_IN),
			Map.entry("com.logicaldoc.core.security.SessionManager", SIGN_IN),
			Map.entry("com.logicaldoc.core.security.LoginThrottle", SIGN_IN),
			Map.entry("com.logicaldoc.core.security.HealdanThrottle", SIGN_IN),
			// Background Jobs: CE's tasks and scheduler, the tasks outside its task package, and the Trash's job
			Map.entry("com.logicaldoc.core.task", BACKGROUND_JOBS),
			Map.entry("com.logicaldoc.core.job", BACKGROUND_JOBS),
			Map.entry("com.logicaldoc.core.stats.StatsCollector", BACKGROUND_JOBS),
			Map.entry("com.logicaldoc.core.document.DigestProcessor", BACKGROUND_JOBS),
			Map.entry("com.logicaldoc.core.document.TagsProcessor", BACKGROUND_JOBS),
			Map.entry("com.logicaldoc.core.folder.PathCalculator", BACKGROUND_JOBS),
			Map.entry("com.logicaldoc.healdan.trash.TrashDestroyer", BACKGROUND_JOBS),
			Map.entry("com.logicaldoc.healdan.trash.TrashStartup", BACKGROUND_JOBS),
			Map.entry("org.quartz", BACKGROUND_JOBS));

	private ErrorAreas() {
	}

	/**
	 * @param logger a logger's name, or null
	 * @return its area: the area of the longest entry that is the name, or a
	 *         package or class the name is in; {@link #SERVER} when none is
	 */
	public static String of(String logger) {
		if (logger == null)
			return SERVER;
		// From the whole name to its first part, the longest entry first
		String name = logger;
		while (!name.isEmpty()) {
			String area = TABLE.get(name);
			if (area != null)
				return area;
			int end = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
			if (end < 0)
				break;
			name = name.substring(0, end);
		}
		return SERVER;
	}
}
