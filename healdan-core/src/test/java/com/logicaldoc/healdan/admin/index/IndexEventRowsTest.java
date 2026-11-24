// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.FolderDAO;

/**
 * The history rows Healdan writes for a document indexed, or that couldn't be (rulings P6-R5 and P6-R24).
 */
public class IndexEventRowsTest extends AbstractReindexTestCase {

	@Test
	public void testTheRows() throws Exception {
		Document document = document(pleadings, "a.txt");

		IndexEventRows.indexed(document.getId(), admin, IndexingStatus.TO_INDEX, "session-1");
		IndexEventRows.failed(document.getId(), admin, "The file is encrypted", null);
		// A document that no longer exists gets none
		IndexEventRows.failed(999_999L, admin, "Gone", null);

		List<String> rows = jdbc.queryForList("""
				select ld_event || '|' || ld_userid || '|' || ld_userlogin || '|' || coalesce(ld_comment, '') || '|'
				       || coalesce(ld_reason, '') || '|' || coalesce(ld_sessionid, '') || '|' || ld_filename || '|'
				       || ld_path
				  from ld_history where ld_event in (?, ?) order by ld_id""", String.class,
				DocumentEvent.INDEXED.toKey(), DocumentEvent.INDEXED_ERROR.toKey());
		String path = "/Client Work/" + FolderDAO.get().findById(charter.getFolderId()).getName() + "/Pleadings";
		assertEquals(List.of(
				DocumentEvent.INDEXED.toKey() + "|" + admin.getId() + "|admin||0|session-1|a.txt|" + path,
				DocumentEvent.INDEXED_ERROR.toKey() + "|" + admin.getId() + "|admin|The file is encrypted|||a.txt|" + path),
				rows);
	}
}
