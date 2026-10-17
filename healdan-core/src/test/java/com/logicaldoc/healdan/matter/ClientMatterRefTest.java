package com.logicaldoc.healdan.matter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.junit.Test;

public class ClientMatterRefTest {

	@Test
	public void testClientAndMatter() {
		ClientMatterRef ref = ClientMatterRef.parse("998.1").orElseThrow();
		assertEquals(998, ref.client());
		assertEquals(Long.valueOf(1), ref.matter());
		assertFalse(ref.isNumberOnly());
	}

	@Test
	public void testSpacesAndLeadingZeroesAreIgnored() {
		ClientMatterRef ref = ClientMatterRef.parse(" 998.01 ").orElseThrow();
		assertEquals(998, ref.client());
		assertEquals(Long.valueOf(1), ref.matter());
	}

	@Test
	public void testBareNumber() {
		ClientMatterRef ref = ClientMatterRef.parse("7").orElseThrow();
		assertEquals(7, ref.client());
		assertNull(ref.matter());
		assertTrue(ref.isNumberOnly());
	}

	@Test
	public void testNotAReference() {
		for (String text : new String[] { "998.1.2", "a.1", "998.", ".1", "", "  ", "-1", "9 98.1",
				"99999999999999999999.1" })
			assertEquals("'%s'".formatted(text), Optional.empty(), ClientMatterRef.parse(text));
		assertEquals(Optional.empty(), ClientMatterRef.parse(null));
	}
}
