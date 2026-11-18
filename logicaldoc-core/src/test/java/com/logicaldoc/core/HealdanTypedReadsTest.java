// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Proves that {@link HealdanTypedReads} converts in Java the numbers
 * PostgreSQL's driver refuses to convert, without asking the driver, and hands
 * the driver everything it doesn't convert exactly, as CE's typed reads did.
 * The result set refuses every typed read with pgjdbc's message, as pgjdbc
 * does for a {@code Long} from an {@code int4}, and records each one.
 */
public class HealdanTypedReadsTest {

	private static final String REFUSAL = "conversion to class java.lang.Long from int4 not supported";

	/**
	 * The types the driver was asked for, one per call of
	 * {@code getObject(1, type)}
	 */
	private final List<Class<?>> askedFor = new ArrayList<>();

	private ResultSet rs;

	@Before
	public void setUp() {
		rs = (ResultSet) Proxy.newProxyInstance(HealdanTypedReadsTest.class.getClassLoader(),
				new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
					if (method.getName().equals("getObject") && method.getParameterCount() == 2
							&& Integer.valueOf(1).equals(args[0]) && args[1] instanceof Class<?> type) {
						askedFor.add(type);
						throw new SQLException(REFUSAL);
					}
					throw new AssertionError("Unexpected call: " + method);
				});
	}

	@Test
	public void testAnIntColumnReadAsALong() throws SQLException {
		assertEquals(Long.valueOf(1L), HealdanTypedReads.read(rs, Integer.valueOf(1), Long.class));
		assertTrue(askedFor.isEmpty());
	}

	@Test
	public void testASumReadAsALong() throws SQLException {
		assertEquals(Long.valueOf(42L), HealdanTypedReads.read(rs, new BigDecimal("42"), Long.class));
		assertTrue(askedFor.isEmpty());
	}

	@Test
	public void testACountReadAsAnInteger() throws SQLException {
		assertEquals(Integer.valueOf(7), HealdanTypedReads.read(rs, Long.valueOf(7), Integer.class));
		assertTrue(askedFor.isEmpty());
	}

	@Test
	public void testANumberReadAsText() throws SQLException {
		assertEquals("7", HealdanTypedReads.read(rs, Long.valueOf(7), String.class));
		assertTrue(askedFor.isEmpty());
	}

	@Test
	public void testTheTypeItAlreadyIs() throws SQLException {
		String text = "text";
		assertSame(text, HealdanTypedReads.read(rs, text, String.class));

		Timestamp timestamp = new Timestamp(1_700_000_000_000L);
		assertSame(timestamp, HealdanTypedReads.read(rs, timestamp, Timestamp.class));

		assertTrue(askedFor.isEmpty());
	}

	@Test
	public void testNullIsNull() throws SQLException {
		assertNull(HealdanTypedReads.read(rs, null, Long.class));
		assertTrue(askedFor.isEmpty());
	}

	@Test
	public void testWhatItCantConvertGoesToTheDriver() {
		try {
			HealdanTypedReads.read(rs, "abc", Long.class);
			fail("The driver's refusal should reach the caller");
		} catch (SQLException e) {
			assertEquals(REFUSAL, e.getMessage());
		}
		assertEquals(List.of(Long.class), askedFor);
	}

	/**
	 * Every whole number held exactly converts to each number type, and a
	 * decimal's zeros after the point don't make it a fraction
	 */
	@Test
	public void testWholeNumbersOfEachKind() throws SQLException {
		assertEquals(Long.valueOf(3L), HealdanTypedReads.read(rs, Short.valueOf((short) 3), Long.class));
		assertEquals(Long.valueOf(3L), HealdanTypedReads.read(rs, Byte.valueOf((byte) 3), Long.class));
		assertEquals(Long.valueOf(3L), HealdanTypedReads.read(rs, BigInteger.valueOf(3), Long.class));
		assertEquals(Long.valueOf(42L), HealdanTypedReads.read(rs, new BigDecimal("42.00"), Long.class));
		assertEquals(Integer.valueOf(42), HealdanTypedReads.read(rs, new BigDecimal("42"), Integer.class));
		assertEquals(Integer.valueOf(Integer.MIN_VALUE),
				HealdanTypedReads.read(rs, Long.valueOf(Integer.MIN_VALUE), Integer.class));
		assertEquals(Long.valueOf(Long.MAX_VALUE),
				HealdanTypedReads.read(rs, new BigDecimal(Long.MAX_VALUE), Long.class));

		assertEquals(Double.valueOf(7D), HealdanTypedReads.read(rs, Long.valueOf(7), Double.class));
		assertEquals(Double.valueOf(42.5D), HealdanTypedReads.read(rs, new BigDecimal("42.50"), Double.class));
		assertEquals(Float.valueOf(7F), HealdanTypedReads.read(rs, Integer.valueOf(7), Float.class));
		assertEquals(new BigDecimal("7"), HealdanTypedReads.read(rs, Integer.valueOf(7), BigDecimal.class));
		assertEquals(new BigDecimal("3"), HealdanTypedReads.read(rs, BigInteger.valueOf(3), BigDecimal.class));

		assertTrue(askedFor.isEmpty());
	}

	/**
	 * A decimal reads as text in plain notation, its scale kept, never in
	 * scientific notation
	 */
	@Test
	public void testADecimalReadAsTextIsPlain() throws SQLException {
		assertEquals("42.50", HealdanTypedReads.read(rs, new BigDecimal("42.50"), String.class));
		assertEquals("1000", HealdanTypedReads.read(rs, new BigDecimal("1E+3"), String.class));
		assertEquals("0.0000001", HealdanTypedReads.read(rs, new BigDecimal("0.0000001"), String.class));
		assertEquals("-12", HealdanTypedReads.read(rs, Integer.valueOf(-12), String.class));
		assertTrue(askedFor.isEmpty());
	}

	/**
	 * A fraction asked for as a whole number, and a number the type can't hold,
	 * go to the driver, which decides as it did before
	 */
	@Test
	public void testAFractionOrAnOverflowGoesToTheDriver() {
		assertAsksTheDriver(new BigDecimal("42.5"), Long.class);
		assertAsksTheDriver(new BigDecimal("0.1"), Integer.class);
		assertAsksTheDriver(Long.valueOf(Integer.MAX_VALUE + 1L), Integer.class);
		assertAsksTheDriver(BigInteger.ONE.shiftLeft(63), Long.class);
		assertEquals(List.of(Long.class, Integer.class, Integer.class, Long.class), askedFor);
	}

	/**
	 * A Double or a Float isn't held exactly, so it goes to the driver, whose
	 * conversions of the server's text it would otherwise second-guess
	 */
	@Test
	public void testFloatingPointGoesToTheDriver() {
		assertAsksTheDriver(Double.valueOf(3D), Long.class);
		assertAsksTheDriver(Float.valueOf(1.1F), Double.class);
		assertAsksTheDriver(Double.valueOf(1E20), String.class);
		assertEquals(List.of(Long.class, Double.class, String.class), askedFor);
	}

	/**
	 * A type it doesn't convert to, a wider type than the value's own among
	 * them, goes to the driver
	 */
	@Test
	public void testOtherTypesGoToTheDriver() {
		assertAsksTheDriver(Integer.valueOf(1), Boolean.class);
		assertAsksTheDriver(Integer.valueOf(1), Short.class);
		assertAsksTheDriver(Long.valueOf(1), Number.class);
		assertEquals(List.of(Boolean.class, Short.class, Number.class), askedFor);
	}

	private void assertAsksTheDriver(Object value, Class<?> type) {
		int asked = askedFor.size();
		try {
			HealdanTypedReads.read(rs, value, type);
			fail("Expected the driver to be asked for " + type.getSimpleName() + " from " + value);
		} catch (SQLException e) {
			assertEquals(REFUSAL, e.getMessage());
		}
		assertEquals(asked + 1, askedFor.size());
	}
}
