package com.logicaldoc.healdan.docnumber;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.util.spring.Context;

/**
 * Finds documents by the legacy doc # they carry from the system they came
 * from (the {@code legacydocnum} attribute, spec 6.3). The match is exact and
 * the number is never read as a doc #.
 */
public final class LegacyDocNumbers {

	private static final String QUERY = """
			select d.ld_id
			  from ld_document d
			  join ld_document_ext e on e.ld_docid = d.ld_id
			 where e.ld_name = ?
			   and e.ld_stringvalue = ?
			   and d.ld_deleted = 0
			   and d.ld_tenantid = ?
			 order by d.ld_id
			""";

	private LegacyDocNumbers() {
	}

	/**
	 * @param legacyNumber the legacy doc #, matched exactly
	 * @param tenantId the tenant whose documents are searched
	 * @return the ids of the live documents with that legacy doc #, lowest
	 *         first; none for a null or blank number
	 * @throws PersistenceException error at data layer
	 */
	public static List<Long> find(String legacyNumber, long tenantId) throws PersistenceException {
		if (legacyNumber == null || legacyNumber.isBlank())
			return List.of();
		try {
			return new JdbcTemplate(Context.get(DataSource.class)).queryForList(QUERY, Long.class,
					HealdanConstants.ATTR_LEGACYDOCNUM, legacyNumber, tenantId);
		} catch (RuntimeException e) {
			throw new PersistenceException(e);
		}
	}
}
