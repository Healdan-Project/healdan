package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

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
import com.logicaldoc.healdan.support.LogCapture;
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
	public void testConvertsInTheBackground() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		File log = new File("target/fake-soffice/worker-background.log");
		Files.deleteIfExists(log.toPath());
		Files.createFile(log.toPath());
		Context.get().getConfig().setProperty(PATH, FakeSoffice.slow(2, log).getPath());
		File held = Files.createTempFile(new File("target").toPath(), "held", ".docx").toFile();
		Files.writeString(held.toPath(), "x");
		LibreOfficeRunner runner = LibreOfficeRunner.get();
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			// Another background conversion holds the single background permit
			Future<?> other = pool.submit(() -> runner.inBackground((Callable<Void>) () -> {
				runner.toPdf(held, OfficeFormats.Kind.TEXT, new File(held.getPath() + ".pdf"));
				return null;
			}));
			long deadline = System.currentTimeMillis() + 10000;
			while (Files.readAllLines(log.toPath()).isEmpty() && System.currentTimeMillis() < deadline)
				Thread.sleep(50);

			assertEquals(1, worker.runOnce(T));
			other.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		// The worker's conversion waited for the permit, though a slot was free
		List<String> kinds = Files.readAllLines(log.toPath()).stream().map(l -> l.split(" ")[0]).toList();
		assertEquals(List.of("start", "end", "start", "end"), kinds);
		assertTrue(Store.get().exists(pdfOf(a, "1.0")));
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
	public void testTimedOutJobStillRetried() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		PreviewJobs.get().failed(a.getId(), "1.0", "LibreOffice timed out after 120 s converting a.docx",
				Instant.now());

		assertEquals(1, worker.runOnce(T));

		assertTrue(Store.get().exists(pdfOf(a, "1.0")));
		assertFalse(queued(a, "1.0"));
	}

	@Test
	public void testUnavailableLibreOfficePostpones() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		Document b = fx.upload(folder, "b.docx", "b", fx.admin());
		Context.get().getConfig().setProperty(PATH, "/nonexistent/soffice");

		try (LogCapture capture = LogCapture.of(PreviewWorker.class)) {
			assertEquals(2, worker.runOnce(T));
			// Once a round, not once a job
			assertEquals(1, capture.lines().stream()
					.filter(l -> l.startsWith("WARN LibreOffice isn't available, Office previews are paused")).count());
		}

		assertEquals(0, PreviewJobs.get().find(b.getId(), "1.0").orElseThrow().attempts());
		PreviewJob job = PreviewJobs.get().find(a.getId(), "1.0").orElseThrow();
		assertEquals(0, job.attempts());
		java.sql.Timestamp next = new org.springframework.jdbc.core.JdbcTemplate(
				Context.get(javax.sql.DataSource.class)).queryForObject(
						"select hd_nexttry from hd_preview_job where hd_docid = ?", java.sql.Timestamp.class, a.getId());
		assertTrue(next.toInstant() + " vs " + T, !next.toInstant().isBefore(T.plus(java.time.Duration.ofMinutes(10))));
		assertTrue(next.toInstant() + " vs " + T, next.toInstant().isBefore(T.plus(java.time.Duration.ofMinutes(11))));
		assertEquals(0, worker.runOnce(T.plus(java.time.Duration.ofMinutes(9))));

		// Once LibreOffice is there, a later round converts it
		Context.get().getConfig().setProperty(PATH, FakeSoffice.ok().getPath());
		assertEquals(2, worker.runOnce(T.plus(java.time.Duration.ofMinutes(11))));
		assertTrue(Store.get().exists(pdfOf(a, "1.0")));
		assertFalse(queued(a, "1.0"));
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
	public void testGivingUpIsLogged() {
		PreviewJobs jobs = PreviewJobs.get();
		jobs.add(999999, "1.0", T);
		for (int i = 1; i < PreviewJobs.MAX_ATTEMPTS; i++)
			jobs.failed(999999, "1.0", "document not found", T);

		try (LogCapture capture = LogCapture.of(PreviewWorker.class)) {
			assertEquals(1, worker.runOnce(T.plus(java.time.Duration.ofHours(2))));

			List<String> warnings = capture.lines().stream().filter(l -> l.startsWith("WARN Giving up")).toList();
			assertEquals(1, warnings.size());
			assertTrue(warnings.get(0), warnings.get(0).contains("999999"));
			assertTrue(warnings.get(0), warnings.get(0).contains("document not found"));
		}
		assertEquals(PreviewJobs.MAX_ATTEMPTS, jobs.find(999999, "1.0").orElseThrow().attempts());
	}

	@Test
	public void testFailureToRecordAFailureMovesOn() {
		PreviewJobs.get().add(999998, "1.0", T);
		PreviewJobs.get().add(999999, "1.0", T);
		Set<Long> tried = new HashSet<>();
		PreviewJobs broken = new PreviewJobs(Context.get(DataSource.class)) {
			@Override
			public void failed(long docId, String fileVersion, String error, Instant now) {
				tried.add(docId);
				throw new IllegalStateException("cannot write");
			}
		};
		PreviewWorker worker = new PreviewWorker() {
			@Override
			PreviewJobs jobs() {
				return broken;
			}
		};

		assertEquals(2, worker.runOnce(T));

		assertEquals(Set.of(999998L, 999999L), tried);
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
