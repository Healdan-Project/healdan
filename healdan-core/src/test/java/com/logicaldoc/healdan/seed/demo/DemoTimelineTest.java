package com.logicaldoc.healdan.seed.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.conversion.AbstractFormatConverter;
import com.logicaldoc.core.conversion.FormatConversionManager;
import com.logicaldoc.core.conversion.FormatConverter;
import com.logicaldoc.core.document.AbstractDocument;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.healdan.seed.demo.DemoManifest.Step;
import com.logicaldoc.util.spring.Context;

public class DemoTimelineTest extends AbstractHealdanTestCase {

	private static final Path DIR = Path.of("src/test/resources/demo");

	private DemoFirm firm;

	private DemoTimeline timeline;

	@Before
	public void buildTheFirm() throws Exception {
		DemoManifest manifest = DemoManifest.read(DIR.resolve("manifest.json"));
		DemoFirm[] built = new DemoFirm[1];
		HealdanOperation.run(Kind.SEED, () -> built[0] = new DemoFirm(manifest, "Demo-Firm-2026").build());
		firm = built[0];
		timeline = new DemoTimeline(firm, DIR.resolve("files"),
				new DemoClock(LocalDate.of(2026, 10, 29), ZoneId.systemDefault()),
				new Backdater(new JdbcTemplate(Context.get(DataSource.class))), DemoTimeline::index);
	}

	private static Step step(String as, String action, String matter, String folder, String file) {
		return new Step(-1, null, as, action, "k", matter, folder, file, null, null, null, null, null, null, null,
				null, null, false, null, null, null, null);
	}

	/** A step with the fields the folder, file, grant and deleteFolder tests use */
	private static Step step(String as, String action, String key, String matter, String folder, String file,
			String name, String doc, List<String> to) {
		return new Step(-1, null, as, action, key, matter, folder, file, name, doc, null, null, null, null, null,
				null, null, false, null, null, to, null);
	}

	private void play(Step... steps) throws Exception {
		HealdanOperation.run(Kind.SEED, () -> timeline.run(List.of(steps)));
	}

	@Test
	public void testFilingWhereTheUserCantWriteIsRefused() {
		Exception e = assertThrows(Exception.class,
				() -> play(step("pnair", "file", "1001.2", "Correspondence", "letter.txt")));
		assertTrue(e.getMessage(), e.getMessage().contains("pnair lacks WRITE"));
	}

	@Test
	public void testAnUnknownActionIsRefused() {
		Exception e = assertThrows(Exception.class, () -> play(step("pnair", "shred", null, null, null)));
		assertTrue(e.getMessage(), e.getMessage().contains("shred"));
	}

	@Test
	public void testAMissingFileIsRefused() {
		Exception e = assertThrows(Exception.class,
				() -> play(step("pnair", "file", "1001.1", "Correspondence", "nowhere.txt")));
		assertTrue(e.getMessage(), e.getMessage().contains("nowhere.txt"));
	}

	@Test
	public void testFilingAtAMattersTopLevelIsRefusedByHealdan() {
		// The filing rule, not the demo's own check: the workspace's top level takes no documents
		Exception e = assertThrows(Exception.class, () -> play(new Step(-1, null, "hashcombe", "file", "k", "1001.1",
				null, "letter.txt", null, null, null, null, null, null, null, null, null, false, null, null, null, null)));
		assertTrue(e.getMessage(), e.getMessage().contains("top level"));
	}

