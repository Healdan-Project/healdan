package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.thumbnail.ThumbnailManager;
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

public class OfficeThumbnailBuilderTest extends AbstractHealdanTestCase {

	/** Records the PDF bytes it is given instead of rendering them. */
	private static final class Recording extends OfficeThumbnailBuilder {

		final List<byte[]> pdfs = new ArrayList<>();

		@Override
		protected void renderFirstPage(String sid, Document document, String fileVersion, File pdf, File dest,
				int size, int quality) throws IOException {
			pdfs.add(Files.readAllBytes(pdf.toPath()));
			Files.write(dest.toPath(), new byte[] { 1 });
		}
	}

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		PreviewTests.clearStore();
	}

	/** Builds the thumbnail as the preview worker does */
	private static void inBackground(Recording builder, Document doc, String fileVersion, File dest) throws Exception {
		LibreOfficeRunner.get().inBackground((Callable<Void>) () -> {
			builder.buildThumbnail(null, doc, fileVersion, null, dest, 100, 80);
			return null;
		});
	}

	private Document upload(String name) throws Exception {
		HealdanFixtures fx = new HealdanFixtures();
		Matter matter = fx.matter(fx.client(997, "Healdan"), 1, "Office", fx.store("Main"), "Documents");
		Folder folder = FolderDAO.get()
				.findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents", Tenant.DEFAULT_ID, true).get(0);
		return fx.upload(folder, name, "content", fx.admin());
	}

	@Test
	public void testRegisteredForEveryFormat() {
		for (String ext : OfficeFormats.extensions())
			assertTrue(ext, ThumbnailManager.get().getBuilder("a." + ext) instanceof OfficeThumbnailBuilder);
		assertTrue(ThumbnailManager.get().getBuilder("A.DOCX") instanceof OfficeThumbnailBuilder);
	}

	@Test
	public void testUsesTheCachedConversion() throws Exception {
		File log = new File("target/fake-soffice/thumb-cache.log");
		Files.deleteIfExists(log.toPath());
		Files.createFile(log.toPath());
		Context.get().getConfig().setProperty("converter.OfficeConverter.path", FakeSoffice.slow(0, log).getPath());
		Document doc = upload("a.docx");
		Recording builder = new Recording();
		File dest = File.createTempFile("thumb", ".png");

		inBackground(builder, doc, null, dest);
		// A request from the GUI uses the conversion the worker made
		builder.buildThumbnail(null, doc, null, null, dest, 100, 80);

		long runs = Files.readAllLines(log.toPath()).stream().filter(l -> l.startsWith("start")).count();
		assertEquals(1, runs);
		byte[] stored;
		try (var in = Store.get().getStream(StoreResource.builder().document(doc).fileVersion(doc.getFileVersion())
				.suffixPdfConversion().build())) {
			stored = in.readAllBytes();
		}
		assertEquals(2, builder.pdfs.size());
		assertArrayEquals(stored, builder.pdfs.get(0));
		assertArrayEquals(stored, builder.pdfs.get(1));
		dest.delete();
	}

	private static Document checkin(Document doc, String name, byte[] content) throws Exception {
		HealdanFixtures fx = new HealdanFixtures();
		DocumentManager.get().checkout(doc.getId(), fx.documentHistory(fx.admin()));
		return DocumentManager.get().checkin(doc.getId(), new ByteArrayInputStream(content), name, false, null,
				fx.documentHistory(fx.admin())).getDocument();
	}

	@Test
	public void testOlderVersionOfAnotherTypeUsesItsConversion() throws Exception {
		Context.get().getConfig().setProperty("converter.OfficeConverter.path", FakeSoffice.ok().getPath());
		Document doc = upload("a.docx");
		doc = checkin(doc, "a.pdf", "%PDF-1.4 current".getBytes(StandardCharsets.US_ASCII));
		assertEquals("a.pdf", doc.getFileName());
		Recording builder = new Recording();
		File dest = File.createTempFile("thumb", ".png");

		inBackground(builder, doc, "1.0", dest);

		byte[] stored = Store.get().getBytes(
				StoreResource.builder().docId(doc.getId()).fileVersion("1.0").suffixPdfConversion().build());
		assertEquals(1, builder.pdfs.size());
		assertArrayEquals(stored, builder.pdfs.get(0));
		dest.delete();
	}

	@Test
	public void testOlderPdfVersionUsedAsItIs() throws Exception {
		Document doc = upload("a.pdf");
		byte[] older = Store.get().getBytes(StoreResource.builder().docId(doc.getId()).fileVersion("1.0").build());
		doc = checkin(doc, "a.docx", "docx".getBytes(StandardCharsets.US_ASCII));
		assertEquals("a.docx", doc.getFileName());
		Context.get().getConfig().setProperty("converter.OfficeConverter.path", FakeSoffice.fail().getPath());
		Recording builder = new Recording();
		File dest = File.createTempFile("thumb", ".png");

		builder.buildThumbnail(null, doc, "1.0", null, dest, 100, 80);

		assertEquals(1, builder.pdfs.size());
		assertArrayEquals(older, builder.pdfs.get(0));
		dest.delete();
	}

	@Test
	public void testFailureThrows() throws Exception {
		Context.get().getConfig().setProperty("converter.OfficeConverter.path", FakeSoffice.fail().getPath());
		Document doc = upload("a.docx");
		Recording builder = new Recording();
		File dest = File.createTempFile("thumb", ".png");

		try {
			inBackground(builder, doc, null, dest);
			fail("The thumbnail should fail");
		} catch (IOException expected) {
			// as it should
		}

		assertTrue(builder.pdfs.isEmpty());
		dest.delete();
	}

	private static long runs(File log) throws IOException {
		return Files.readAllLines(log.toPath()).stream().filter(l -> l.startsWith("start")).count();
	}

	@Test
	public void testOnDemandQueuesInsteadOfConverting() throws Exception {
		File log = new File("target/fake-soffice/thumb-on-demand.log");
		Files.deleteIfExists(log.toPath());
		Files.createFile(log.toPath());
		Context.get().getConfig().setProperty("converter.OfficeConverter.path", FakeSoffice.slow(0, log).getPath());
		Document doc = upload("a.docx");
		PreviewJobs.get().done(doc.getId(), "1.0");
		Recording builder = new Recording();
		File dest = File.createTempFile("thumb", ".png");

		for (int i = 0; i < 2; i++) {
			try {
				builder.buildThumbnail(null, doc, null, null, dest, 100, 80);
				fail("The thumbnail should fail");
			} catch (IOException expected) {
				assertTrue(expected.getMessage(), expected.getMessage().contains("queued"));
			}
		}

		assertEquals(0, runs(log));
		assertTrue(builder.pdfs.isEmpty());
		assertTrue(PreviewJobs.get().find(doc.getId(), "1.0").isPresent());
		dest.delete();
	}

	@Test
	public void testOnDemandLeavesAGivenUpJob() throws Exception {
		File log = new File("target/fake-soffice/thumb-given-up.log");
		Files.deleteIfExists(log.toPath());
		Files.createFile(log.toPath());
		Context.get().getConfig().setProperty("converter.OfficeConverter.path", FakeSoffice.slow(0, log).getPath());
		Document doc = upload("a.docx");
		for (int i = 0; i < PreviewJobs.MAX_ATTEMPTS; i++)
			PreviewJobs.get().failed(doc.getId(), "1.0", "LibreOffice exited 1 without a PDF for a.docx",
					Instant.now());
		File dest = File.createTempFile("thumb", ".png");

		try {
			new Recording().buildThumbnail(null, doc, null, null, dest, 100, 80);
			fail("The thumbnail should fail");
		} catch (IOException expected) {
			// as it should
		}

		assertEquals(0, runs(log));
		assertEquals(PreviewJobs.MAX_ATTEMPTS, PreviewJobs.get().find(doc.getId(), "1.0").orElseThrow().attempts());
		dest.delete();
	}
}
