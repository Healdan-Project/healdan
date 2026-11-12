package com.logicaldoc.core.searchengine.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.Test;

/**
 * Test case for {@link SnowballFilterFactory}: analyses on several threads at
 * once must stem exactly as one thread does, as Snowball's programs keep the
 * word being stemmed in their own fields.
 */
public class SnowballFilterFactoryTest {

	private static final int THREADS = 8;

	private static final int ROUNDS = 300;

	private static List<String> words() {
		String[] stems = { "running", "generalizations", "relational", "conditional", "happiness", "organisation",
				"differently", "hopefulness", "agreed", "caresses", "ponies", "documentation", "filing",
				"communities", "controlling", "adjustable", "triplicate", "formalize", "electricity", "revival" };
		List<String> list = new ArrayList<>();
		for (String s : stems) {
			list.add(s);
			list.add(s + "s");
		}
		return list;
	}

	private static List<String> stem(SnowballFilterFactory factory, String text) throws Exception {
		List<String> out = new ArrayList<>();
		WhitespaceTokenizer tokenizer = new WhitespaceTokenizer();
		tokenizer.setReader(new StringReader(text));
		try (TokenStream ts = factory.create(tokenizer)) {
			CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
			ts.reset();
			while (ts.incrementToken())
				out.add(term.toString());
			ts.end();
		}
		return out;
	}

	@Test
	public void testConcurrentAnalysis() throws Exception {
		HashMap<String, String> config = new HashMap<>();
		config.put("lang", "en");
		SnowballFilterFactory factory = new SnowballFilterFactory(config);

		String text = String.join(" ", words());
		List<String> expected = stem(factory, text);
		assertEquals(words().size(), expected.size());
		assertTrue(!expected.equals(words()));

		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		try {
			CountDownLatch gate = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (int t = 0; t < THREADS; t++) {
				futures.add(pool.submit((Callable<Integer>) () -> {
					gate.await();
					int wrong = 0;
					for (int i = 0; i < ROUNDS; i++) {
						try {
							if (!expected.equals(stem(factory, text)))
								wrong++;
						} catch (RuntimeException e) {
							wrong++;
						}
					}
					return wrong;
				}));
			}
			gate.countDown();
			int wrong = 0;
			for (Future<Integer> f : futures)
				wrong += f.get(60, TimeUnit.SECONDS);
			assertEquals("analyses that stemmed otherwise than one thread does", 0, wrong);
		} finally {
			pool.shutdownNow();
		}
	}
}
