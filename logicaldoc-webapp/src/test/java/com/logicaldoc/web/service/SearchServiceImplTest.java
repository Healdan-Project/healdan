package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.searchengine.FulltextSearchOptions;
import com.logicaldoc.core.searchengine.SearchOptions;
import com.logicaldoc.core.searchengine.TagSearchOptions;
import com.logicaldoc.core.searchengine.folder.FolderCriterion;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUICriterion;
import com.logicaldoc.gui.common.client.beans.GUIDocument;
import com.logicaldoc.gui.common.client.beans.GUIResult;
import com.logicaldoc.gui.common.client.beans.GUISearchOptions;
import com.logicaldoc.util.io.ResourceUtil;
import com.logicaldoc.web.AbstractFulltextTestCase;

public class SearchServiceImplTest extends AbstractFulltextTestCase {

	/** A search type that registers no options converter: CE's tag search under another number */
	private static final int UNCONVERTED_TYPE = 4242;

	/**
	 * A full-text type that registers no options converter: CE's full-text
	 * search under the number of Healdan's documents-only search
	 */
	private static final int UNCONVERTED_FULLTEXT_TYPE = 104;

	// Instance under test
	private SearchServiceImpl testSubject = new SearchServiceImpl();

	@Override
	protected List<String> getPluginArchives() {
		List<String> archives = new ArrayList<>(super.getPluginArchives());
		archives.add(unconvertedSearchPlugin());
		return archives;
	}

	@Test
	public void testUnregisteredTypeUsesDefaultConverter() throws ServerException {
		// One default for every unregistered type
		assertSame(SearchServiceImpl.converterFor(UNCONVERTED_TYPE), SearchServiceImpl.converterFor(-7));
		assertSame(SearchServiceImpl.converterFor(UNCONVERTED_TYPE),
				SearchServiceImpl.converterFor(UNCONVERTED_FULLTEXT_TYPE));

		GUISearchOptions options = new GUISearchOptions();
		options.setExpression("abc");
		options.setType(UNCONVERTED_TYPE);
		SearchOptions converted = SearchServiceImpl.converterFor(UNCONVERTED_TYPE).toSearchOptions(options);
		assertEquals(TagSearchOptions.class, converted.getClass());
		assertEquals(UNCONVERTED_TYPE, converted.getType());
		assertEquals("abc", converted.getExpression());

		// The search runs, with the expression and the user carried over
		options.setType(SearchOptions.TYPE_TAG);
		List<Long> tagged = hitIds(testSubject.search(options));
		assertFalse(tagged.isEmpty());
		options.setType(UNCONVERTED_TYPE);
		assertEquals(tagged, hitIds(testSubject.search(options)));
	}

	@Test
	public void testUnregisteredFulltextTypeKeepsTheFulltextOptions() throws ServerException {
		GUISearchOptions options = new GUISearchOptions();
		options.setType(UNCONVERTED_FULLTEXT_TYPE);
		options.setExpression("document");
		options.setFields(List.of("content", "fileName"));
		options.setExpressionLanguage("it");
		options.setDateFrom(new Date(1));

		SearchOptions converted = SearchServiceImpl.converterFor(UNCONVERTED_FULLTEXT_TYPE).toSearchOptions(options);
		assertEquals(FulltextSearchOptions.class, converted.getClass());
		FulltextSearchOptions fulltext = (FulltextSearchOptions) converted;
		assertEquals(UNCONVERTED_FULLTEXT_TYPE, fulltext.getType());
		assertEquals("document", fulltext.getExpression());
		assertEquals(Set.of("content", "fileName"), fulltext.getFields());
		assertEquals("it", fulltext.getExpressionLanguage());
		assertEquals(new Date(1), fulltext.getDateFrom());

		// The search finds what CE's full-text search finds with the same options
		options.setExpressionLanguage("en");
		options.setType(SearchOptions.TYPE_FULLTEXT);
		List<Long> found = hitIds(testSubject.search(options));
		assertFalse(found.isEmpty());
		options.setType(UNCONVERTED_FULLTEXT_TYPE);
		assertEquals(found, hitIds(testSubject.search(options)));
	}

