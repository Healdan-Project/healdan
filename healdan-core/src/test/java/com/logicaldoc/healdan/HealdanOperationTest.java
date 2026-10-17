package com.logicaldoc.healdan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.healdan.HealdanOperation.Kind;

public class HealdanOperationTest {

	@Test
	public void testAskingLeavesNothingOnTheThread() {
		assertFalse(HealdanOperation.active(Kind.SEED));
		assertNull(HealdanOperation.STACK.get());
	}

	@Test
	public void testOperationsNestAndUnwind() throws Exception {
		HealdanOperation.run(Kind.SEED, () -> {
			assertTrue(HealdanOperation.active(Kind.SEED));
			assertFalse(HealdanOperation.active(Kind.MATTER_CREATE));
			HealdanOperation.run(Kind.MATTER_CREATE, () -> {
				assertTrue(HealdanOperation.active(Kind.SEED));
				assertTrue(HealdanOperation.active(Kind.MATTER_CREATE));
			});
			assertTrue(HealdanOperation.active(Kind.SEED));
			assertFalse(HealdanOperation.active(Kind.MATTER_CREATE));
		});
		assertFalse(HealdanOperation.active(Kind.SEED));
		assertNull(HealdanOperation.STACK.get());
	}

	@Test
	public void testFailedOperationIsPopped() {
		assertThrows(IllegalStateException.class, () -> HealdanOperation.run(Kind.SEED, () -> {
			throw new IllegalStateException("boom");
		}));
		assertFalse(HealdanOperation.active(Kind.SEED));
		assertNull(HealdanOperation.STACK.get());
	}
}
