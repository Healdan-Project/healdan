package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
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

		builder.buildThumbnail(null, doc, null, null, dest, 100, 80);
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

	@Test
	public void testFailureThrows() throws Exception {
		Context.get().getConfig().setProperty("converter.OfficeConverter.path", FakeSoffice.fail().getPath());
		Document doc = upload("a.docx");
		Recording builder = new Recording();
		File dest = File.createTempFile("thumb", ".png");

		try {
			builder.buildThumbnail(null, doc, null, null, dest, 100, 80);
			fail("The thumbnail should fail");
		} catch (IOException expected) {
			// as it should
		}

		assertTrue(builder.pdfs.isEmpty());
		dest.delete();
	}
}
