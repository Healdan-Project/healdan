package com.logicaldoc.healdan.api;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider;

/**
 * The API's JSON conventions (spec sections 4.3 to 4.5): camelCase names as
 * the Java ones, {@code null} written rather than left out, timestamps as ISO
 * 8601 in UTC ({@code 2026-10-23T14:05:00Z}), and unknown request fields
 * refused.
 */
public final class ApiJson {

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.addModule(new SimpleModule("healdan-dates").addSerializer(Date.class, new UtcDateSerializer())
					.addDeserializer(Date.class, new UtcDateDeserializer()))
			.build();

	private ApiJson() {
	}

	/**
	 * @return the configured mapper, shared: don't change its configuration
	 */
	public static ObjectMapper mapper() {
		return MAPPER;
	}

	/**
	 * @return a JAX-RS provider that reads and writes JSON with
	 *         {@link #mapper()}
	 */
	public static JacksonJsonProvider provider() {
		return new JacksonJsonProvider(MAPPER);
	}

	/**
	 * Writes a date in UTC, to the second, with a {@code Z}. It takes the
	 * milliseconds, because {@code java.sql.Date} and {@code java.sql.Time},
	 * which JDBC hands over, can't give an instant.
	 */
	private static class UtcDateSerializer extends JsonSerializer<Date> {

		@Override
		public void serialize(Date value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
			generator.writeString(DateTimeFormatter.ISO_INSTANT
					.format(Instant.ofEpochMilli(value.getTime()).truncatedTo(ChronoUnit.SECONDS)));
		}
	}

	/**
	 * Reads an ISO 8601 timestamp with a {@code Z} or an offset.
	 */
	private static class UtcDateDeserializer extends JsonDeserializer<Date> {

		@Override
		public Date deserialize(JsonParser parser, DeserializationContext context) throws IOException {
			String text = parser.getValueAsString();
			if (text == null || text.isBlank())
				return null;
			try {
				return Date.from(Instant.parse(text.trim()));
			} catch (DateTimeException e) {
				return (Date) context.handleWeirdStringValue(Date.class, text,
						"expected an ISO 8601 timestamp such as 2026-10-23T14:05:00Z");
			}
		}
	}
}
