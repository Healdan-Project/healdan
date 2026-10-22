package com.logicaldoc.web;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.logicaldoc.core.conversion.FormatConversionManager;
import com.logicaldoc.core.conversion.FormatConverter;
import com.logicaldoc.core.document.AbstractDocument;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
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

	private static final byte[] STUB_PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n"
			.getBytes(StandardCharsets.US_ASCII);

	private final StubConverter stub = new StubConverter();

	@Override
	protected List<String> getPluginArchives() {
		return List.of("/logicaldoc-core-plugin.jar");
	}

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		FormatConversionManager.get().getConverters().put("abc-pdf", List.of(stub));
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

	@Override
	public void tearDown() throws IOException {
		FormatConversionManager.get().getConverters().remove("abc-pdf");
		super.tearDown();
	}

	/**
	 * A stand-in for Healdan's converter, which these tests don't load
	 */
	private static class StubConverter implements FormatConverter {

		boolean failing;

		@Override
		public void convert(File src, File dest) throws IOException {
			if (failing)
				throw new IOException("The stub fails on demand");
			Files.write(dest.toPath(), STUB_PDF);
		}

		@Override
		public void convert(String sid, AbstractDocument document, File src, File dest) throws IOException {
			convert(src, dest);
		}

		@Override
		public List<String> getParameterNames() {
			return List.of();
		}

		@Override
		public Map<String, String> getParameters() {
			return Map.of();
		}

		@Override
		public String getParameter(String name) {
			return null;
		}

		@Override
		public void loadParameters() {
			// Nothing to load
		}

		@Override
		public boolean isEnabled() {
			return true;
		}

		@Override
		public void setEnabled(boolean enabled) {
			// Always enabled
		}
	}

	private Document checkinAbc() throws Exception {
		DocumentHistory checkin = new DocumentHistory();
		checkin.setSession(session);
		doc = DocumentManager.get().checkin(doc.getId(),
				new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), "three.abc", false, null, checkin)
				.getDocument();
		assertEquals("three.abc", doc.getFileName());
		return doc;
	}

	private static StoreResource conversion(long docId, String fileVersion) {
		return StoreResource.builder().docId(docId).fileVersion(fileVersion).suffixPdfConversion().build();
	}

	/**
	 * Removes a conversion an earlier run may have left under the same ids
	 */
	private static void forgetConversion(long docId, String fileVersion) {
		Store.get().delete(conversion(docId, fileVersion));
		assertTrue(!Store.get().exists(conversion(docId, fileVersion)));
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

	@Test
	public void testFileTypeIsTheVersions() throws Exception {
		// The current file is now text, but version 1.0 is still a PDF
		DocumentHistory checkin = new DocumentHistory();
		checkin.setSession(session);
		doc = DocumentManager.get().checkin(doc.getId(),
				new ByteArrayInputStream("not a PDF".getBytes(StandardCharsets.UTF_8)), "two.txt", false, null,
				checkin).getDocument();
		assertEquals("two.txt", doc.getFileName());

		assertArrayEquals(first, preview("fileVersion", "1.0").bytes());

		// The current, text file is refused: the "not available" PDF
		byte[] refused = preview("fileVersion", "9.9").bytes();
		assertEquals(refused.length, preview().bytes().length);
		assertEquals(refused.length, preview("fileVersion", doc.getFileVersion()).bytes().length);
	}

	@Test
	public void testConvertibleVersionServesItsConversion() throws Exception {
		checkinAbc();
		String fileVersion = doc.getFileVersion();
		forgetConversion(doc.getId(), fileVersion);

		assertArrayEquals(STUB_PDF, preview("fileVersion", fileVersion).bytes());
		assertTrue(Store.get().exists(conversion(doc.getId(), fileVersion)));
	}

	@Test
	public void testConversionFailureServesPlaceholderAndCachesNothing() throws Exception {
		checkinAbc();
		String fileVersion = doc.getFileVersion();
		forgetConversion(doc.getId(), fileVersion);
		stub.failing = true;

		byte[] refused = preview("fileVersion", "9.9").bytes();
		byte[] failed = preview("fileVersion", fileVersion).bytes();
		assertEquals(refused.length, failed.length);
		assertTrue(failed.length != STUB_PDF.length);
		assertTrue(!Store.get().exists(conversion(doc.getId(), fileVersion)));
	}

	@Test
	public void testAliasServesTargetsConversion() throws Exception {
		checkinAbc();
		String fileVersion = doc.getFileVersion();
		long targetId = doc.getId();
		forgetConversion(targetId, fileVersion);

		DocumentHistory transaction = new DocumentHistory();
		transaction.setSession(session);
		Document alias = DocumentManager.get().createAlias(DocumentDAO.get().findById(targetId, true),
				com.logicaldoc.core.folder.FolderDAO.get().findById(Folder.DEFAULTWORKSPACEID), null, transaction);
		assertTrue(alias.getId() != targetId);

		MockServletRequest request = new MockServletRequest(servletSession);
		request.setParameter("docId", Long.toString(alias.getId()));
		request.setParameter("control", "preview");
		request.setParameter("fileVersion", fileVersion);
		MemoryResponse response = new MemoryResponse();
		new ConvertPdf().doGet(request, response);

		assertArrayEquals(STUB_PDF, response.bytes());
		assertTrue(Store.get().exists(conversion(targetId, fileVersion)));
		assertTrue(!Store.get().exists(conversion(alias.getId(), fileVersion)));
	}
}
