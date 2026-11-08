// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.healdan.db.HealdanMigrator;
import com.logicaldoc.healdan.db.HealdanMigrator.Status;
import com.logicaldoc.healdan.email.EmailIndexWorker;
import com.logicaldoc.healdan.preview.PreviewWorker;
import com.logicaldoc.healdan.seed.SeedRunner;
import com.logicaldoc.healdan.signin.AdminSignInAlert;
import com.logicaldoc.util.spring.Context;

import jakarta.annotation.PostConstruct;

/**
 * Prepares Healdan's data when the Spring context starts: first the schema, then the seeds,
 * then the listeners that need what the seeds made. A failure throws
 * {@link HealdanStartupException} and stops the startup. At a real start it
 * then starts the workers and tells each {@link HealdanStartListener}.
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

	private final EmailIndexWorker emailIndexWorker;

	private final AdminSignInAlert adminSignInAlert;

	@Autowired
	public HealdanStartup(HealdanMigrator migrator, SeedRunner seedRunner, PreviewWorker previewWorker,
			EmailIndexWorker emailIndexWorker, AdminSignInAlert adminSignInAlert) {
		this.migrator = migrator;
		this.seedRunner = seedRunner;
		this.previewWorker = previewWorker;
		this.emailIndexWorker = emailIndexWorker;
		this.adminSignInAlert = adminSignInAlert;
	}

	/*
	 * The workers and the start listeners start only when the migrations and
	 * seeds ran. Tests build the context before the schema exists, so for them
	 * run() returns false and no thread starts.
	 */
	@PostConstruct
	void start() {
		if (run()) {
			previewWorker.start();
			emailIndexWorker.start();
			for (HealdanStartListener listener : Context.get().getBeansOfType(HealdanStartListener.class))
				started(listener);
		}
	}

	/**
	 * Tells a listener that Healdan has started. One that fails is logged and the
	 * start goes on: its data is migrated and seeded already, and LogicalDOC
	 * still serves what the listener's work doesn't touch.
	 */
	private static void started(HealdanStartListener listener) {
		try {
			listener.started();
		} catch (RuntimeException e) {
			log.error("{} failed to start: {}", listener.getClass().getSimpleName(), e.getMessage(), e);
		}
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
