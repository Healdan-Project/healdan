package com.logicaldoc.core.searchengine.saved;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.searchengine.FulltextSearchOptions;
import com.logicaldoc.core.searchengine.SearchOptions;
import com.logicaldoc.core.searchengine.TagSearchOptions;
import com.logicaldoc.core.searchengine.folder.FolderCriterion;
import com.logicaldoc.core.searchengine.folder.FolderSearchOptions;

/**
 * Test case for reading and writing the options of a {@link SavedSearch}
 */
public class SavedSearchTest extends AbstractCoreTestCase {

	private static final String MARKER = "healdan.savedsearch.decoded";

	/**
	 * XML that, given to XMLDecoder, would set a system property
	 */
	private static final String PAYLOAD = "<object class=\"java.lang.System\" method=\"setProperty\"><string>" + MARKER
			+ "</string><string>yes</string></object>";

	@After
	public void clearMarker() {
		System.clearProperty(MARKER);
	}

	@Test
	public void testUserTextCannotAddXmlElements() throws PersistenceException {
		// Everything a user types into a search is a string the encoder
		// escapes, so it comes back as text and never as markup
		String hostile = "</string></void>" + PAYLOAD + "<void property=\"name\"><string>]]>&amp;<!-- \u0000";

		FulltextSearchOptions fulltext = new FulltextSearchOptions();
		fulltext.setExpression(hostile);
		fulltext.setFields(new HashSet<>(Set.of(hostile)));
		fulltext.setDateFrom(new Date(0));
		assertEquals(hostile, roundTrip(fulltext).getExpression());

		FolderCriterion criterion = new FolderCriterion();
		criterion.setField(hostile + " type:" + Attribute.TYPE_STRING);
		criterion.setOperator(FolderCriterion.OPERATOR_EQUALS);
		criterion.setValue(hostile);
		FolderSearchOptions folder = new FolderSearchOptions();
		folder.setCriteria(new ArrayList<>(List.of(criterion)));
		folder.setFilterIds(new HashSet<>(Set.of(4L)));
		FolderCriterion read = ((FolderSearchOptions) roundTrip(folder)).getCriteria().get(0);
		assertEquals(hostile, read.getFieldName());
		assertEquals(hostile, read.getStringValue());

		TagSearchOptions tag = new TagSearchOptions();
		tag.setExpression(hostile);
		assertEquals(hostile, roundTrip(tag).getExpression());

		assertNull(System.getProperty(MARKER));
	}

	@Test
	public void testReadOptionsRejectsOtherClasses() {
		// What a stored row would hold if something other than saveOptions()
		// wrote it, such as SQL injection or a database restore
		SavedSearch search = new SavedSearch();
		search.setOptions(
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?><java version=\"21\" class=\"java.beans.XMLDecoder\">"
						+ PAYLOAD + "</java>");

		assertThrows(IllegalArgumentException.class, search::readOptions);
		assertNull(System.getProperty(MARKER));
	}

	private SearchOptions roundTrip(SearchOptions options) throws PersistenceException {
		options.setName("test");
		SavedSearch search = new SavedSearch();
		search.setTenantId(1L);
		search.setName("test");
		search.saveOptions(options);

		SavedSearch stored = new SavedSearch();
		stored.setName("test");
		stored.setOptions(search.getOptions());
		return stored.readOptions();
	}
}
