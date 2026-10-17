package com.logicaldoc.healdan.seed;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Sets the document columns that new users get, unless an admin already chose
 * their own.
 */
@Component
public class ColumnsSeed implements SeedStep {

	static final String PROPERTY = "default.gui.document.columns";

	static final String COLUMNS = "filename,fileVersion,customId,lastModified,size,ext_author,ext_docclass";

	/** The value CE ships in context.properties; any other value is an admin's choice */
	static final String CE_DEFAULT = "filename,size,fileVersion,published,publisher";

	@Override
	public String name() {
		return "columns";
	}

	@Override
	public void run() throws Exception {
		ContextProperties config = Context.get().getConfig();
		String current = config.getProperty(PROPERTY);
		if (StringUtils.isNotBlank(current) && !CE_DEFAULT.equals(current.trim()))
			return;
		config.setProperty(PROPERTY, COLUMNS);
		config.write();
	}
}
