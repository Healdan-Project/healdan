package com.logicaldoc.healdan.search;

import com.logicaldoc.core.searchengine.SearchOptions;

/**
 * Options of Healdan's searches by number or name: the doc # lookup, the
 * Matters scope and the Clients scope. They use only the common options: the
 * expression, the user and the maximum number of hits.
 * <p>
 * CE creates these by reflection ({@code Search.newOptions}), which then sets
 * the type.
 */
public class HealdanSearchOptions extends SearchOptions {

	private static final long serialVersionUID = 1L;

	public HealdanSearchOptions() {
		super();
	}
}
