package com.logicaldoc.web;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.util.io.ResourceUtil;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.servlet.MockServletRequest;

/**
 * The PDF preview serves the file version the request names ({@code fileVersion},
 * as the version dialog and CE's Versions tab ask for it), not always the
 * document's current one.
 */
public class ConvertPdfTest extends AbstractWPTestCase {

	private Document doc;

	private byte[] first;

	private byte[] second;

	@Override
	protected List<String> getPluginArchives() {
		return List.of("/logicaldoc-core-plugin.jar");
	}

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			first = read("pdf1.pdf");
			second = read("pdf2.pdf");
			assertTrue(first.length != second.length || !java.util.Arrays.equals(first, second));

			Document docVO = new Document();
			docVO.setFileName("two.pdf");
			docVO.setFolder(com.logicaldoc.core.folder.FolderDAO.get().findById(Folder.DEFAULTWORKSPACEID));
			docVO.setTenantId(Tenant.DEFAULT_ID);
			docVO.setLanguage("en");
			DocumentHistory create = new DocumentHistory();
			create.setSession(session);
			doc = DocumentManager.get().create(ResourceUtil.getInputStream("pdf1.pdf"), docVO, create).getDocument();

			DocumentHistory checkin = new DocumentHistory();
			checkin.setSession(session);
			doc = DocumentManager.get()
					.checkin(doc.getId(), ResourceUtil.getInputStream("pdf2.pdf"), "two.pdf", false, null, checkin)
					.getDocument();
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	private static byte[] read(String resource) throws IOException {
		try (InputStream in = ResourceUtil.getInputStream(resource)) {
			return in.readAllBytes();
		}
	}

	private MemoryResponse preview(String... parameters) {
		MockServletRequest request = new MockServletRequest(servletSession);
		request.setParameter("docId", Long.toString(doc.getId()));
		request.setParameter("control", "preview");
		for (int i = 0; i < parameters.length; i += 2)
			request.setParameter(parameters[i], parameters[i + 1]);
		MemoryResponse response = new MemoryResponse();
		new ConvertPdf().doGet(request, response);
		return response;
	}

	@Test
	public void testFileVersion() throws Exception {
		String older = "1.0";
		String current = doc.getFileVersion();
		assertNotEquals(older, current);

		// The current file by default
		assertArrayEquals(second, preview().bytes());
		// The older one when asked for, as the version dialog does
		assertArrayEquals(first, preview("fileVersion", older).bytes());
		assertArrayEquals(second, preview("fileVersion", current).bytes());

		// A file version that is not this document's is refused: the "not available" PDF
		byte[] refused = preview("fileVersion", "9.9").bytes();
		assertTrue(refused.length > 0);
		assertNotEquals(first.length, refused.length);
		assertNotEquals(second.length, refused.length);
		assertEquals(preview("fileVersion", "8.8").bytes().length, refused.length);
	}
}
