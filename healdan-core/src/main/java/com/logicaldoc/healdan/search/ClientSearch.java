package com.logicaldoc.healdan.search;

import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.ClientMatterRef;

/**
 * Search {@link HealdanConstants#SEARCH_CLIENTS}: the Clients scope (spec
 * 8.1). It lists the matters of every client whose number is the input or
 * whose name contains it, ignoring case, as {@link MatterSearch} lists them.
 */
public class ClientSearch extends MatterSearch {

	@Override
	protected String condition(String input, MapSqlParameterSource params) {
		params.addValue("text", containing(input));
		String match = "lower(c.hd_name) like :text escape '!'";
		Optional<ClientMatterRef> ref = ClientMatterRef.parse(input);
		if (ref.isPresent() && ref.get().isNumberOnly()) {
			params.addValue("client", ref.get().client());
			match = "c.hd_number = :client or " + match;
		}
		return "c.ld_deleted = 0 and (" + match + ")";
	}
}
