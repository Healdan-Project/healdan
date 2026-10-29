package com.logicaldoc.healdan.seed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.seed.demo.Backdater;
import com.logicaldoc.healdan.seed.demo.DemoClock;
import com.logicaldoc.healdan.seed.demo.DemoFirm;
import com.logicaldoc.healdan.seed.demo.DemoManifest;
import com.logicaldoc.healdan.seed.demo.DemoManifest.Step;
import com.logicaldoc.healdan.seed.demo.DemoTimeline;
import com.logicaldoc.util.spring.Context;

/**
 * For screenshots only: when {@code HEALDAN_DEMO_FIXTURE} is {@code true},
 * fills a fresh stack with the demo firm that the manifest in
 * {@code HEALDAN_DEMO_DIR} describes, then plays its timeline with the files
 * in that folder's {@code files}. Its users sign in with
 * {@code HEALDAN_DEMO_PASSWORD} (demo spec §5). {@code docker/demo}'s
 * overlay sets all three.
 * <p>
 * Without the flag the step does nothing and writes no marker. It refuses a
 * repository that holds any document, so it never mixes invented data into
 * real data (demo spec §5.3).
 */
@Component
public class DemoFixtureSeed implements SeedStep {

	static final String ENV = "HEALDAN_DEMO_FIXTURE";

	static final String DIR_ENV = "HEALDAN_DEMO_DIR";

	static final String PASSWORD_ENV = "HEALDAN_DEMO_PASSWORD";

	private final UnaryOperator<String> env;

	private final Supplier<LocalDate> today;

	@Autowired
	public DemoFixtureSeed() {
		this(System::getenv, LocalDate::now);
	}

	DemoFixtureSeed(UnaryOperator<String> env, Supplier<LocalDate> today) {
		this.env = env;
		this.today = today;
	}

	@Override
	public String name() {
		return "demo-fixture";
	}

	@Override
	public boolean enabled() {
		return "true".equals(env.apply(ENV));
	}

	/**
	 * Not one transaction: CE saves versions in the background, which a
	 * transaction held open here would block. A failure leaves a partial
	 * firm, and the next start's guard asks for a fresh stack (demo spec
	 * §5.3).
	 */
	@Override
	public void run() throws Exception {
		Path dir = Path.of(required(DIR_ENV));
		String password = required(PASSWORD_ENV);
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		refuseARepositoryWithDocuments(jdbc);

		Path manifestFile = dir.resolve("manifest.json");
		if (!Files.isRegularFile(manifestFile))
			throw new IllegalStateException("No demo manifest at %s".formatted(manifestFile));
		DemoManifest manifest = DemoManifest.read(manifestFile);
		DemoClock clock = new DemoClock(today.get(), ZoneId.systemDefault());

		Backdater backdater = new Backdater(jdbc);
		DemoFirm firm = new DemoFirm(manifest, password).build();
		// The firm is set up the day before its first action
		int firstDay = manifest.timeline().stream().mapToInt(Step::day).min().orElse(-1);
		backdater.stamp(clock.at(firstDay - 1, "08:30"));
		new DemoTimeline(firm, dir.resolve("files"), clock, backdater, DemoTimeline::index).run(manifest.timeline());
	}

	private String required(String name) {
		String value = env.apply(name);
		if (value == null || value.isBlank())
			throw new IllegalStateException("%s is set, but %s isn't".formatted(ENV, name));
		return value;
	}

	/** Plan call 4: the filing rule keeps documents out of the Templates Store, so any document is real data */
	private static void refuseARepositoryWithDocuments(JdbcTemplate jdbc) {
		Long documents = jdbc.queryForObject("select count(*) from ld_document", Long.class);
		if (documents != null && documents > 0)
			throw new IllegalStateException(("The demo fixture runs only on a fresh stack, and this one holds %d "
					+ "documents. Unset %s, or start over with docker compose down -v.").formatted(documents, ENV));
	}
}
