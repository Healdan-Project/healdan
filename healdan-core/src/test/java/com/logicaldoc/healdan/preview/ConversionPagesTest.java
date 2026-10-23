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
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
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
	public void testReplacedFileTakesItsOwnCount() throws Exception {
		Document deck = fx.upload(folder, "b.pptx", OfficeFiles.pptx(3, 0), fx.admin());
		Document word = fx.upload(folder, "a.docx", OfficeFiles.docx(12), fx.admin());
		awaitAsyncWork();
		assertEquals(2, worker.runOnce(T));

		DocumentManager.get().replaceFile(deck.getId(), "1.0", new ByteArrayInputStream(OfficeFiles.pptx(5, 1)),
				fx.documentHistory(fx.admin()));
		DocumentManager.get().replaceFile(word.getId(), "1.0", new ByteArrayInputStream(OfficeFiles.docx(30)),
				fx.documentHistory(fx.admin()));
		awaitAsyncWork();
		// CE doesn't count a replaced file
		assertEquals(3, pagesOf(deck));

		assertEquals(2, worker.runOnce(T));

		assertEquals(5, pagesOf(deck));
		assertEquals(List.of("1.0 5"), versionPages(deck));
		assertEquals(30, pagesOf(word));
		assertEquals(List.of("1.0 30"), versionPages(word));
	}

	@Test
	public void testIndexingKeepsTheFilledCount() throws Exception {
		Document a = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());
		awaitAsyncWork();
		// The indexer loads the document before it parses it, and the worker fills the count meanwhile
		Document indexed = DocumentDAO.get().findById(a.getId(), true);
		assertEquals(1, worker.runOnce(T));
		assertEquals(5, pagesOf(a));

		DocumentHistory transaction = fx.documentHistory(fx.admin());
		transaction.setEvent(DocumentEvent.INDEXED);
		DocumentDAO.get().store(indexed, transaction);
		assertEquals(5, pagesOf(a));

		// Other saves still write the count they're given
		Document changed = DocumentDAO.get().findById(a.getId(), true);
		changed.setPages(9);
		DocumentDAO.get().store(changed);
		assertEquals(9, pagesOf(a));
	}

	@Test
	public void testFillWaitsForTheVersionSave() throws Exception {
		Document a = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());
		awaitAsyncWork();
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		long size = jdbc.queryForObject("select ld_filesize from ld_version where ld_documentid = ?", Long.class,
				a.getId());

		// As if CE's background save of the version hadn't landed: it does a second after the conversion, with
		// the count it took at upload
		jdbc.update("update ld_version set ld_filesize = ? where ld_documentid = ?", size + 1, a.getId());
		Thread save = new Thread(() -> {
			try {
				for (int i = 0; i < 1000 && !Store.get().exists(pdfOf(a, "1.0")); i++)
					Thread.sleep(20L);
				Thread.sleep(1000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			jdbc.update("update ld_version set ld_filesize = ?, ld_pages = 1 where ld_documentid = ?", size,
					a.getId());
		});
		save.start();
		assertEquals(1, worker.runOnce(T));
		save.join();
		assertEquals(5, pagesOf(a));
		assertEquals(List.of("1.0 5"), versionPages(a));

		// A save that never lands doesn't hold the count back for good
		Document b = fx.upload(folder, "b.docx", OfficeFiles.docx(null), fx.admin());
		awaitAsyncWork();
		jdbc.update("update ld_version set ld_filesize = ld_filesize + 1 where ld_documentid = ?", b.getId());
		ConversionPages.versionSaveWait = Duration.ofMillis(300);
		try {
			assertEquals(1, worker.runOnce(T));
		} finally {
			ConversionPages.versionSaveWait = Duration.ofSeconds(10);
		}
		assertEquals(5, pagesOf(b));
		assertEquals(List.of("1.0 5"), versionPages(b));
	}

	@Test
	public void testRecountGivesTheFilledCount() throws Exception {
		Document a = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());
		Document one = fx.upload(folder, "one.docx", OfficeFiles.docx(1), fx.admin());
		awaitAsyncWork();
		// No preview yet
		assertEquals(1, DocumentManager.get().countPages(DocumentDAO.get().findById(a.getId())));

		assertEquals(2, worker.runOnce(T));

		assertEquals(5, DocumentManager.get().countPages(DocumentDAO.get().findById(a.getId())));
		// Word's saved count of 1 beats the preview's 5
		assertEquals(1, DocumentManager.get().countPages(DocumentDAO.get().findById(one.getId())));
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
