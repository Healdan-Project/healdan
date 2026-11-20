// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One administrative change, for the administration record (Admin app spec
 * section 9.3): what was done, to what, and the fields it changed with their
 * old and new values. {@link AdminEvents} adds who, when and from where.
 *
 * @param area the Admin app's area the change belongs to
 * @param action what was done, as {@code <kind>.<verb-past>}, such as
 *        {@code client.renamed}
 * @param targetKind what it was done to, such as {@code client}
 * @param targetId its id, null for none
 * @param targetName what a person recognises it by, such as a client's name,
 *        null for none
 * @param changes the fields it changed, by name; null or empty for none. They
 *        are kept in the order of their names, which the record's detail
 *        follows.
 */
public record AdminEvent(AdminArea area, String action, String targetKind, Long targetId, String targetName,
		Map<String, Change> changes) {

	public AdminEvent {
		Objects.requireNonNull(area, "area");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(targetKind, "targetKind");
		SortedMap<String, Change> sorted = new TreeMap<>();
		if (changes != null)
			changes.forEach((name, change) -> sorted.put(Objects.requireNonNull(name, "a change's name"),
					Objects.requireNonNull(change, "a change")));
		changes = Collections.unmodifiableSortedMap(sorted);
	}

	/**
	 * One field's change. A secret, such as an S3 secret or a client secret, is
	 * recorded as changed with no value: {@link #secret()}.
	 *
	 * @param before the old value, null for none
	 * @param after the new value, null for none
	 */
	public record Change(String before, String after) {

		/** The one secret change, told apart from a change of nothing to nothing by identity */
		private static final Change SECRET = new Change(null, null);

		/**
		 * @return a secret's change, which the record shows as
		 *         {@code {"secret":true}}, with neither value
		 */
		public static Change secret() {
			return SECRET;
		}

		/**
		 * @return whether this is a secret's change, which has no values
		 */
		public boolean isSecret() {
			return this == SECRET;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Change change && isSecret() == change.isSecret()
					&& Objects.equals(before, change.before) && Objects.equals(after, change.after);
		}

		@Override
		public int hashCode() {
			return Objects.hash(isSecret(), before, after);
		}
	}
}
