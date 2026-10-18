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
public final class StoredProfile {

	private StoredProfile() {
	}

	/**
	 * @param documentId a document
	 * @param docClass a Class value
	 * @param subclass a Subclass value
	 * @return true if the document is stored with exactly these
	 */
	public static boolean unchanged(long documentId, Object docClass, Object subclass) {
		return Objects.equals(text(docClass), stored(documentId, HealdanConstants.ATTR_DOCCLASS))
				&& Objects.equals(text(subclass), stored(documentId, HealdanConstants.ATTR_DOCSUBCLASS));
	}

	private static String text(Object value) {
		return value == null || value.toString().isEmpty() ? null : value.toString();
	}

	private static String stored(long documentId, String attribute) {
		List<String> rows = new JdbcTemplate(Context.get(DataSource.class)).queryForList(
				"select ld_stringvalue from ld_document_ext where ld_docid = ? and ld_name = ?", String.class,
				documentId, attribute);
		return rows.isEmpty() ? null : text(rows.get(0));
	}

	/**
	 * @param documentId a document
	 * @param document the document as a check-in is about to store it
	 * @param names the attributes to compare
	 * @return true if any of those attributes holds another value than the
	 *         database has
	 */
	public static boolean changed(long documentId, com.logicaldoc.core.document.Document document,
			java.util.Collection<String> names) {
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		java.util.Map<String, java.util.Map<String, Object>> stored = new java.util.HashMap<>();
		jdbc.queryForList(
				"select ld_name, ld_stringvalue, ld_intvalue, ld_doublevalue, ld_datevalue from ld_document_ext where ld_docid = ?",
				documentId).forEach(row -> stored.put((String) row.get("LD_NAME"), row));
		for (String name : names) {
			com.logicaldoc.core.metadata.Attribute own = document.getAttribute(name);
			java.util.Map<String, Object> row = stored.get(name);
			if (own == null && row == null)
				continue;
			if (own == null || row == null) {
				if ((own != null && !Inheritance.isBlank(own.getValue()))
						|| (row != null && !Inheritance.isBlank(row.get("LD_STRINGVALUE"))))
					return true;
				continue;
			}
			if (!Objects.equals(text(own.getStringValue()), text(row.get("LD_STRINGVALUE")))
					|| !Objects.equals(own.getIntValue(), row.get("LD_INTVALUE") != null
							? ((Number) row.get("LD_INTVALUE")).longValue() : null)
					|| !Objects.equals(own.getDoubleValue(), row.get("LD_DOUBLEVALUE") != null
							? ((Number) row.get("LD_DOUBLEVALUE")).doubleValue() : null)
					|| !Objects.equals(own.getDateValue() != null ? own.getDateValue().getTime() : null,
							row.get("LD_DATEVALUE") != null ? ((java.util.Date) row.get("LD_DATEVALUE")).getTime()
									: null))
				return true;
		}
		return false;
	}
}
