// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.Test;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.db.HealdanMigrator;
import com.logicaldoc.healdan.email.EmailIndexWorker;
import com.logicaldoc.healdan.preview.PreviewWorker;
import com.logicaldoc.healdan.seed.SeedRunner;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.AdminSignInAlert;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.spring.Context;

/**
 * {@link HealdanStartup} tells each {@link HealdanStartListener} at a real start, after the seeds, and never from
 * {@code run()}, which every test's {@code setUp} calls (call 5).
 */
public class HealdanStartListenerTest extends AbstractHealdanTestCase {

	/** The first seed's marker, which a seed run writes again once it's gone */
	private static final String SEED_MARKER = "seed.settings";

	/**
	 * Remembers each call, and the first seed's marker as it was then. A bean in every context that has
	 * {@code healdan-core}'s test classes, where nothing calls {@code start()} once the schema is there.
	 */
	@Component
	public static class Recorder implements HealdanStartListener {

		private final List<String> calls = new ArrayList<>();

		@Override
		public synchronized void started() {
			calls.add(String.valueOf(HealdanSettings.get().get(SEED_MARKER)));
		}

		synchronized List<String> calls() {
			return List.copyOf(calls);
		}
	}

	private static Recorder recorder() {
		return Context.get(Recorder.class);
	}

	/**
	 * Throws what a test arms it with, once, and remembers how many calls {@link Recorder} had then. Its name puts it
	 * before {@link Recorder} in the scan, so the recorder is the next listener. Does nothing unless armed.
	 */
	@Component
	public static class Breaker implements HealdanStartListener {

		static volatile Throwable armed;

		static volatile int recorderCallsBefore = -1;

		@Override
		public void started() {
			Throwable failure = armed;
			if (failure == null)
				return;
			armed = null;
			recorderCallsBefore = recorder().calls().size();
			if (failure instanceof Error error)
				throw error;
			throw (RuntimeException) failure;
		}

		static void reset() {
			armed = null;
			recorderCallsBefore = -1;
		}
	}

	@Override
	public void tearDown() throws IOException {
		Breaker.reset();
		super.tearDown();
	}

	@Test
	public void testAnErrorIsLoggedAndTheNextListenerStarts() {
		// A class missing from a plugin's zip
		Breaker.armed = new NoClassDefFoundError("com/example/Missing");

		try (LogCapture log = LogCapture.of(HealdanStartup.class)) {
			Context.get(HealdanStartup.class).start();

			assertEquals("the breaker ran first", 0, Breaker.recorderCallsBefore);
			assertEquals("the next listener started", 1, recorder().calls().size());
			assertTrue(log.lines().toString(), log.lines().stream()
					.anyMatch(line -> line.startsWith("ERROR Breaker failed to start: com/example/Missing")));
		}
	}

	@Test
	public void testTheJvmsOwnErrorIsThrown() {
		Breaker.armed = new InternalError("the JVM failed");

		InternalError thrown = assertThrows(InternalError.class, () -> Context.get(HealdanStartup.class).start());

		assertEquals("the JVM failed", thrown.getMessage());
		assertEquals(0, Breaker.recorderCallsBefore);
	}

	@Test
	public void testStartCallsEachListenerOnceAfterTheSeeds() {
		HealdanSettings.get().delete(SEED_MARKER);
		assertNull(HealdanSettings.get().get(SEED_MARKER));

		Context.get(HealdanStartup.class).start();

		List<String> calls = recorder().calls();
		assertEquals(1, calls.size());
		assertNotNull("the seeds ran before the listener", HealdanSettings.get().get(SEED_MARKER));
		assertEquals("the seeds ran before the listener", HealdanSettings.get().get(SEED_MARKER), calls.get(0));
	}

	@Test
	public void testRunAloneCallsNone() {
		// setUp's run() called none
		assertEquals(List.of(), recorder().calls());

		Context.get(HealdanStartup.class).run();

		assertEquals(List.of(), recorder().calls());
	}

	@Test
	public void testNoCeSchemaCallsNone() throws Exception {
		// The context's own start found no CE schema, as the test database is loaded after it
		assertEquals(List.of(), recorder().calls());

		JDBCDataSource empty = new JDBCDataSource();
		empty.setUrl("jdbc:hsqldb:mem:healdan-no-ce-schema");
		empty.setUser("sa");
		empty.setPassword("");
		try {
			HealdanStartup startup = new HealdanStartup(new HealdanMigrator(empty), Context.get(SeedRunner.class),
					Context.get(PreviewWorker.class), Context.get(EmailIndexWorker.class),
					Context.get(AdminSignInAlert.class));
			startup.start();

			assertEquals(List.of(), recorder().calls());
		} finally {
			try (Connection con = empty.getConnection(); Statement statement = con.createStatement()) {
				statement.execute("shutdown");
			}
		}
	}
}
