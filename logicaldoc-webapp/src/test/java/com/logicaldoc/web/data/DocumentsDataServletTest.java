package com.logicaldoc.web.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.util.servlet.MockServletResponse;

public class DocumentsDataServletTest extends AbstractDataServletTestCase {

	// Instance under test
	private DocumentsDataServlet testSubject = new DocumentsDataServlet();

	@Test
	public void testService() throws IOException {
		MockServletRequest mockRequest = new MockServletRequest(servletSession);
		mockRequest.setParameter("page", "1");
		mockRequest.setParameter("folderId", "5");
		mockRequest.setParameter("extattr", "source,coverage");
		mockRequest.setParameter("sort", "date desc");

		MockServletResponse response = new MockServletResponse(responseFile);
		testSubject.service(mockRequest, response);
		response.flushBuffer();

		assertTrue(responseFile.exists());

		String output = response.getOutputString();
		assertTrue(output.contains("test.zip"));
		assertTrue(output.contains("<id>1</id>"));
		assertTrue(output.contains("<id>4</id>"));

		mockRequest = new MockServletRequest(servletSession);
		mockRequest.setParameter("page", "1");
		mockRequest.setParameter("folderId", "5");
		mockRequest.setParameter("extattr", "source,coverage");
		mockRequest.setParameter("sort", "date desc");
		mockRequest.setParameter("docIds", "1,4");
		mockRequest.setParameter("hiliteDocId", "4m");

		response.clear();
		response = new MockServletResponse(responseFile);
		testSubject.service(mockRequest, response);
		response.flushBuffer();

		assertTrue(responseFile.exists());

		output = response.getOutputString();
		assertTrue(output.contains("<id>1</id>"));
		assertTrue(output.contains("<id>4</id>"));

		mockRequest = new MockServletRequest(servletSession);
		mockRequest.setParameter("page", "1");
		mockRequest.setParameter("folderId", "5");
		mockRequest.setParameter("extattr", "source,coverage");
		mockRequest.setParameter("sort", "date desc");
		mockRequest.setParameter("status", "0");

		response.clear();
		response = new MockServletResponse(responseFile);
		testSubject.service(mockRequest, response);
		response.flushBuffer();

		assertTrue(responseFile.exists());

		output = response.getOutputString();
		assertEquals("<list></list>", output);
	}

	@Test
	public void testServiceWithLastNote() throws Exception {
		DocumentDAO dao = DocumentDAO.get();
		Document doc = dao.findById(1L, true);
		assertNotNull(doc);

		String original = doc.getLastNote();

		doc.setLastNote("note-one");
		dao.store(doc);

		doc.setLastNote("note-two");
		dao.store(doc);
		
		doc.setLastNote("note-three");
		dao.store(doc);

		MockServletRequest mockRequest = new MockServletRequest(servletSession);
		mockRequest.setParameter("page", "1");
		mockRequest.setParameter("folderId", String.valueOf(doc.getFolder().getId()));
		mockRequest.setParameter("sort", "date desc");

		MockServletResponse response = new MockServletResponse(responseFile);
		testSubject.service(mockRequest, response);
		response.flushBuffer();

		String output = response.getOutputString();

		assertTrue(output.contains("note-three"));

		doc.setLastNote(original);
		dao.store(doc);
	}

	/**
	 * Healdan's search folders list their documents through docIds, and their
	 * grid shows extended attributes such as Author and Class
	 */
	@Test
	public void testDocIdsModePrintsExtendedAttributes() throws Exception {
		DocumentDAO dao = DocumentDAO.get();
		Document doc = dao.findById(1L, true);
		doc.setValue("source", "the source of one");
		dao.store(doc);

		MockServletRequest mockRequest = new MockServletRequest(servletSession);
		mockRequest.setParameter("page", "1");
		mockRequest.setParameter("extattr", "source,coverage");
		mockRequest.setParameter("docIds", "1,4");

		MockServletResponse response = new MockServletResponse(responseFile);
		testSubject.service(mockRequest, response);
		response.flushBuffer();

		String output = response.getOutputString();
		assertTrue(output, output.contains("<id>1</id>"));
		assertTrue(output, output.contains("<id>4</id>"));
		assertTrue(output, output.contains("<ext_source><![CDATA[the source of one]]></ext_source>"));
		assertFalse(output, output.contains("<ext_coverage>"));
	}

	/**
	 * A folder listing prints the extended attributes extattr names, now that
	 * its query binds the names
	 */
	@Test
	public void testFolderModePrintsExtendedAttributes() throws Exception {
		DocumentDAO dao = DocumentDAO.get();
		Document doc = dao.findById(1L, true);
		doc.setValue("source", "the source of one");
		dao.store(doc);

		MockServletRequest mockRequest = new MockServletRequest(servletSession);
		mockRequest.setParameter("page", "1");
		mockRequest.setParameter("folderId", String.valueOf(doc.getFolder().getId()));
		mockRequest.setParameter("extattr", "source,coverage");

		MockServletResponse response = new MockServletResponse(responseFile);
		testSubject.service(mockRequest, response);
		response.flushBuffer();

		String output = response.getOutputString();
		assertTrue(output, output.contains("<id>1</id>"));
		assertTrue(output, output.contains("<ext_source><![CDATA[the source of one]]></ext_source>"));
		assertFalse(output, output.contains("<ext_coverage>"));
	}

	/**
	 * Healdan's doc #s live in customId and sort as numbers, with any
	 * non-numeric custom id after them
	 */
	@Test
	public void testCustomIdSortsNumerically() throws Exception {
		// Not document 1: document 2 is a shortcut to it and lists with its custom id
		DocumentDAO dao = DocumentDAO.get();
		setCustomId(dao, 5L, "2");
		setCustomId(dao, 3L, "10");
		setCustomId(dao, 4L, "1");

		List<Long> ascending = listedIds("customId asc");
		assertEquals(ascending.toString(), List.of(4L, 5L, 3L), ascending.subList(0, 3));

		List<Long> descending = listedIds("customId desc");
		assertEquals(descending.toString(), List.of(3L, 5L, 4L),
				descending.subList(descending.size() - 3, descending.size()));
	}

	private void setCustomId(DocumentDAO dao, long docId, String customId) throws Exception {
		Document doc = dao.findById(docId, true);
		doc.setCustomId(customId);
		dao.store(doc);
	}

	private List<Long> listedIds(String sort) throws IOException {
		MockServletRequest mockRequest = new MockServletRequest(servletSession);
		mockRequest.setParameter("page", "1");
		mockRequest.setParameter("folderId", "5");
		mockRequest.setParameter("sort", sort);

		MockServletResponse response = new MockServletResponse(responseFile);
		testSubject.service(mockRequest, response);
		response.flushBuffer();

		List<Long> ids = new ArrayList<>();
		Matcher matcher = Pattern.compile("<id>(\\d+)</id>").matcher(response.getOutputString());
		while (matcher.find())
			ids.add(Long.parseLong(matcher.group(1)));
		return ids;
	}
}