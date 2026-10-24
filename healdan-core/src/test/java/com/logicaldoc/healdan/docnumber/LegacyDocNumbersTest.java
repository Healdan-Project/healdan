package com.logicaldoc.healdan.docnumber;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * Finding documents by their legacy doc # (spec 6.3): exact, live documents
 * only, within a tenant.
 */
public class LegacyDocNumbersTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		admin = fx.admin();
		Folder store = fx.store("Main");
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private Document file(String name, String legacy) throws Exception {
		Document d = fx.upload(documents, name, "text " + name, admin);
		Document current = DocumentDAO.get().findById(d.getId(), true);
		current.setValue(HealdanConstants.ATTR_LEGACYDOCNUM, legacy);
		DocumentDAO.get().store(current, fx.documentHistory(admin));
		return d;
	}

	@Test
	public void testFindsEveryLiveDocumentWithTheExactNumber() throws Exception {
		Document first = file("a.txt", "ABC-1");
		Document second = file("b.txt", "ABC-1");
		file("c.txt", "ABC-10");

		assertEquals(List.of(first.getId(), second.getId()), LegacyDocNumbers.find("ABC-1", Tenant.DEFAULT_ID));
		assertEquals(List.of(), LegacyDocNumbers.find("ABC", Tenant.DEFAULT_ID));
		assertEquals(List.of(), LegacyDocNumbers.find("abc-1", Tenant.DEFAULT_ID));
		assertEquals(List.of(), LegacyDocNumbers.find("ABC-1' or '1'='1", Tenant.DEFAULT_ID));
		assertEquals(List.of(), LegacyDocNumbers.find("", Tenant.DEFAULT_ID));
		assertEquals(List.of(), LegacyDocNumbers.find("ABC-1", Tenant.DEFAULT_ID + 1));
	}

	@Test
	public void testDeletedDocumentIsLeftOut() throws Exception {
		Document kept = file("a.txt", "ABC-1");
		Document gone = file("b.txt", "ABC-1");
		DocumentDAO.get().delete(gone.getId(), fx.documentHistory(admin));

		assertEquals(List.of(kept.getId()), LegacyDocNumbers.find("ABC-1", Tenant.DEFAULT_ID));
	}
}