	/**
	 * Drafts holds a document whose own ACL gives hashcombe no DELETE. The web page checks every folder and
	 * document in a tree before it deletes the tree, and inside a seed Healdan's own check is skipped
	 */
	@Test
	public void testDeletingAFolderWithADocumentTheUserCantDeleteIsRefused() {
		Exception e = assertThrows(Exception.class, () -> play(draftsFolder(),
				step("hashcombe", "file", "draft", "1001.1", "Correspondence/Drafts", "letter.txt", "Draft.txt", null,
						null),
				step("hashcombe", "grant", null, null, null, null, null, "draft", List.of("hashcombe")),
				deleteFolder("hashcombe", "Correspondence/Drafts")));
		// The tree check's refusal, not the check on the folder alone, which hashcombe passes
		assertTrue(e.getMessage(),
				e.getMessage().contains("hashcombe lacks DELETE on everything in 1001.1/Correspondence/Drafts"));
		assertTrue(e.getMessage(), e.getMessage().contains("DELETE on document"));
	}

	@Test
	public void testDeletingAFolderWhoseDocumentsTheUserMayDeleteIsAllowed() throws Exception {
		play(draftsFolder(), step("hashcombe", "file", "draft", "1001.1", "Correspondence/Drafts", "letter.txt",
				"Draft.txt", null, null), deleteFolder("hashcombe", "Correspondence/Drafts"));

		assertThrows(IllegalArgumentException.class, () -> firm.folder("1001.1", "Correspondence/Drafts"));
	}

	@Test
	public void testANonAdminCantAddAFolderAtAMattersTopLevel() {
		Exception e = assertThrows(Exception.class,
				() -> play(step("hashcombe", "folder", null, "1001.1", null, null, "Evidence", null, null)));
		assertTrue(e.getMessage(), e.getMessage().contains("hashcombe isn't an administrator"));
		assertTrue(e.getMessage(), e.getMessage().contains("top-level folders"));
	}

	@Test
	public void testAnAdminAddsAndDeletesAFolderAtAMattersTopLevel() throws Exception {
		play(step("mvance", "folder", null, "1001.1", null, null, "Evidence", null, null));
		assertTrue(firm.folder("1001.1", "Evidence").getId() > 0);

		play(deleteFolder("mvance", "Evidence"));

		assertThrows(IllegalArgumentException.class, () -> firm.folder("1001.1", "Evidence"));
	}

	@Test
	public void testANonAdminCantDeleteAMattersTopLevelFolder() {
		Exception e = assertThrows(Exception.class, () -> play(deleteFolder("hashcombe", "Correspondence")));
		assertTrue(e.getMessage(), e.getMessage().contains("hashcombe isn't an administrator"));
		assertTrue(e.getMessage(), e.getMessage().contains("top-level folders"));
	}

	@Test
	public void testANonAdminCantDeleteAMatterByItsWorkspace() {
		Exception e = assertThrows(Exception.class, () -> play(deleteFolder("hashcombe", null)));
		assertTrue(e.getMessage(), e.getMessage().contains("hashcombe isn't an administrator"));
		assertTrue(e.getMessage(), e.getMessage().contains("workspace"));
	}

	/**
	 * {@code Correspondence/} names the top-level folder, but a check on whether the path has a slash takes it for
	 * a deeper one, so a non-admin would delete a top-level folder
	 */
	@Test
	public void testAFolderPathWithAnEmptyPartIsRefused() throws Exception {
		for (String path : List.of("Correspondence/", "/Correspondence", "Correspondence//Drafts")) {
			IllegalArgumentException e = assertThrows(path, IllegalArgumentException.class,
					() -> firm.folder("1001.1", path));
			assertTrue(e.getMessage(), e.getMessage().contains("empty"));
		}
		Exception e = assertThrows(Exception.class, () -> play(deleteFolder("hashcombe", "Correspondence/")));
		assertTrue(e.getMessage(), e.getMessage().contains("empty"));
		// It's still there
		assertTrue(firm.folder("1001.1", "Correspondence").getId() > 0);
	}

