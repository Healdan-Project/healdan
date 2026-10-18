package com.logicaldoc.healdan.profile;

import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.util.spring.Context;

/**
 * A document's Class and Subclass as the database holds them, for a rule that
 * mustn't refuse a combination that was already there.
 */
final class StoredProfile {

	private StoredProfile() {
	}

	/**
	 * @param documentId a document
	 * @param docClass a Class value
	 * @param subclass a Subclass value
	 * @return true if the document is stored with exactly these
	 */
	static boolean unchanged(long documentId, String docClass, String subclass) {
		return Objects.equals(docClass, stored(documentId, HealdanConstants.ATTR_DOCCLASS))
				&& Objects.equals(subclass, stored(documentId, HealdanConstants.ATTR_DOCSUBCLASS));
	}

	private static String stored(long documentId, String attribute) {
		List<String> rows = new JdbcTemplate(Context.get(DataSource.class)).queryForList(
				"select ld_stringvalue from ld_document_ext where ld_docid = ? and ld_name = ?", String.class,
				documentId, attribute);
		return rows.isEmpty() ? null : rows.get(0);
	}
}
