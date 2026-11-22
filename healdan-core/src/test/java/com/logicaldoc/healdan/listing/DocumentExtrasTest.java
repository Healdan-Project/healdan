// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.listing.DocumentAuthors.Author;
import com.logicaldoc.healdan.listing.DocumentExtras.Extras;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * {@link DocumentExtras}: a page's Class, Subclass, Typist and email fields,
 * read in one query per batch of ids (list columns spec section 5.2).
 * <p>
 * Uploads are slow, so each test uploads a document or two and writes their
 * properties, and copies of them, with JDBC.
 */
public class DocumentExtrasTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		admin = fx.admin();
		Folder workspace = FolderDAO.get().findById(
				fx.matter(fx.client(998, "Healdan"), 1, "Demo", fx.store("Main"), "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	@Test
	public void testReadsAPageInOneQueryPerBatch() throws Exception {
		long template = fx.upload(documents, "a.docx", "Lease", admin).getId();
		User typist = fx.user("typist");
		Date sent = Date.from(Instant.parse("2025-10-14T07:29:00Z"));
		attribute(template, HealdanConstants.ATTR_DOCSUBCLASS, Attribute.Type.STRING, "Letter", null, null);
		attribute(template, HealdanConstants.ATTR_TYPIST, Attribute.Type.USER, "typist", typist.getId(), null);
		attribute(template, HealdanConstants.ATTR_EMAILFROM, Attribute.Type.STRING, "Ann <ann@example.com>", null,
				null);
		attribute(template, HealdanConstants.ATTR_EMAILTO, Attribute.Type.STRING, "bob@example.com", null, null);
		attribute(template, HealdanConstants.ATTR_EMAILSENT, Attribute.Type.DATE, null, null, sent);
		List<Long> ids = new ArrayList<>();
		ids.add(template);
		ids.addAll(copies(template, 500));
		// Each its own Class, so a row given to another document would show
		jdbc().update("update ld_document_ext set ld_stringvalue = 'Class ' || ld_docid where ld_name = ?",
				HealdanConstants.ATTR_DOCCLASS);

		CountingDataSource counting = new CountingDataSource(Context.get(DataSource.class));
		Map<Long, Extras> extras = new DocumentExtras(counting.dataSource()).of(ids);

		// 501 ids are two batches: two queries, the typist's user read in them
		assertEquals(counting.queries().toString(), 2, counting.queries().size());
		assertEquals(501, extras.size());
		for (long id : ids)
			assertEquals(Long.toString(id), new Extras("Class " + id, "Letter", new Author("typist", "typist typist"),
					"Ann <ann@example.com>", "bob@example.com", sent), extras.get(id));

		// None asked, none read
		counting.reset();
		assertTrue(new DocumentExtras(counting.dataSource()).of(List.of()).isEmpty());
		assertEquals(0, counting.queries().size());
	}

	@Test
	public void testTypistNamedAsAuthorIs() throws Exception {
		long doc = fx.upload(documents, "a.docx", "Lease", admin).getId();
		User named = fx.user("ann");
		jdbc().update("update ld_user set ld_firstname = 'Ann', ld_name = 'Smith' where ld_id = ?", named.getId());
		User surname = fx.user("bob");
		jdbc().update("update ld_user set ld_firstname = null, ld_name = 'Jones' where ld_id = ?", surname.getId());
		User bare = fx.user("carol");
		jdbc().update("update ld_user set ld_firstname = null, ld_name = null where ld_id = ?", bare.getId());
		User deleted = fx.user("dave");
		jdbc().update("update ld_user set ld_deleted = 1 where ld_id = ?", deleted.getId());

		/*
		 * The kept username with its user; a user kept without its username; a surname alone; no name at all; a user
		 * deleted since; a username with no user; a blank one; nothing
		 */
		Object[][] cases = { { "ann", named.getId(), new Author("ann", "Ann Smith") },
				{ null, named.getId(), new Author("ann", "Ann Smith") },
				{ "bob", surname.getId(), new Author("bob", "Jones") },
				{ "carol", bare.getId(), new Author("carol", "carol") },
				{ "dave", deleted.getId(), new Author("dave", "dave") }, { "erin", null, new Author("erin", "erin") },
				{ " ", null, null }, { null, null, null } };
		for (Object[] each : cases) {
			String kept = (String) each[0];
			Long userId = (Long) each[1];
			attribute(doc, HealdanConstants.ATTR_AUTHOR, Attribute.Type.USER, kept, userId, null);
			attribute(doc, HealdanConstants.ATTR_TYPIST, Attribute.Type.USER, kept, userId, null);
			String label = kept + " " + userId;

			Author author = DocumentAuthors.get().of(List.of(doc)).get(doc);
			Extras extras = DocumentExtras.get().of(List.of(doc)).get(doc);
			assertEquals(label, each[2], author);
			assertEquals(label, author, extras == null ? null : extras.typist());
		}
	}

	@Test
	public void testNothingStoredIsNull() throws Exception {
		long doc = fx.upload(documents, "a.docx", "Lease", admin).getId();
		for (String name : List.of(HealdanConstants.ATTR_DOCCLASS, HealdanConstants.ATTR_DOCSUBCLASS,
				HealdanConstants.ATTR_EMAILFROM, HealdanConstants.ATTR_EMAILTO))
			attribute(doc, name, Attribute.Type.STRING, null, null, null);
		attribute(doc, HealdanConstants.ATTR_TYPIST, Attribute.Type.USER, null, null, null);
		attribute(doc, HealdanConstants.ATTR_EMAILSENT, Attribute.Type.DATE, null, null, null);

		// Rows with no value, and a blank one, are nothing: the document has no entry
		assertNull(DocumentExtras.get().of(List.of(doc)).get(doc));
		attribute(doc, HealdanConstants.ATTR_EMAILFROM, Attribute.Type.STRING, "  ", null, null);
		assertNull(DocumentExtras.get().of(List.of(doc)).get(doc));
		// Nor are no rows at all
		jdbc().update("delete from ld_document_ext where ld_docid = ?", doc);
		assertNull(DocumentExtras.get().of(List.of(doc)).get(doc));

		// One value, and the rest null
		attribute(doc, HealdanConstants.ATTR_DOCSUBCLASS, Attribute.Type.STRING, "Letter", null, null);
		Extras extras = new Extras(null, "Letter", null, null, null, null);
		assertEquals(extras, DocumentExtras.get().of(List.of(doc)).get(doc));

		// Another property is none of them: a document with only a CC has no entry
		long other = fx.upload(documents, "b.docx", "Lease", admin).getId();
		jdbc().update("delete from ld_document_ext where ld_docid = ?", other);
		attribute(other, HealdanConstants.ATTR_EMAILCC, Attribute.Type.STRING, "cc@example.com", null, null);
		assertEquals(Map.of(doc, extras), DocumentExtras.get().of(List.of(doc, other)));
	}

	/**
	 * Copies a document as new ones with JDBC: its row and its properties,
	 * with new ids and doc #s
	 *
	 * @return the copies' ids
	 */
	private static List<Long> copies(long templateId, int count) {
		JdbcTemplate jdbc = jdbc();
		long first = jdbc.queryForObject("select coalesce(max(ld_id), 0) from ld_document", Long.class) + 100_000;
		Map<String, Object> row = jdbc.queryForMap("select * from ld_document where ld_id = ?", templateId);
		List<Map<String, Object>> properties = jdbc.queryForList("select * from ld_document_ext where ld_docid = ?",
				templateId);
		List<Long> ids = new ArrayList<>();
		List<Object[]> documentRows = new ArrayList<>();
		List<Object[]> propertyRows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			long id = first + i;
			ids.add(id);
			Map<String, Object> copy = new LinkedHashMap<>(row);
			copy.replaceAll((column, value) -> switch (column.toLowerCase()) {
			case "ld_id" -> id;
			case "ld_customid" -> Long.toString(5_000_000L + id);
			default -> value;
			});
			documentRows.add(copy.values().toArray());
			for (Map<String, Object> property : properties)
				propertyRows.add(property.entrySet().stream()
						.map(e -> "ld_docid".equalsIgnoreCase(e.getKey()) ? (Object) id : e.getValue()).toArray());
		}
		jdbc.batchUpdate(insert("ld_document", row.keySet()), documentRows);
		jdbc.batchUpdate(insert("ld_document_ext", properties.get(0).keySet()), propertyRows);
		return ids;
	}

	private static String insert(String table, Collection<String> columns) {
		return "insert into %s (%s) values (%s)".formatted(table, String.join(", ", columns),
				String.join(", ", columns.stream().map(column -> "?").toList()));
	}

	/** Sets a property's value, adding its row if the document has none */
	private static void attribute(long docId, String name, Attribute.Type type, String stringValue, Long intValue,
			Date dateValue) {
		Timestamp date = dateValue == null ? null : new Timestamp(dateValue.getTime());
		JdbcTemplate jdbc = jdbc();
		if (jdbc.update(
				"update ld_document_ext set ld_stringvalue = ?, ld_intvalue = ?, ld_datevalue = ? where ld_docid = ? and ld_name = ?",
				stringValue, intValue, date, docId, name) == 0)
			jdbc.update("""
					insert into ld_document_ext (ld_docid, ld_name, ld_mandatory, ld_type, ld_editor, ld_position,
					                             ld_hidden, ld_readonly, ld_multiple, ld_stringvalue, ld_intvalue, ld_datevalue)
					values (?, ?, 0, ?, 0, 0, 0, 0, 0, ?, ?, ?)
					""", docId, name, type.ordinal(), stringValue, intValue, date);
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}
}
