package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

public class PreviewWorkerTest extends AbstractHealdanTestCase {

	private static final String PATH = "converter.OfficeConverter.path";

	private static final String TIMEOUT = "converter.OfficeConverter.timeout";

	private static final String MAX_SIZE = "default.gui.preview.maxfilesize";

	/** After the jobs the uploads queue, which are due at the real time */
	private static final Instant T = Instant.now().plus(java.time.Duration.ofDays(1));

	private HealdanFixtures fx;

	private Folder folder;

	private final PreviewWorker worker = new PreviewWorker();

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		PreviewTests.clearStore();
		Context.get().getConfig().setProperty(TIMEOUT, "30");
		try {
			Context.get().getConfig().setProperty(PATH, FakeSoffice.ok().getPath());
			fx = new HealdanFixtures();
			Matter matter = fx.matter(fx.client(997, "Healdan"), 1, "Previews", fx.store("Main"), "Documents");
			folder = FolderDAO.get()
					.findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents", Tenant.DEFAULT_ID, true)
					.get(0);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static StoreResource pdfOf(Document doc, String fileVersion) throws Exception {
		return StoreResource.builder().document(doc).fileVersion(fileVersion).suffixPdfConversion().build();
	}

	private static boolean queued(Document doc, String version) {
		return PreviewJobs.get().find(doc.getId(), version).isPresent();
	}

	@Test
	public void testConvertsAndFinishes() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		assertTrue(queued(a, "1.0"));

		assertEquals(1, worker.runOnce(T));

		assertTrue(Store.get().exists(pdfOf(a, "1.0")));
		assertFalse(queued(a, "1.0"));
	}

	@Test
	public void testOlderVersionOfAnotherTypeConverts() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		DocumentManager.get().checkout(a.getId(), fx.documentHistory(fx.admin()));
		Document current = DocumentManager.get()
				.checkin(a.getId(), new ByteArrayInputStream("%PDF-1.4".getBytes(StandardCharsets.US_ASCII)), "a.pdf",
						false, null, fx.documentHistory(fx.admin()))
				.getDocument();
		assertEquals("a.pdf", current.getFileName());
		assertTrue(queued(a, "1.0"));

		assertEquals(1, worker.runOnce(T));

		assertFalse(queued(a, "1.0"));
		byte[] converted = Store.get().getBytes(pdfOf(a, "1.0"));
		assertEquals("%PDF", new String(converted, 0, 4, StandardCharsets.US_ASCII));
	}

	@Test
	public void testDeletedDocumentDropped() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		DocumentDAO.get().delete(a.getId());

		assertEquals(1, worker.runOnce(T));

		assertFalse(queued(a, "1.0"));
		assertFalse(Store.get().exists(pdfOf(a, "1.0")));
	}

	@Test
	public void testOversizeDropped() throws Exception {
		Context.get().getConfig().setProperty(MAX_SIZE, "1");
		try {
			Document big = fx.upload(folder, "big.docx", "x".repeat(1_100_000), fx.admin());

			assertEquals(1, worker.runOnce(T));

			assertFalse(queued(big, "1.0"));
			assertFalse(Store.get().exists(pdfOf(big, "1.0")));
		} finally {
			Context.get().getConfig().setProperty(MAX_SIZE, "0");
		}
	}

	@Test
	public void testPlaceholdersCleared() throws Exception {
		byte[] placeholder;
		try (InputStream in = getClass().getResourceAsStream("/pdf/notavailable.pdf")) {
			placeholder = in.readAllBytes();
		}
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		DocumentManager.get().checkout(a.getId(), fx.documentHistory(fx.admin()));
		DocumentManager.get().checkin(a.getId(), new ByteArrayInputStream("v2".getBytes(StandardCharsets.UTF_8)),
				"a.docx", false, null, fx.documentHistory(fx.admin()));
		PreviewJobs.get().done(a.getId(), "1.0");
		assertTrue(queued(a, "1.1"));
		for (String v : new String[] { "1.0", "1.1" })
			Store.get().store(new ByteArrayInputStream(placeholder), pdfOf(a, v));

		assertEquals(1, worker.runOnce(T));

		byte[] converted = Store.get().getBytes(pdfOf(a, "1.1"));
		assertEquals("%PDF", new String(converted, 0, 4, StandardCharsets.US_ASCII));
		assertFalse(PreviewWorker.isPlaceholder(converted));
		assertTrue(PreviewWorker.isPlaceholder(placeholder));
		assertFalse(Store.get().exists(pdfOf(a, "1.0")));
		assertFalse(queued(a, "1.1"));
	}

	@Test
	public void testFailureRetriesAndMovesOn() throws Exception {
		Context.get().getConfig().setProperty(PATH, FakeSoffice.hangIfContains("HANGME").getPath());
		Context.get().getConfig().setProperty(TIMEOUT, "2");
		Document a = fx.upload(folder, "a.docx", "HANGME", fx.admin());
		Document b = fx.upload(folder, "b.docx", "fine", fx.admin());

		assertEquals(2, worker.runOnce(Instant.now().plusSeconds(1)));

		PreviewJob job = PreviewJobs.get().find(a.getId(), "1.0").orElseThrow();
		assertEquals(1, job.attempts());
		assertTrue(job.lastError(), job.lastError().contains("timed out"));
		assertTrue(Store.get().exists(pdfOf(b, "1.0")));
		assertFalse(queued(b, "1.0"));
	}

	@Test
	public void testMissingDocumentRetried() {
		PreviewJobs.get().add(999999, "1.0", T);

		assertEquals(1, worker.runOnce(T));

		PreviewJob job = PreviewJobs.get().find(999999, "1.0").orElseThrow();
		assertEquals(1, job.attempts());
		assertEquals("document not found", job.lastError());
	}

	@Test
	public void testJobsSurviveRestart() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());

		PreviewWorker restarted = new PreviewWorker();
		assertEquals(1, restarted.runOnce(Instant.now().plusSeconds(1)));

		assertTrue(Store.get().exists(pdfOf(a, "1.0")));
	}

	@Test
	public void testErrorFailsTheJobAndTheQueueMovesOn() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		Document b = fx.upload(folder, "b.docx", "b", fx.admin());
		PreviewWorker erroring = new PreviewWorker() {
			@Override
			void convert(Document doc, String fileVersion) throws Exception {
				if (doc.getId() == a.getId())
					throw new LinkageError("broken");
				super.convert(doc, fileVersion);
			}
		};

		assertEquals(2, erroring.runOnce(T));

		PreviewJob job = PreviewJobs.get().find(a.getId(), "1.0").orElseThrow();
		assertEquals(1, job.attempts());
		assertEquals("broken", job.lastError());
		assertFalse(queued(b, "1.0"));
		assertTrue(Store.get().exists(pdfOf(b, "1.0")));
	}

	@Test
	public void testRetryDelayCountsFromTheFailure() throws Exception {
		Context.get().getConfig().setProperty(PATH, FakeSoffice.hang().getPath());
		Context.get().getConfig().setProperty(TIMEOUT, "2");
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());

		assertEquals(1, worker.runOnce(T));

		java.sql.Timestamp next = new org.springframework.jdbc.core.JdbcTemplate(
				Context.get(javax.sql.DataSource.class)).queryForObject(
						"select hd_nexttry from hd_preview_job where hd_docid = ?", java.sql.Timestamp.class, a.getId());
		// The conversion took the 2 s timeout, so the retry is more than a minute after the round's time
		assertTrue(next.toInstant() + " vs " + T, next.toInstant().isAfter(T.plusSeconds(61)));
	}
}
