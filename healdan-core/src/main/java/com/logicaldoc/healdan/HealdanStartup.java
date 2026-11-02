package com.logicaldoc.healdan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.healdan.db.HealdanMigrator;
import com.logicaldoc.healdan.db.HealdanMigrator.Status;
import com.logicaldoc.healdan.preview.PreviewWorker;
import com.logicaldoc.healdan.seed.SeedRunner;
import com.logicaldoc.healdan.signin.AdminSignInAlert;

import jakarta.annotation.PostConstruct;

/**
 * Prepares Healdan's data when the Spring context starts: first the schema, then the seeds,
 * then the listeners that need what the seeds made. A failure throws
 * {@link HealdanStartupException} and stops the startup.
 */
/*
 * The seeds reach the DAOs and the config through Context.get(), which is only
 * set once CE's "context" bean exists.
 */
@Component
@DependsOn("context")
public class HealdanStartup {

	private static final Logger log = LoggerFactory.getLogger(HealdanStartup.class);

	private final HealdanMigrator migrator;

	private final SeedRunner seedRunner;

	private final PreviewWorker previewWorker;

	private final AdminSignInAlert adminSignInAlert;

	@Autowired
	public HealdanStartup(HealdanMigrator migrator, SeedRunner seedRunner, PreviewWorker previewWorker,
			AdminSignInAlert adminSignInAlert) {
		this.migrator = migrator;
		this.seedRunner = seedRunner;
		this.previewWorker = previewWorker;
		this.adminSignInAlert = adminSignInAlert;
	}

	/*
	 * The worker starts only when the migrations and seeds ran. Tests build the
	 * context before the schema exists, so for them run() returns false and
	 * no thread starts.
	 */
	@PostConstruct
	void start() {
		if (run())
			previewWorker.start();
	}

	/**
	 * Migrates Healdan's tables, then runs the first-start seeds that haven't
	 * run yet, then registers the {@code admin} sign-in alert with CE's
	 * sessions, once: its template and settings come from a seed. Does nothing
	 * more when LogicalDOC's own schema doesn't exist yet: it runs again on the
	 * next start. Nothing here contacts a sign-in provider, so one that can't
	 * be reached never stops a start (M4 spec section 7.5).
	 * 
	 * @return true if the migrations and seeds ran
	 */
	public boolean run() {
		if (migrator.migrate() == Status.SKIPPED_NO_CE_SCHEMA) {
			log.info("LogicalDOC's schema doesn't exist yet, Healdan will set up its tables on the next start");
			return false;
		}
		seedRunner.runPending();
		// SessionManager adds a listener it already has no second time
		SessionManager.get().addListener(adminSignInAlert);
		return true;
	}
}
