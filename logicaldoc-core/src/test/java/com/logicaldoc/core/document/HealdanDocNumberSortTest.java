package com.logicaldoc.core.document;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Healdan: {@link DocumentComparator} sorts custom ids through
 * {@link HealdanDocNumberSort}, whatever form of the sort a caller sends
 */
public class HealdanDocNumberSortTest {

	@Test
	public void testNumbersSortByValueThenOtherIds() {
		// SOAP and REST send the field as it is, the documents grid's servlet
		// wraps it in lower(). An id of 19 digits isn't a number, so it sorts
		// with the text.
		for (String field : List.of("customId", "lower(customId)")) {
			assertEquals(field, Arrays.asList("1", "2", "007", "10", null, "1234567890123456789", "abc", "B"),
					sorted(field + " asc", "10", "B", "2", null, "1234567890123456789", "abc", "007", "1"));
			assertEquals(field, Arrays.asList("B", "abc", "1234567890123456789", null, "10", "007", "2", "1"),
					sorted(field + " desc", "10", "B", "2", null, "1234567890123456789", "abc", "007", "1"));
		}
	}

	@Test
	public void testNumberIsOneToEighteenDigits() {
		assertEquals(true, HealdanDocNumberSort.isNumber("0"));
		assertEquals(true, HealdanDocNumberSort.isNumber("999999999999999999"));
		assertEquals(false, HealdanDocNumberSort.isNumber("1000000000000000000"));
		assertEquals(false, HealdanDocNumberSort.isNumber(""));
		assertEquals(false, HealdanDocNumberSort.isNumber(null));
		assertEquals(false, HealdanDocNumberSort.isNumber("-1"));
		assertEquals(false, HealdanDocNumberSort.isNumber("1 "));
	}

	private static List<String> sorted(String sort, String... customIds) {
		List<Document> docs = new ArrayList<>();
		for (String customId : customIds) {
			Document doc = new Document();
			doc.setCustomId(customId);
			docs.add(doc);
		}
		docs.sort(DocumentComparator.getComparator(sort));
		return docs.stream().map(Document::getCustomId).toList();
	}
}
