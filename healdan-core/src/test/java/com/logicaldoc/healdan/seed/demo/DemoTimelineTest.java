package com.logicaldoc.healdan.seed.demo;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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

	@Test
	public void testMovingADocumentToAMattersTopLevelIsRefusedByHealdan() {
		Exception e = assertThrows(Exception.class, () -> play(
				step("hashcombe", "file", "k", "1001.1", "Correspondence", "letter.txt", "Letter.txt", null, null),
				step("hashcombe", "move", null, "1001.1", null, null, null, "k", null)));
		assertTrue(e.getMessage(), e.getMessage().contains("top level"));
	}

	private static Step draftsFolder() {
		return step("hashcombe", "folder", null, "1001.1", "Correspondence", null, "Drafts", null, null);
	}

	private static Step deleteFolder(String as, String folder) {
		return step(as, "deleteFolder", null, "1001.1", folder, null, null, null, null);
	}
}
