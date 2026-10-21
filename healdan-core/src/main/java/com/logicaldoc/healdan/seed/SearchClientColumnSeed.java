package com.logicaldoc.healdan.seed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.search.MatterSearch;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Gives the Search tab's grid the client's name of Matters and Clients hits
 * (spec 8.1): the hit's {@link MatterSearch#CLIENT_NAME} attribute is asked
 * for ({@code search.extattr}) and its column is one of those the grid shows
 * ({@code gui.search.columns}), right after the file name where that is
 * listed. Each is added to the lists as they are, so an admin's own lists
 * stay.
 */
@Component
public class SearchClientColumnSeed implements SeedStep {

	static final String EXTATTR = "default.search.extattr";

	static final String COLUMNS = "default.gui.search.columns";

	static final String COLUMN = "ext_" + MatterSearch.CLIENT_NAME;

	/**
	 * The columns CE ships in the webapp's context.properties
	 * ({@code default.gui.search.columns}), for a list that is blank: the grid
	 * shows only the columns listed
	 */
	static final String CE_COLUMNS = "filename,size,score,fileVersion,published,publisher";

	@Override
	public String name() {
		return "search-client-column";
	}

	@Override
	public void run() throws Exception {
		ContextProperties config = Context.get().getConfig();
		boolean changed = append(config, EXTATTR, MatterSearch.CLIENT_NAME, null);
		if (StringUtils.isBlank(config.getProperty(COLUMNS)))
			config.setProperty(COLUMNS, CE_COLUMNS);
		changed |= append(config, COLUMNS, COLUMN, "filename");
		if (changed)
			config.write();
	}

	/**
	 * @return true if the item was missing from the comma-separated list in
	 *         the property, and is now right after {@code after} when that is
	 *         listed, otherwise at the end
	 */
	private static boolean append(ContextProperties config, String property, String item, String after) {
		String current = StringUtils.trimToEmpty(config.getProperty(property));
		List<String> items = new ArrayList<>(Arrays.asList(StringUtils.split(current, ',')));
		items.replaceAll(String::trim);
		if (items.contains(item))
			return false;
		int at = after == null ? -1 : items.indexOf(after);
		if (at < 0)
			items.add(item);
		else
			items.add(at + 1, item);
		config.setProperty(property, String.join(",", items));
		return true;
	}
}
