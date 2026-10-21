package com.logicaldoc.healdan.seed;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Makes the documents grids ask for the Author and Class attributes, which the
 * default columns ({@link ColumnsSeed}) show. CE ships no attributes there, so
 * those columns stayed empty; an admin's own list stays as it is.
 */
@Component
public class SearchAttributesSeed implements SeedStep {

	static final String PROPERTY = "default.search.extattr";

	static final String ATTRIBUTES = "author,docclass";

	@Override
	public String name() {
		return "search-attributes";
	}

	@Override
	public void run() throws Exception {
		ContextProperties config = Context.get().getConfig();
		if (StringUtils.isNotBlank(config.getProperty(PROPERTY)))
			return;
		config.setProperty(PROPERTY, ATTRIBUTES);
		config.write();
	}
}
