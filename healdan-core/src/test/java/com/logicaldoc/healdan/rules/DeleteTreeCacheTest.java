package com.logicaldoc.healdan.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.profile.LocalValueDAO;

/**
 * CE's folder delete is JDBC and evicts only folders from the second-level
 * cache. The first DAO read of a document of the deleted tree then gave
 * Hibernate a stale, live entity, and it deleted the document's attribute and
 * ACL rows, so a restore brought the document back without its profile or its
 * permissions (plan, R9). Healdan evicts the tree's documents and folders when
 * the delete commits, and the restore evicts what it brings back.
 */
public class DeleteTreeCacheTest extends AbstractRulesTestCase {

	private static final String EXT = "select ld_name, ld_stringvalue, ld_intvalue from ld_document_ext where ld_docid = ? order by ld_name";

	private static final String ACL = "select ld_groupid, ld_read, ld_write, ld_delete from ld_document_acl where ld_docid = ? order by ld_groupid";

	@Test
	public void testDeleteThenReadThenRestoreKeepsProfileAndPermissions() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document vo = documentVO(letters, "letter.eml.txt");
		vo.setValue(HealdanConstants.ATTR_DOCCLASS, "Letters");
		vo.setValue(HealdanConstants.ATTR_EMAILSUBJECT, "Demo plan");
		vo.setValue(HealdanConstants.ATTR_EMAILFROM, "a@example.com");
		Document letter = DocumentManager.get().create(content(), vo, documentHistory(alice)).getDocument();
		awaitAsyncWork();

		// A permission of the document's own
		Document stored = reload(letter);
		DocumentAccessControlEntry ace = new DocumentAccessControlEntry(lawyers.getId());
		ace.grantPermissions(Set.of(Permission.READ, Permission.WRITE, Permission.DELETE));
		stored.addAccessControlEntry(ace);
		documentDao.store(stored);
		awaitAsyncWork();

		// Cached: the document as the DAO last read it
		assertNotNull(reload(letter));
		List<Map<String, Object>> ext = jdbc.queryForList(EXT, letter.getId());
		List<Map<String, Object>> acl = jdbc.queryForList(ACL, letter.getId());
		assertFalse(acl.isEmpty());
		assertEquals("Letters", value(ext, HealdanConstants.ATTR_DOCCLASS));
		assertEquals("alice", value(ext, HealdanConstants.ATTR_AUTHOR));
		assertEquals("alice", value(ext, HealdanConstants.ATTR_TYPIST));
		assertEquals("Demo plan", value(ext, HealdanConstants.ATTR_EMAILSUBJECT));
		Set<String> locals = LocalValueDAO.get().locals(LocalValueDAO.DOCUMENT, letter.getId());
		assertEquals(Set.of(HealdanConstants.ATTR_DOCCLASS), locals);

		allowed(() -> folderDao.deleteTree(letters.getId(), folderHistory(admin)));

		// The DAOs see the delete, and reading the document leaves its rows alone
		assertNull(documentDao.findById(letter.getId()));
		assertNull(documentDao.findDocument(letter.getId()));
		assertNull(documentDao.findById(letter.getId(), true));
		assertNull(folderDao.findById(letters.getId()));
		assertEquals(ext, jdbc.queryForList(EXT, letter.getId()));
		assertEquals(acl, jdbc.queryForList(ACL, letter.getId()));

		allowed(() -> folderDao.restore(letters.getId(), correspondence.getId(), folderHistory(admin)));

		Document restored = documentDao.findById(letter.getId(), true);
		assertNotNull(restored);
		assertNotNull(folderDao.findById(letters.getId()));
		assertEquals(ext, jdbc.queryForList(EXT, letter.getId()));
		assertEquals(acl, jdbc.queryForList(ACL, letter.getId()));
		assertEquals("Letters", restored.getValue(HealdanConstants.ATTR_DOCCLASS));
		assertNotNull(restored.getAccessControlEntry(lawyers.getId()));
		assertEquals(locals, LocalValueDAO.get().locals(LocalValueDAO.DOCUMENT, letter.getId()));
	}

	@Test
	public void testRestoreElsewhereIsSeenByTheDaos() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder inner = fx.folder(letters, "Inner");
		Document letter = upload(inner, "letter.txt", alice);
		awaitAsyncWork();
		allowed(() -> folderDao.deleteTree(letters.getId(), folderHistory(admin)));
		// Something reads the deleted folders before the restore, as the restore rules do
		assertNull(folderDao.findById(letters.getId()));
		assertNull(folderDao.findById(inner.getId()));

		allowed(() -> folderDao.restore(letters.getId(), documents.getId(), folderHistory(admin)));

		Folder restored = folderDao.findById(letters.getId());
		assertNotNull(restored);
		assertEquals(documents.getId(), restored.getParentId());
		assertNotNull(folderDao.findById(inner.getId()));
		assertEquals(inner.getId(), documentDao.findById(letter.getId()).getFolder().getId());
	}

	@Test
	public void testRestoringALiveFolderIsSeenByTheDaos() throws Exception {
		// CE's restore doesn't check that the folder is deleted, so on a live one it moves it, with JDBC
		Folder deep = fx.folder(correspondence, "Deep");
		Document inside = upload(deep, "inside.txt", alice);
		awaitAsyncWork();
		assertEquals(correspondence.getId(), folderDao.findById(deep.getId()).getParentId());
		assertNotNull(documentDao.findById(inside.getId()));

		allowed(() -> folderDao.restore(deep.getId(), documents.getId(), folderHistory(admin)));

		assertEquals(documents.getId(), folderDao.findById(deep.getId()).getParentId());
		assertEquals(deep.getId(), documentDao.findById(inside.getId()).getFolder().getId());
	}

	private static Object value(List<Map<String, Object>> rows, String name) {
		return rows.stream().filter(row -> name.equals(row.get("LD_NAME"))).map(row -> row.get("LD_STRINGVALUE"))
				.findFirst().orElse(null);
	}
}
