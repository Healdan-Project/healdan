package com.logicaldoc.web.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.MemoryResponse;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Healdan (spec 8.2): CE's Bookmarks section lists no bookmark of a deleted
 * folder, as it lists none of a deleted document. {@code PersistentObject}'s
 * {@code @SQLRestriction("ld_deleted=0")} already keeps deleted folders out of
 * the servlet's join; its folder query also says {@code B.deleted = 0}, as its
 * document query does, so the listing doesn't depend on that annotation.
 */
public class BookmarksDataServletTest extends AbstractWPTestCase {

	@Test
	public void testDeletedFolderHidden() throws Exception {
		// The fixture's folder 6 is live and folder 8 deleted; document 1 is live
		assertNull(FolderDAO.get().findById(8L));
		bookmark(Bookmark.Type.FOLDER, 6L);
		bookmark(Bookmark.Type.FOLDER, 8L);
		bookmark(Bookmark.Type.DOCUMENT, 1L);

		MemoryResponse response = new MemoryResponse();
		// HttpServlet's public entry, which calls the data servlet's own service method
		new BookmarksDataServlet().service((ServletRequest) new MockServletRequest(servletSession),
				(ServletResponse) response);

		assertNull(response.error());
		String xml = response.text();
		assertTrue(xml, xml.contains("<targetId>6</targetId>"));
		assertTrue(xml, xml.contains("<targetId>1</targetId>"));
		assertFalse(xml, xml.contains("<targetId>8</targetId>"));
	}

	private void bookmark(Bookmark.Type type, long targetId) throws PersistenceException {
		Bookmark bookmark = new Bookmark();
		bookmark.setTenantId(Tenant.DEFAULT_ID);
		bookmark.setUserId(session.getUserId());
		bookmark.setType(type);
		bookmark.setTargetId(targetId);
		bookmark.setTitle("Bookmark " + targetId);
		BookmarkDAO.get().store(bookmark);
	}
}
