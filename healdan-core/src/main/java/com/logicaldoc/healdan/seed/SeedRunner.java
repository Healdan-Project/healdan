package com.logicaldoc.healdan.seed;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.healdan.HealdanStartupException;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * Runs the {@link SeedStep}s that haven't run yet, in a fixed order.
 */
@Component
public class SeedRunner {

	/** The order of the steps; a step with no bean yet is skipped */
	static final List<String> ORDER = List.of("settings", "everyone", "profile", "templates-store", "columns",
			"search-attributes", "search-client-column", "reindex-office", "smoke-fixture");

	private static final String MARKER_PREFIX = "seed.";

	private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

	private final List<SeedStep> steps;

	private final HealdanSettings settings;

	@Autowired
	public SeedRunner(List<SeedStep> steps, HealdanSettings settings) {
		this.steps = steps;
		this.settings = settings;
	}

	/**
	 * Runs each step whose {@code seed.<name>} marker is missing, writing the
	 * marker after the step succeeds.
	 * 
	 * @throws HealdanStartupException if a step fails or has an unknown name
	 */
	public void runPending() {
		runPending(steps);
	}

	void runPending(List<SeedStep> candidates) {
		for (SeedStep step : candidates)
			if (!ORDER.contains(step.name()))
				throw new HealdanStartupException("Unknown seed step '%s'".formatted(step.name()));

		List<SeedStep> ordered = new ArrayList<>(candidates);
		ordered.sort((a, b) -> Integer.compare(ORDER.indexOf(a.name()), ORDER.indexOf(b.name())));

		for (SeedStep step : ordered) {
			String marker = MARKER_PREFIX + step.name();
			if (settings.get(marker) != null)
				continue;
			if (!step.enabled())
				continue;
			try {
				log.info("Running seed step {}", step.name());
				HealdanOperation.run(Kind.SEED, step::run);
				settings.set(marker, "done");
			} catch (Exception e) {
				throw new HealdanStartupException(("Seed step '%s' failed: %s. Fix the cause and restart; to skip the "
						+ "step instead, run insert into hd_setting (name, value) values ('%s', 'done')")
						.formatted(step.name(), e.getMessage(), marker), e);
			}
		}
	}
}
