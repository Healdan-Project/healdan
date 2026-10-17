package com.logicaldoc.healdan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.db.HealdanMigrator;
import com.logicaldoc.healdan.db.HealdanMigrator.Status;
import com.logicaldoc.healdan.seed.SeedRunner;

import jakarta.annotation.PostConstruct;

/**
 * Prepares Healdan's data when the Spring context starts: first the schema, then the seeds.
 * A failure throws {@link HealdanStartupException} and stops the startup.
 */
@Component
public class HealdanStartup {

	private static final Logger log = LoggerFactory.getLogger(HealdanStartup.class);

	private final HealdanMigrator migrator;

	private final SeedRunner seedRunner;

	@Autowired
	public HealdanStartup(HealdanMigrator migrator, SeedRunner seedRunner) {
		this.migrator = migrator;
		this.seedRunner = seedRunner;
	}

	@PostConstruct
	void start() {
		run();
	}

	/**
	 * Migrates Healdan's tables, then runs the first-start seeds that haven't
	 * run yet. Does nothing more when LogicalDOC's own schema doesn't exist
	 * yet: it runs again on the next start.
	 */
	public void run() {
		if (migrator.migrate() == Status.SKIPPED_NO_CE_SCHEMA) {
			log.info("LogicalDOC's schema doesn't exist yet, Healdan will set up its tables on the next start");
			return;
		}
		seedRunner.runPending();
	}
}
