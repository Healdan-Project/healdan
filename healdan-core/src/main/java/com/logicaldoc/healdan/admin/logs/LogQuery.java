// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import java.util.Locale;
import java.util.Set;

/**
 * What a read of a log asks for (Admin app spec section 8.8): where it starts, at most one of {@code before} and
 * {@code after}, the newest entries with neither, and the entries it keeps.
 *
 * @param before the entries before this position, going back; or null
 * @param after the entries at this position or after it, going forward; or null
 * @param level the entries kept by their level
 * @param text text the entries kept hold, in any case; null or blank for any
 */
public record LogQuery(LogPosition before, LogPosition after, Level level, String text) {

	/** The entries a read keeps by their level */
	public enum Level {
		/** Every entry, those with no level too */
		ALL(null),
		/** Warnings and errors */
		WARN(Set.of("WARN", "ERROR", "FATAL")),
		/** Errors */
		ERROR(Set.of("ERROR", "FATAL"));

		private final Set<String> levels;

		Level(Set<String> levels) {
			this.levels = levels;
		}

		/**
		 * @param level an entry's level, null for none
		 * @return whether an entry with it is kept
		 */
		boolean keeps(String level) {
			return levels == null || (level != null && levels.contains(level));
		}
	}

	/**
	 * Takes the whitespace off either end of the text, compared in any case, and a blank one for none.
	 *
	 * @throws IllegalArgumentException for both {@code before} and {@code after}
	 */
	public LogQuery {
		if (before != null && after != null)
			throw new IllegalArgumentException("A read goes back or forward, not both");
		if (level == null)
			level = Level.ALL;
		if (text != null) {
			text = text.strip().toLowerCase(Locale.ROOT);
			if (text.isEmpty())
				text = null;
		}
	}
}
