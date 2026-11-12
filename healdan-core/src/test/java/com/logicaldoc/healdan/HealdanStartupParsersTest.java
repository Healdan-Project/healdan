// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.logicaldoc.core.parser.PDFParser;
import com.logicaldoc.core.parser.Parser;
import com.logicaldoc.core.parser.ParserFactory;
import com.logicaldoc.healdan.db.HealdanMigrator;
import com.logicaldoc.healdan.email.EmailIndexWorker;
import com.logicaldoc.healdan.parser.EmailParser;
import com.logicaldoc.healdan.parser.WordParser;
import com.logicaldoc.healdan.preview.PreviewWorker;
import com.logicaldoc.healdan.seed.SeedRunner;
import com.logicaldoc.healdan.signin.AdminSignInAlert;
import com.logicaldoc.util.spring.Context;

/**
 * {@link HealdanStartup} fills CE's {@link ParserFactory} at a real start, before the workers start, so
 * that the first parses after a start, such as the Indexer's two threads', never fill it at once.
 */
public class HealdanStartupParsersTest extends AbstractHealdanTestCase {

	private static final int THREADS = 16;

	/** A preview worker that starts no thread and remembers the factory's parsers as they were then */
	private static class RecordingWorker extends PreviewWorker {

		private Map<String, Parser> atStart;

		@Override
		public synchronized void start() {
			atStart = new HashMap<>(factory());
		}
	}

	/** The factory's own map, read without calling ParserFactory, which would fill it */
	@SuppressWarnings("unchecked")
	private static Map<String, Parser> factory() {
		try {
			return (Map<String, Parser>) field().get(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Empties the factory as a fresh JVM has it: a new map, which CE's first parse fills */
	private static void emptyFactory() throws ReflectiveOperationException {
		field().set(null, new HashMap<String, Parser>());
	}

	private static Field field() throws NoSuchFieldException {
		Field field = ParserFactory.class.getDeclaredField("parsers");
		field.setAccessible(true);
		return field;
	}

	private static HealdanStartup startup(PreviewWorker worker) {
		return new HealdanStartup(Context.get(HealdanMigrator.class), Context.get(SeedRunner.class), worker,
				Context.get(EmailIndexWorker.class), Context.get(AdminSignInAlert.class));
	}

	@Test
	public void testStartFillsTheParsersBeforeTheWorkersStart() throws Exception {
		emptyFactory();
		RecordingWorker worker = new RecordingWorker();

		startup(worker).start();

		Map<String, Parser> atStart = worker.atStart;
		assertNotNull("the preview worker started", atStart);
		assertTrue("CE's own: " + atStart.keySet(), atStart.get("pdf") instanceof PDFParser);
		assertTrue("Healdan's Parser extensions: " + atStart.keySet(), atStart.get("docx") instanceof WordParser);
		assertTrue(atStart.keySet().toString(), atStart.get("eml") instanceof EmailParser);
		assertTrue(atStart.keySet().toString(), atStart.get("msg") instanceof EmailParser);
	}

	@Test
	public void testParsesAtOnceAfterTheStartUseWhatTheStartFilled() throws Exception {
		emptyFactory();
		startup(new RecordingWorker()).start();
		Map<String, Parser> filled = factory();
		Parser pdf = filled.get("pdf");
		assertTrue("the start filled the parsers: " + filled.keySet(), pdf instanceof PDFParser);
		int size = filled.size();

		// As the Indexer's threads do after a start, and many more of them; init() again would make new parsers
		CyclicBarrier together = new CyclicBarrier(THREADS);
		Callable<List<Parser>> parses = () -> {
			together.await(10, TimeUnit.SECONDS);
			List<Parser> found = new ArrayList<>();
			for (int i = 0; i < 200; i++) {
				found.add(ParserFactory.getParser("Charterparty " + i + ".pdf"));
				ParserFactory.getParser("Note " + i + ".docx");
			}
			return found;
		};
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		try {
			List<Future<List<Parser>>> futures = new ArrayList<>();
			for (int i = 0; i < THREADS; i++)
				futures.add(pool.submit(parses));
			for (Future<List<Parser>> future : futures)
				for (Parser found : future.get(30, TimeUnit.SECONDS))
					assertSame("each parse found the start's PDF parser", pdf, found);
		} finally {
			pool.shutdownNow();
		}

		// init() refills the same map with new parsers
		assertSame("nothing filled the factory again", pdf, factory().get("pdf"));
		assertEquals(size, factory().size());
	}
}
