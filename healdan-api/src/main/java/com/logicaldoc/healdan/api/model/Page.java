package com.logicaldoc.healdan.api.model;

import java.util.List;

/**
 * A list as the API returns it (spec section 4.5).
 *
 * @param items the items on this page
 * @param offset how many items come before them
 * @param limit the most items a page holds
 * @param total how many items there are, or null where counting would be
 *        expensive
 */
public record Page<T>(List<T> items, int offset, int limit, Long total) {

	/**
	 * @param items every item of a short list
	 * @return them all, as one page
	 */
	public static <T> Page<T> whole(List<T> items) {
		return new Page<>(items, 0, items.size(), (long) items.size());
	}
}
