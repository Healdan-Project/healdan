package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.OfficeFiles;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

public class ConversionPagesTest extends AbstractHealdanTestCase {

	private static final String PATH = "converter.OfficeConverter.path";

	private static final String TIMEOUT = "converter.OfficeConverter.timeout";

	/** After the jobs the uploads queue, which are due at the real time */
	private static final Instant T = Instant.now().plus(Duration.ofDays(1));

	private HealdanFixtures fx;

	private Folder folder;

	private final PreviewWorker worker = new PreviewWorker();

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		PreviewTests.clearStore();
		Context.get().getConfig().setProperty(TIMEOUT, "30");
		try {
			convertsTo(5);
			fx = new HealdanFixtures();
			Matter matter = fx.matter(fx.client(994, "Healdan"), 1, "Pages", fx.store("Main"), "Documents");
			folder = FolderDAO.get()
					.findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents", Tenant.DEFAULT_ID, true)
					.get(0);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Makes the fake LibreOffice convert every file to a PDF of this many pages
	 */
	private static void convertsTo(int pages) throws IOException {
		File pdf = new File("target/fake-soffice/pages-" + pages + ".pdf");
		Files.createDirectories(pdf.getParentFile().toPath());
		Files.write(pdf.toPath(), OfficeFiles.pdf(pages));
		Context.get().getConfig().setProperty(PATH, FakeSoffice.copying(pdf).getPath());
	}

	private static StoreResource pdfOf(Document doc, String fileVersion) {
		return StoreResource.builder().docId(doc.getId()).fileVersion(fileVersion).suffixPdfConversion().build();
	}

	private static int pagesOf(Document doc) throws Exception {
		return DocumentDAO.get().findById(doc.getId()).getPages();
	}

	/**
	 * @return "fileVersion pages" for each of the document's file versions
	 */
	private static List<String> versionPages(Document doc) {
		return new JdbcTemplate(Context.get(DataSource.class)).queryForList(
				"select distinct ld_fileversion || ' ' || cast(ld_pages as varchar(10)) from ld_version "
						+ "where ld_documentid = ? order by 1",
				String.class, doc.getId());
	}

	private static void awaitAsyncWork() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 20000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				fail("Background version saves didn't finish");
			Thread.sleep(50L);
		}
	}

	@Test
	public void testWordWithoutSavedCountTakesThePdfPages() throws Exception {
		Document a = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());
		// A file Word can't read but LibreOffice converts, such as an RTF named .docx
		Document b = fx.upload(folder, "b.docx", "{\\rtf1 not a zip}", fx.admin());
		awaitAsyncWork();
		assertEquals(1, pagesOf(a));

		assertEquals(2, worker.runOnce(T));

		assertEquals(5, pagesOf(a));
		assertEquals(List.of("1.0 5"), versionPages(a));
		assertEquals(5, pagesOf(b));
	}

	@Test
	public void testSavedCountsWin() throws Exception {
		Document word = fx.upload(folder, "a.docx", OfficeFiles.docx(12), fx.admin());
		Document deck = fx.upload(folder, "b.pptx", OfficeFiles.pptx(3, 0), fx.admin());
		Document book = fx.upload(folder, "c.xlsx", OfficeFiles.xlsx(2, 0, 0), fx.admin());
		awaitAsyncWork();

		assertEquals(3, worker.runOnce(T));

		assertEquals(12, pagesOf(word));
		assertEquals(List.of("1.0 12"), versionPages(word));
		assertEquals(3, pagesOf(deck));
		assertEquals(2, pagesOf(book));
	}

	@Test
	public void testEachVersionTakesItsOwnPdf() throws Exception {
		Document a = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());
		DocumentManager.get().checkout(a.getId(), fx.documentHistory(fx.admin()));
		DocumentManager.get().checkin(a.getId(), new ByteArrayInputStream(OfficeFiles.docx(null)), "a.docx", false,
				null, fx.documentHistory(fx.admin()));
		awaitAsyncWork();
		String current = DocumentDAO.get().findById(a.getId()).getFileVersion();

		// The old version converts last, and must leave the document's count alone
		convertsTo(5);
		worker.process(PreviewJobs.get().find(a.getId(), current).orElseThrow(), T);
		convertsTo(7);
		worker.process(PreviewJobs.get().find(a.getId(), "1.0").orElseThrow(), T);

		assertEquals(5, pagesOf(a));
		assertEquals(List.of("1.0 7", current + " 5"), versionPages(a));
	}

	@Test
	public void testCountsOnlyARealConversion() throws Exception {
		Document a = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());
		assertEquals(0, ConversionPages.ofConversion(a.getId(), "1.0"));

		byte[] placeholder;
		try (InputStream in = getClass().getResourceAsStream("/pdf/notavailable.pdf")) {
			placeholder = in.readAllBytes();
		}
		Store.get().store(new ByteArrayInputStream(placeholder), pdfOf(a, "1.0"));
		assertEquals(0, ConversionPages.ofConversion(a.getId(), "1.0"));

		Store.get().store(new ByteArrayInputStream(OfficeFiles.pdf(4)), pdfOf(a, "1.0"));
		assertEquals(4, ConversionPages.ofConversion(a.getId(), "1.0"));
	}
}
