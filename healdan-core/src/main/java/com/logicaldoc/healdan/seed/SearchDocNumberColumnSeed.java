package com.logicaldoc.healdan.seed;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Shows the Doc # in the Search tab's grid (spec 8.4, plan R139): CE's grid
 * hides {@code customId} unless it is one of the columns the grid shows
 * ({@code gui.search.columns}), so it's added there, right after the file name
 * where that is listed. It's added to the list as it is, so an admin's own
 * list stays.
 */
@Component
public class SearchDocNumberColumnSeed implements SeedStep {

	static final String COLUMNS = SearchClientColumnSeed.COLUMNS;

	static final String COLUMN = "customId";

	@Override
	public String name() {
		return "search-docnumber-column";
	}

	@Override
	public void run() throws Exception {
		ContextProperties config = Context.get().getConfig();
		if (StringUtils.isBlank(config.getProperty(COLUMNS)))
			config.setProperty(COLUMNS, SearchClientColumnSeed.CE_COLUMNS);
		if (SearchClientColumnSeed.append(config, COLUMNS, COLUMN, "filename"))
			config.write();
	}
}
