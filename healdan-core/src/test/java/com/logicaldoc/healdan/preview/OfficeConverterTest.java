package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.SQLException;

import org.junit.Test;

import com.logicaldoc.core.conversion.FormatConversionManager;
import com.logicaldoc.core.conversion.FormatConverter;
import com.logicaldoc.core.document.Document;
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

public class OfficeConverterTest extends AbstractHealdanTestCase {

	private static final String PATH = "converter.OfficeConverter.path";

	private static final String ENABLED = "converter.OfficeConverter.enabled";

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		Context.get().getConfig().setProperty("converter.OfficeConverter.timeout", "30");
	}

	private static FormatConverter converter(String in) throws Exception {
		return FormatConversionManager.get().getConverter(in, "a.pdf");
	}

	private Document upload(String name) throws Exception {
		HealdanFixtures fx = new HealdanFixtures();
		Matter matter = fx.matter(fx.client(997, "Healdan"), 1, "Office", fx.store("Main"), "Documents");
		Folder folder = FolderDAO.get()
				.findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents", Tenant.DEFAULT_ID, true).get(0);
		return fx.upload(folder, name, "content", fx.admin());
	}

	private static StoreResource pdfOf(Document doc) throws Exception {
		return StoreResource.builder().document(doc).fileVersion(doc.getFileVersion()).suffixPdfConversion().build();
	}

	@Test
	public void testRegisteredForEveryFormat() throws Exception {
		for (String ext : OfficeFormats.extensions())
			assertTrue(ext, converter("a." + ext) instanceof OfficeConverter);
	}

	@Test
	public void testAlwaysEnabled() throws Exception {
		Context.get().getConfig().setProperty(ENABLED, "false");
		assertTrue(new OfficeConverter().isEnabled());
		assertTrue(converter("a.docx") instanceof OfficeConverter);
	}

	@Test
	public void testConvertsAndCaches() throws Exception {
		Context.get().getConfig().setProperty(PATH, FakeSoffice.ok().getPath());
		Document doc = upload("a.docx");

		FormatConversionManager.get().convertToPdf(doc, null);

		assertTrue(Store.get().exists(pdfOf(doc)));
		try (var in = Store.get().getStream(pdfOf(doc))) {
			assertEquals("%PDF", new String(in.readNBytes(4), "US-ASCII"));
		}
	}

	@Test
	public void testFailureCachesNothing() throws Exception {
		Context.get().getConfig().setProperty(PATH, FakeSoffice.fail().getPath());
		Document doc = upload("a.docx");

		try {
			FormatConversionManager.get().convertToPdf(doc, null);
			fail("The conversion should fail");
		} catch (IOException expected) {
			// as it should
		}

		assertFalse(Store.get().exists(pdfOf(doc)));
	}
}