	/** The error says which step, so a long manifest's mistake can be found */
	@Test
	public void testAStepWithNoPastDayOrABadTimeNamesItself() {
		Step noDay = new Step(0, null, "hashcombe", "file", "k", "1001.1", "Correspondence", "letter.txt", null, null,
				null, null, null, null, null, null, null, false, null, null, null, null);
		Exception e = assertThrows(Exception.class, () -> play(noDay));
		assertTrue(e.getMessage(), e.getMessage().contains("Demo step 'file' by hashcombe"));

		Step badTime = new Step(-1, "9am", "hashcombe", "file", "k", "1001.1", "Correspondence", "letter.txt", null,
				null, null, null, null, null, null, null, null, false, null, null, null, null);
		e = assertThrows(Exception.class, () -> play(badTime));
		assertTrue(e.getMessage(), e.getMessage().contains("Demo step 'file' by hashcombe"));
		assertTrue(e.getMessage(), e.getMessage().contains("9am"));
	}

	@Test
	public void testMovingADocumentToAMattersTopLevelIsRefusedByHealdan() {
		Exception e = assertThrows(Exception.class, () -> play(
				step("hashcombe", "file", "k", "1001.1", "Correspondence", "letter.txt", "Letter.txt", null, null),
				step("hashcombe", "move", null, "1001.1", null, null, null, "k", null)));
		assertTrue(e.getMessage(), e.getMessage().contains("top level"));
	}

	@Test
	public void testAnImageIsReadThroughItsConversionAndADocumentIsNot() {
		assertTrue(DemoTimeline.readThroughConversion("receipt.jpg"));
		assertTrue(DemoTimeline.readThroughConversion("plan.png"));
		assertFalse(DemoTimeline.readThroughConversion("letter.txt"));
		assertFalse(DemoTimeline.readThroughConversion("reply.eml"));
	}

	/**
	 * CE reads an image through its PDF conversion, and the conversion saves the document under the indexing that
	 * asked for it, so the indexing's own save used to fail on the stack (where ImageMagick converts) and stop
	 * the seed. The tests' context has no converter that works, so this one stands in for ImageMagick's.
	 */
	@Test
	public void testAnImageIsIndexedAfterItsConversionSavedTheDocument() throws Exception {
		Map<String, List<FormatConverter>> converters = FormatConversionManager.get().getConverters();
		converters.put("jpg-pdf", List.of(new BlankPdfConverter()));
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		try {
			play(step("hashcombe", "file", "receipt", "1001.1", "Correspondence", "receipt.jpg", "Receipt.jpg", null,
					null));
		} finally {
			converters.remove("jpg-pdf");
			// The store is on disk and the next run files the same id: a PDF left here would hide the conversion
			jdbc.queryForList("select ld_id from ld_document where ld_filename = 'Receipt.jpg'", Long.class)
					.forEach(Store.get()::delete);
		}

		assertEquals(Integer.valueOf(IndexingStatus.INDEXED.ordinal()), jdbc
				.queryForObject("select ld_indexed from ld_document where ld_filename = 'Receipt.jpg'", Integer.class));
		for (DocumentEvent event : List.of(DocumentEvent.CONVERTED, DocumentEvent.INDEXED))
			assertEquals(event.toKey(), Integer.valueOf(1),
					jdbc.queryForObject("select count(*) from ld_history h join ld_document d on d.ld_id = h.ld_docid "
							+ "where d.ld_filename = 'Receipt.jpg' and h.ld_event = ?", Integer.class, event.toKey()));
	}

	/** Stands in for ImageMagick: writes a PDF of one blank page */
	private static final class BlankPdfConverter extends AbstractFormatConverter {
		@Override
		public boolean isEnabled() {
			return true;
		}

		@Override
		protected void internalConvert(String sid, AbstractDocument document, File src, File dest)
				throws IOException {
			try (PDDocument pdf = new PDDocument()) {
				pdf.addPage(new PDPage());
				pdf.save(dest);
			}
		}
	}

	private static Step draftsFolder() {
		return step("hashcombe", "folder", null, "1001.1", "Correspondence", null, "Drafts", null, null);
	}

	private static Step deleteFolder(String as, String folder) {
		return step(as, "deleteFolder", null, "1001.1", folder, null, null, null, null);
	}
}
