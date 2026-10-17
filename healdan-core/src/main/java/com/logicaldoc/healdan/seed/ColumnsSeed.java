package com.logicaldoc.healdan.seed;

import org.springframework.stereotype.Component;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Sets the document columns that new users get.
 */
@Component
public class ColumnsSeed implements SeedStep {

	static final String PROPERTY = "default.gui.document.columns";

	static final String COLUMNS = "filename,fileVersion,customId,lastModified,size,ext_author,ext_docclass";

	@Override
	public String name() {
		return "columns";
	}

	@Override
	public void run() throws Exception {
		ContextProperties config = Context.get().getConfig();
		config.setProperty(PROPERTY, COLUMNS);
		config.write();
	}
}
