package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.time.Instant;
import java.util.Date;

import org.junit.Test;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

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
	public void testNullsAreWritten() throws Exception {
		assertEquals("{\"name\":null,\"when\":null}", ApiJson.mapper().writeValueAsString(new Stamp(null, null)));
	}

	@Test(expected = UnrecognizedPropertyException.class)
	public void testUnknownFieldsFail() throws Exception {
		ApiJson.mapper().readValue("{\"name\":\"a\",\"colour\":\"red\"}", Stamp.class);
	}

	@Test
	public void testProviderUsesTheMapper() {
		assertSame(ApiJson.mapper(), ApiJson.provider().locateMapper(Stamp.class, null));
	}
}
