package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import com.logicaldoc.healdan.api.model.Page;

/**
 * Paging as spec section 4.5 describes it.
 */
public class PagingTest {

	@Test
	public void testDefaults() {
		assertEquals(new Paging(0, 50), Paging.of(null, null));
		assertEquals(new Paging(10, 200), Paging.of(10, 200));
		assertEquals(new Paging(0, 1), Paging.of(0, 1));
	}

	@Test
	public void testOutOfRange() {
		assertBadRequest(-1, null);
		assertBadRequest(null, 0);
		assertBadRequest(null, 201);
	}

	@Test
	public void testSlice() {
		List<String> all = List.of("a", "b", "c", "d");

		assertEquals(new Page<>(List.of("b", "c"), 1, 2, 4L), Paging.of(1, 2).slice(all));
		assertEquals(new Page<>(List.of("d"), 3, 2, 4L), Paging.of(3, 2).slice(all));
		// Past the end: no items, and still the total
		assertEquals(new Page<>(List.of(), 10, 2, 4L), Paging.of(10, 2).slice(all));
	}

	@Test
	public void testWhole() {
		Page<String> page = Page.whole(List.of("a", "b"));

		assertEquals(List.of("a", "b"), page.items());
		assertEquals(0, page.offset());
		assertEquals(2, page.limit());
		assertEquals(Long.valueOf(2), page.total());
		assertNull(new Page<>(List.of(), 0, 50, null).total());
	}

	private static void assertBadRequest(Integer offset, Integer limit) {
		try {
			Paging.of(offset, limit);
			fail("offset " + offset + ", limit " + limit + " was accepted");
		} catch (ApiProblem problem) {
			assertEquals(400, problem.status());
			assertEquals("bad-request", problem.code());
		}
	}
}
