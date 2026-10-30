package com.logicaldoc.web.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.MemoryResponse;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Healdan: Reports › Locked documents lists the checked-out and locked
 * documents, with who holds each.
 * <p>
 * Upstream's query had no comma between {@code A.ld_lockuserid} and
 * {@code B.ld_firstname}, so the database refused it and the report listed
 * nothing. It also checked no permission, and a user without the report's menu
 * now sees only what they hold, as in the trash reports.
 */
public class LockedDocsDataServletTest extends AbstractWPTestCase {

	@Test
	public void testListsCheckedOutAndLocked() throws Exception {
		holdTwo();
		String xml = report(null);

		String checkedOut = document(xml, 1);
		assertTrue(checkedOut, checkedOut.contains("<status>1</status>"));
		assertTrue(checkedOut, checkedOut.contains("<userId>2</userId>"));
		assertTrue(checkedOut, checkedOut.contains("<username>Marco Meschieri</username>"));

		String locked = document(xml, 4);
		assertTrue(locked, locked.contains("<status>2</status>"));
		assertTrue(locked, locked.contains("<userId>3</userId>"));
		assertTrue(locked, locked.contains("<username>Stein Sebastian</username>"));

		// Unlocked
		assertFalse(xml, xml.contains("<id>3</id>"));
	}

	@Test
	public void testListsOneHoldersDocuments() throws Exception {
		holdTwo();
		String xml = report(3L);

		assertTrue(xml, xml.contains("<document><id>4</id>"));
		assertFalse(xml, xml.contains("<id>1</id>"));
	}

	@Test
	public void testListsOnlyTheirOwnWithoutTheMenu() throws Exception {
		holdTwo();
		// Boss has neither the report's menu nor the admins' group
		prepareSession("boss", "admin");

		String xml = report(null);
		assertTrue(xml, xml.contains("<document><id>1</id>"));
		assertFalse(xml, xml.contains("<id>4</id>"));

		MemoryResponse others = respond(3L);
		assertEquals(500, others.getStatus());
		assertEquals("User boss can only list the documents they hold", others.error());
		assertFalse(others.text(), others.text().contains("<id>4</id>"));
	}

	/**
	 * The fixture's documents are all unlocked: boss checks out 1 and sebastian
	 * locks 4.
	 */
	private void holdTwo() throws PersistenceException {
		DocumentDAO dao = DocumentDAO.get();
		dao.jdbcUpdate("update ld_document set ld_status=%d, ld_lockuserid=2 where ld_id=1"
				.formatted(DocumentStatus.CHECKEDOUT.ordinal()));
		dao.jdbcUpdate("update ld_document set ld_status=%d, ld_lockuserid=3 where ld_id=4"
				.formatted(DocumentStatus.LOCKED.ordinal()));
	}

	private String report(Long userId) throws Exception {
		MemoryResponse response = respond(userId);
		assertNull(response.error());
		return response.text();
	}

	private MemoryResponse respond(Long userId) throws Exception {
		MockServletRequest request = new MockServletRequest(servletSession);
		if (userId != null)
			request.setParameter("userId", userId.toString());

		MemoryResponse response = new MemoryResponse();
		// HttpServlet's public entry, which calls the data servlet's own service method
		new LockedDocsDataServlet().service((ServletRequest) request, (ServletResponse) response);
		return response;
	}

	/**
	 * @return the report's entry for the document, failing if it has none
	 */
	private static String document(String xml, long docId) {
		int start = xml.indexOf("<document><id>%d</id>".formatted(docId));
		assertTrue(xml, start >= 0);
		return xml.substring(start, xml.indexOf("</document>", start));
	}
}
