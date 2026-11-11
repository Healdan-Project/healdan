// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import javax.sql.DataSource;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.searchengine.HitField;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Many documents for searches to find, made quickly: copies of an uploaded
 * document made with JDBC (its row, its properties and its ACL), and hits for
 * them added to the index in one batch, with the fields
 * {@code StandardSearchEngine.addHit} gives a hit. An upload takes about a
 * second in the tests, and {@code addHit} commits the index once for each hit.
 */
public class BulkHits {

	/** The next id {@link #copies} gives */
	private long nextId;

	public BulkHits() {
		nextId = jdbc().queryForObject("select coalesce(max(ld_id), 0) from ld_document", Long.class) + 100_000;
	}

	/**
	 * Copies a document many times, with a new id and doc # each
	 *
	 * @param templateId the document copied
	 * @param folder where the copies go
	 * @param count how many
	 * @param names the file name of each copy, by its index
	 * @return the copies' ids, in order
	 */
	public List<Long> copies(long templateId, Folder folder, int count, IntFunction<String> names) {
		JdbcTemplate jdbc = jdbc();
		long first = nextId;
		nextId += count;
		Map<String, Object> row = jdbc.queryForMap("select * from ld_document where ld_id = ?", templateId);
		List<String> columns = new ArrayList<>(row.keySet());
		List<Long> ids = new ArrayList<>();
		List<Object[]> documentRows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			long id = first + i;
			ids.add(id);
			String name = names.apply(i);
			Map<String, Object> copy = new LinkedHashMap<>();
			for (String col : columns)
				copy.put(col.toUpperCase(), row.get(col));
			copy.put("LD_ID", id);
			copy.put("LD_CUSTOMID", Long.toString(5_000_000L + id));
			copy.put("LD_FILENAME", name);
			copy.put("LD_TYPE", name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "");
			copy.put("LD_FOLDERID", folder.getId());
			documentRows.add(columns.stream().map(col -> copy.get(col.toUpperCase())).toArray());
		}
		jdbc.batchUpdate(insert("ld_document", columns), documentRows);
		for (String table : new String[] { "ld_document_ext", "ld_document_acl" }) {
			List<Map<String, Object>> children = jdbc.queryForList("select * from " + table + " where ld_docid = ?",
					templateId);
			if (children.isEmpty())
				continue;
			List<String> childColumns = new ArrayList<>(children.get(0).keySet());
			List<Object[]> rows = new ArrayList<>();
			for (long id : ids)
				for (Map<String, Object> child : children)
					rows.add(childColumns.stream()
							.map(col -> "LD_DOCID".equalsIgnoreCase(col) ? (Object) id : child.get(col)).toArray());
			jdbc.batchUpdate(insert(table, childColumns), rows);
		}
		return ids;
	}

	/**
	 * Sets a text property of documents, as their rows hold it
	 *
	 * @param ids the documents
	 * @param name the property
	 * @param type STRING or USER
	 * @param stringValue the value; for a USER, the username
	 * @param intValue for a USER, the user's id
	 */
	public void set(Collection<Long> ids, String name, Attribute.Type type, String stringValue, Long intValue) {
		JdbcTemplate jdbc = jdbc();
		List<Long> list = new ArrayList<>(ids);
		int[] updated = jdbc.batchUpdate(
				"update ld_document_ext set ld_stringvalue = ?, ld_intvalue = ? where ld_docid = ? and ld_name = ?",
				list.stream().map(id -> new Object[] { stringValue, intValue, id, name }).toList());
		List<Object[]> inserts = new ArrayList<>();
		for (int i = 0; i < list.size(); i++)
			if (updated[i] == 0)
				inserts.add(new Object[] { list.get(i), name, type.ordinal(), stringValue, intValue });
		if (!inserts.isEmpty())
			jdbc.batchUpdate("""
					insert into ld_document_ext (ld_docid, ld_name, ld_mandatory, ld_type, ld_editor, ld_position,
					                             ld_hidden, ld_readonly, ld_multiple, ld_stringvalue, ld_intvalue)
					values (?, ?, 0, ?, 0, 0, 0, 0, 0, ?, ?)
					""", inserts);
	}

	/**
	 * Forgets the documents Hibernate has cached, after their rows changed
	 */
	public void evict(Collection<Long> ids) {
		Context.get(CacheEvictor.class).evictDocuments(ids);
	}

	/**
	 * Adds a hit for each document to the index, with its row as it is now,
	 * and commits once. A hit has the fields {@code StandardSearchEngine.addHit}
	 * gives a document that CE's indexer loads from the database: CE adds the
	 * properties ({@code ext_*}) only when {@code getTemplateId()} is set, which
	 * Hibernate never sets on a loaded document, so none are added here either.
	 *
	 * @param ids the documents
	 * @param content the text of each
	 * @throws Exception if the index refuses them
	 */
	public void index(Collection<Long> ids, String content) throws Exception {
		index(ids, i -> content);
	}

	/**
	 * As {@link #index(Collection, String)}, with a text for each document
	 *
	 * @param ids the documents
	 * @param contents the text of each, by its place in {@code ids}: a word
	 *        repeated more often in texts of the same length scores higher
	 * @throws Exception if the index refuses them
	 */
	public void index(Collection<Long> ids, IntFunction<String> contents) throws Exception {
		NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(Context.get(DataSource.class));
		List<Long> list = new ArrayList<>(ids);
		Map<Long, Integer> places = new HashMap<>();
		for (int i = 0; i < list.size(); i++)
			places.put(list.get(i), i);
		// In the order given, which the index keeps among hits that score the same
		SolrInputDocument[] hits = new SolrInputDocument[list.size()];
		for (int from = 0; from < list.size(); from += 1000)
			jdbc.query("""
					select d.ld_id, d.ld_tenantid, d.ld_language, d.ld_filename, d.ld_filesize, d.ld_date,
					       d.ld_creation, d.ld_customid, d.ld_folderid, f.ld_name
					  from ld_document d join ld_folder f on f.ld_id = d.ld_folderid
					 where d.ld_id in (:ids)
					""", Map.of("ids", list.subList(from, Math.min(from + 1000, list.size()))), rs -> {
				SolrInputDocument hit = new SolrInputDocument();
				String fileName = rs.getString(4);
				hit.addField(HitField.ID.getName(), Long.toString(rs.getLong(1)));
				hit.addField(HitField.TENANT_ID.getName(), Long.toString(rs.getLong(2)));
				hit.addField(HitField.LANGUAGE.getName(), rs.getString(3));
				hit.addField(HitField.FILENAME.getName(), fileName);
				hit.addField(HitField.TITLE.getName(),
						fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName);
				hit.addField(HitField.SIZE.getName(), rs.getLong(5));
				hit.addField(HitField.DATE.getName(), rs.getTimestamp(6));
				hit.addField(HitField.CREATION.getName(), rs.getTimestamp(7));
				hit.addField(HitField.CUSTOM_ID.getName(), rs.getString(8));
				int place = places.get(rs.getLong(1));
				hit.addField(HitField.CONTENT.getName(), contents.apply(place));
				hit.addField(HitField.FOLDER_ID.getName(), rs.getLong(9));
				hit.addField(HitField.FOLDER_NAME.getName(), rs.getString(10));
				hits[place] = hit;
			});
		SolrClient server = (SolrClient) SearchEngine.get().getServer();
		for (int i = 0; i < hits.length; i += 2000)
			server.add(Arrays.asList(hits).subList(i, Math.min(i + 2000, hits.length)));
		server.commit();
	}

	private static String insert(String table, List<String> columns) {
		return "insert into %s (%s) values (%s)".formatted(table, String.join(", ", columns),
				String.join(", ", columns.stream().map(col -> "?").toList()));
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}
}