	private static List<Long> hitIds(GUIResult result) {
		return result.getHits().stream().map(GUIDocument::getId).toList();
	}

	/**
	 * Builds, in the test classpath, a plugin that registers CE's tag search as
	 * {@link #UNCONVERTED_TYPE} and CE's full-text search as
	 * {@link #UNCONVERTED_FULLTEXT_TYPE}
	 *
	 * @return the jar's classpath path
	 */
	private static String unconvertedSearchPlugin() {
		try {
			String dummyXml;
			try (JarInputStream in = new JarInputStream(ResourceUtil.getInputStream("logicaldoc-dummy-plugin.jar"))) {
				while (!"plugin.xml".equals(in.getNextJarEntry().getName()))
					in.closeEntry();
				dummyXml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			// The manifest parser validates against the DTD at the top
			String xml = dummyXml.substring(0, dummyXml.indexOf("<plugin id=")) + """
					<plugin id="unconverted-search" version="9.3.1">
					  <requires><import plugin-id="logicaldoc-core" /></requires>
					  <extension plugin-id="logicaldoc-core" point-id="Search" id="unconvertedSearch">
					    <parameter id="type" value="%d" />
					    <parameter id="class" value="com.logicaldoc.core.searchengine.TagSearch" />
					    <parameter id="options" value="com.logicaldoc.core.searchengine.TagSearchOptions" />
					  </extension>
					  <extension plugin-id="logicaldoc-core" point-id="Search" id="unconvertedFulltextSearch">
					    <parameter id="type" value="%d" />
					    <parameter id="class" value="com.logicaldoc.core.searchengine.FulltextSearch" />
					    <parameter id="options" value="com.logicaldoc.core.searchengine.FulltextSearchOptions" />
					  </extension>
					</plugin>
					""".formatted(UNCONVERTED_TYPE, UNCONVERTED_FULLTEXT_TYPE);
			try (JarOutputStream out = new JarOutputStream(
					new FileOutputStream(new File("target/test-classes/unconverted-search-plugin.jar")))) {
				out.putNextEntry(new JarEntry("plugin.xml"));
				out.write(xml.getBytes(StandardCharsets.UTF_8));
			}
			return "/unconverted-search-plugin.jar";
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	public void testSearch() throws ServerException {
		GUISearchOptions options = new GUISearchOptions();
		options.setUserId(1L);
		options.setLanguage("en");
		options.setExpression("document");
		options.setExpressionLanguage("en_UK");
		options.setType(SearchOptions.TYPE_FULLTEXT);
		options.setDateFrom(new Date(1));
		options.setTopOperator("and");

		GUIResult result = testSubject.search(options);
		assertEquals(1, result.getHits().size());

		options.setExpression("unexisting");
		result = testSubject.search(options);
		assertTrue(result.getHits().isEmpty());

		options.setExpression(null);
		options.setType(SearchOptions.TYPE_FOLDERS);
		GUICriterion crit = new GUICriterion();
		crit.setField("name");
		crit.setStringValue("folder");
		crit.setOperator(FolderCriterion.OPERATOR_CONTAINS);
		options.getCriteria().add(crit);
		
		crit = new GUICriterion();
		crit.setField("id");
		crit.setLongValue(-1000L);
		crit.setOperator(FolderCriterion.OPERATOR_GREATER);
		
		crit = new GUICriterion();
		crit.setField("creation");
		crit.setDateValue(new Date(1));
		crit.setOperator(FolderCriterion.OPERATOR_GREATER);
		options.getCriteria().add(crit);
		
		result = testSubject.search(options);
		assertEquals(3, result.getHits().size());
	}

	@Test
	public void testSaveAndLoadOfUnregisteredType() throws ServerException, PersistenceException {
		GUISearchOptions options = new GUISearchOptions();
		options.setUserId(1L);
		options.setLanguage("en");
		options.setExpression("document");
		options.setType(UNCONVERTED_FULLTEXT_TYPE);
		options.setName("unconverted");

		testSubject.save(options);

		GUISearchOptions loaded = testSubject.load("unconverted");
		assertNotNull(loaded);
		assertEquals("document", loaded.getExpression());
	}

	@Test
	public void testCRUD() throws ServerException, PersistenceException {
		GUISearchOptions options = new GUISearchOptions();
		options.setUserId(1L);
		options.setLanguage("en");
		options.setExpression("document");
		options.setType(SearchOptions.TYPE_FULLTEXT);
		options.setName("test");

		testSubject.save(options);

		GUISearchOptions loaded = testSubject.load("test");
		assertNotNull(loaded);
		assertEquals("document", options.getExpression());
		assertEquals("test", options.getName());

		assertEquals(1, SearchServiceImpl.getSearches(session).size());

		options = new GUISearchOptions();
		options.setUserId(1L);
		options.setLanguage("en");
		options.setExpression("folder");
		options.setType(SearchOptions.TYPE_FOLDERS);
		options.setName("test2");
		GUICriterion crit = new GUICriterion();
		crit.setField("name");
		crit.setStringValue("folder");
		crit.setOperator(FolderCriterion.OPERATOR_CONTAINS);
		options.getCriteria().add(crit);
		crit = new GUICriterion();
		crit.setField("id");
		crit.setLongValue(1L);
		crit.setOperator(FolderCriterion.OPERATOR_GREATER);
		
		testSubject.save(options);

		loaded = testSubject.load("test2");
		assertNotNull(loaded);

		assertEquals(2, SearchServiceImpl.getSearches(session).size());

		testSubject.delete(List.of("test"));
		try {
			assertNotNull(testSubject.load("test"));
			fail("No error if loading a deleted search");
		} catch (ServerException se) {
			// All ok
		}
	}

	@Test
	public void testShareSearch() throws ServerException, PersistenceException {
		GUISearchOptions options = new GUISearchOptions();
		options.setUserId(1L);
		options.setLanguage("en");
		options.setExpression("document");
		options.setType(SearchOptions.TYPE_FULLTEXT);
		options.setName("test");

		testSubject.save(options);
		assertEquals(1, searchDao.findAll(Tenant.DEFAULT_ID).size());

		testSubject.shareSearch("test", List.of(3L, 4L), List.of(2L, 3L));
		assertEquals(4, searchDao.findAll(Tenant.DEFAULT_ID).size());
	}

	@Test
	public void testShareSearchOnlyWithinTenant() throws ServerException, PersistenceException {
		// User "test" and group testGroup move to another tenant
		UserDAO.get().jdbcUpdate("update ld_user set ld_tenantid = 2 where ld_id = 5");
		GroupDAO.get().jdbcUpdate("update ld_group set ld_tenantid = 2 where ld_id = 10");

		GUISearchOptions options = new GUISearchOptions();
		options.setUserId(1L);
		options.setExpression("document");
		options.setType(SearchOptions.TYPE_FULLTEXT);
		options.setName("test");
		testSubject.save(options);

		assertThrows(ServerException.class, () -> testSubject.shareSearch("test", List.of(3L, 5L), List.of()));
		assertThrows(ServerException.class, () -> testSubject.shareSearch("test", List.of(3L), List.of(10L)));
		assertThrows(ServerException.class, () -> testSubject.shareSearch("test", List.of(3L, 9999L), List.of()));

		// Nobody got it, not even the user in the tenant
		assertNull(searchDao.findByUserIdAndName(3L, "test"));
		assertNull(searchDao.findByUserIdAndName(5L, "test"));
		assertNull(searchDao.findByUserIdAndName(9999L, "test"));
	}
}