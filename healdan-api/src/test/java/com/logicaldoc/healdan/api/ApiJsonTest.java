// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Date;

import org.junit.Test;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.logicaldoc.healdan.api.model.FavoriteRename;
import com.logicaldoc.healdan.api.model.PropertyPatch;

/**
 * The JSON conventions of spec sections 4.3 to 4.5.
 */
public class ApiJsonTest {

	public record Stamp(String name, Date when) {
	}

	@Test
	public void testDatesAreUtcWithZ() throws Exception {
		Date date = Date.from(Instant.parse("2026-10-23T14:05:00.250Z"));

		assertEquals("{\"name\":\"a\",\"when\":\"2026-10-23T14:05:00Z\"}",
				ApiJson.mapper().writeValueAsString(new Stamp("a", date)));
		assertEquals(Instant.parse("2026-10-23T14:05:00Z"),
				ApiJson.mapper().readValue("{\"name\":\"a\",\"when\":\"2026-10-23T14:05:00Z\"}", Stamp.class).when()
						.toInstant());
		assertEquals(Instant.parse("2026-10-23T12:05:00Z"),
				ApiJson.mapper().readValue("{\"when\":\"2026-10-23T14:05:00+02:00\"}", Stamp.class).when().toInstant());
	}

	@Test
	public void testSqlDatesAndTimestamps() throws Exception {
		long millis = Instant.parse("2026-10-23T14:05:00.250Z").toEpochMilli();

		// JDBC hands these over for date and timestamp columns
		assertEquals("{\"name\":\"d\",\"when\":\"2026-10-23T14:05:00Z\"}",
				ApiJson.mapper().writeValueAsString(new Stamp("d", new java.sql.Date(millis))));
		assertEquals("{\"name\":\"t\",\"when\":\"2026-10-23T14:05:00Z\"}",
				ApiJson.mapper().writeValueAsString(new Stamp("t", new java.sql.Timestamp(millis))));
		assertEquals("{\"name\":\"h\",\"when\":\"2026-10-23T14:05:00Z\"}",
				ApiJson.mapper().writeValueAsString(new Stamp("h", new java.sql.Time(millis))));
	}

	@Test
	public void testNullsAreWritten() throws Exception {
		assertEquals("{\"name\":null,\"when\":null}", ApiJson.mapper().writeValueAsString(new Stamp(null, null)));
	}

	@Test(expected = UnrecognizedPropertyException.class)
	public void testUnknownFieldsFail() throws Exception {
		ApiJson.mapper().readValue("{\"name\":\"a\",\"colour\":\"red\"}", Stamp.class);
	}

	/** PostgreSQL spec section 7.5: text with a NUL or a lone surrogate is refused as it's read, in a tree too */
	@Test
	public void testTextHealdanCantKeepIsRefused() throws Exception {
		for (String json : new String[] { "{\"name\":\"a\\u0000b\"}", "{\"name\":\"a\\ud83c\"}" })
			assertEquals(json, "The field name has a character Healdan can't keep.", ProblemMapper.describe(
					assertThrows(json, ApiJson.UnkeptText.class, () -> ApiJson.mapper().readValue(json, Stamp.class))));
		// In a tree: its text, at any depth, and its field names
		for (String json : new String[] { "{\"values\":{\"p\":\"a\\u0000b\"}}",
				"{\"values\":{\"p\":[\"ok\",{\"q\":\"\\udf89\"}]}}", "{\"values\":{\"p\":{\"a\\u0000\":1}}}" })
			assertEquals(json, "The field p has a character Healdan can't keep.", ProblemMapper.describe(assertThrows(
					json, ApiJson.UnkeptText.class, () -> ApiJson.mapper().readValue(json, PropertyPatch.class))));
		// Text not in a field
		assertEquals("The request has a character Healdan can't keep.", ProblemMapper.describe(assertThrows(
				ApiJson.UnkeptText.class, () -> ApiJson.mapper().readValue("[\"a\\u0000\"]", String[].class))));

		// An emoji is read as it is
		assertEquals("Party \uD83C\uDF89",
				ApiJson.mapper().readValue("{\"name\":\"Party \\ud83c\\udf89\"}", Stamp.class).name());
		assertEquals("Party \uD83C\uDF89", ApiJson.mapper()
				.readValue("{\"values\":{\"p\":\"Party \\ud83c\\udf89\"}}", PropertyPatch.class).values().get("p")
				.textValue());
	}

	/** A tree's null and a missing one read as Jackson reads them: a null node, and nothing */
	@Test
	public void testATreesNullAndAbsence() throws Exception {
		assertTrue(ApiJson.mapper().readValue("{\"name\":null}", FavoriteRename.class).name().isNull());
		assertNull(ApiJson.mapper().readValue("{}", FavoriteRename.class).name());
		assertTrue(ApiJson.mapper().readValue("{\"values\":{\"p\":null}}", PropertyPatch.class).values().get("p")
				.isNull());
	}

	@Test
	public void testProviderUsesTheMapper() {
		assertSame(ApiJson.mapper(), ApiJson.provider().locateMapper(Stamp.class, null));
	}
}
