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

	private DemoTimeline timeline;

	@Before
	public void buildTheFirm() throws Exception {
		DemoManifest manifest = DemoManifest.read(DIR.resolve("manifest.json"));
		DemoFirm[] firm = new DemoFirm[1];
		HealdanOperation.run(Kind.SEED, () -> firm[0] = new DemoFirm(manifest, "Demo-Firm-2026").build());
		timeline = new DemoTimeline(firm[0], DIR.resolve("files"),
				new DemoClock(LocalDate.of(2026, 10, 29), ZoneId.systemDefault()),
				new Backdater(new JdbcTemplate(Context.get(DataSource.class))), DemoTimeline::index);
	}

	private static Step step(String as, String action, String matter, String folder, String file) {
		return new Step(-1, null, as, action, "k", matter, folder, file, null, null, null, null, null, null, null,
				null, null, false, null, null, null, null);
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
}
