package com.logicaldoc.healdan.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.util.spring.Context;

/**
 * Finds documents by the words in their Comments. The full-text index keeps a
 * text property only as one exact value, never as words, so the Documents
 * search asks the database: a document matches when its Comments hold every
 * word of the query, in any case. A {@code LIKE} scan, which spec M3 accepts
 * for a property that few documents carry.
 */
public final class CommentsMatch {

	/** Marks the escaped characters in a {@code LIKE} pattern */
	private static final char ESCAPE = '!';

	/** Words Lucene reads as operators, not as text to find */
	private static final Set<String> OPERATORS = Set.of("AND", "OR", "NOT");

	private static final String SELECT = """
			select d.ld_id
			  from ld_document d
			  join ld_document_ext b on b.ld_docid = d.ld_id
			 where b.ld_name = :name
			   and d.ld_deleted = 0
			   and d.ld_tenantid = :tenant
			""";

	private CommentsMatch() {
	}

	/**
	 * @param q the user's query
	 * @param tenantId the tenant whose documents are searched
	 * @param max the most ids to return; 0 for all
	 * @return the live documents whose Comments hold every word of {@code q},
	 *         newest first; none if {@code q} has no word
	 */
	public static List<Long> documentIds(String q, long tenantId, int max) {
		return documentIds(q, tenantId, max, null, false);
	}

	/**
	 * @param q the user's query
	 * @param tenantId the tenant whose documents are searched
	 * @param max the most ids to return; 0 for all
	 * @param folderId the folder to stay in, or null for anywhere
	 * @param subPath true to include the folder's subfolders, at any depth
	 * @return the live documents in that place whose Comments hold every word
	 *         of {@code q}, newest first; none if {@code q} has no word
	 */
	public static List<Long> documentIds(String q, long tenantId, int max, Long folderId, boolean subPath) {
		List<String> words = words(q);
		if (words.isEmpty())
			return List.of();

		StringBuilder sql = new StringBuilder(SELECT);
		MapSqlParameterSource params = new MapSqlParameterSource("name", HealdanConstants.ATTR_COMMENTS)
				.addValue("tenant", tenantId);
		for (int i = 0; i < words.size(); i++) {
			sql.append(" and lower(b.ld_stringvalue) like :w").append(i).append(" escape '").append(ESCAPE)
					.append("'\n");
			params.addValue("w" + i, "%" + escape(words.get(i)) + "%");
		}
		if (folderId != null) {
			Collection<Long> folders = subPath ? FolderDAO.get().findFolderIdInTree(folderId, false)
					: List.of(folderId);
			if (folders.isEmpty())
				return List.of();
			sql.append(" and d.ld_folderid in (:folders)\n");
			params.addValue("folders", folders);
		}
		sql.append(" order by d.ld_id desc");

		JdbcTemplate template = new JdbcTemplate(Context.get(DataSource.class));
		template.setMaxRows(Math.max(max, 0));
		return new NamedParameterJdbcTemplate(template).queryForList(sql.toString(), params, Long.class);
	}

	/**
	 * @return the words of the query, lower case, without Lucene's operators,
	 *         quotes, parentheses and wildcards
	 */
	private static List<String> words(String q) {
		List<String> words = new ArrayList<>();
		if (q == null)
			return words;
		for (String raw : q.trim().split("\\s+")) {
			if (OPERATORS.contains(raw))
				continue;
			String word = raw.replaceAll("[\"*()]", "").replaceFirst("^[+-]+", "").toLowerCase();
			if (!word.isEmpty())
				words.add(word);
		}
		return words;
	}

	private static String escape(String word) {
		return word.replace(String.valueOf(ESCAPE), ESCAPE + "" + ESCAPE).replace("%", ESCAPE + "%").replace("_",
				ESCAPE + "_");
	}
}
