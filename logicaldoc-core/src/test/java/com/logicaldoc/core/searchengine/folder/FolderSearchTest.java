package com.logicaldoc.core.searchengine.folder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.SearchException;

public class FolderSearchTest extends AbstractCoreTestCase {

	private static final Logger log = LoggerFactory.getLogger(FolderSearchTest.class);

	@Test
	public void testSearch() {
		List<FolderCriterion> criteria = new ArrayList<>();

		FolderCriterion criterion = new FolderCriterion();
		criterion.setComposition("and");
		criterion.setOperator(FolderCriterion.OPERATOR_CONTAINS);
		criterion.setField("name");
		criterion.setValue("ABC");
		criteria.add(criterion);

		FolderSearchOptions opt = new FolderSearchOptions();
		opt.setCriteria(new ArrayList<>());
		opt.setMaxHits(10);
		opt.setUserId(1);
		opt.setRetrieveAliases(true);
		opt.setCriteria(criteria);
		opt.setOrder(Arrays.asList(new String[] { "lastmodified desc", "name asc" }));

		FolderSearch search = new FolderSearch();
		search.setOptions(opt);
		try {
			search.search();
		} catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage(), e);
		}

		List<Hit> results = search.getHits();
		assertEquals(1, results.size());

		assertEquals(1201, results.get(0).getId());
		assertEquals(1200, results.get(0).getFolder().getId());

		criteria.clear();
		criterion = new FolderCriterion();
		criterion.setComposition("and");
		criterion.setExtendedAttribute(true);
		criterion.setOperator(FolderCriterion.OPERATOR_EQUALS);
		criterion.setField("val1");
		criterion.setValue("test_val_1");

		criterion = new FolderCriterion();
		criterion.setComposition("and");
		criterion.setOperator(FolderCriterion.OPERATOR_EQUALS);
		criterion.setField("template");
		criterion.setValue(1L);
		criterion.setType(FolderCriterion.TYPE_TEMPLATE);
		criteria.add(criterion);

		opt = new FolderSearchOptions();
		opt.setCriteria(new ArrayList<>());
		opt.setMaxHits(10);
		opt.setUserId(1);
		opt.setRetrieveAliases(true);
		opt.setCriteria(criteria);
		opt.setOrder(Arrays.asList(new String[] { "lastmodified desc", "name asc" }));

		search = new FolderSearch();
		search.setOptions(opt);
		try {
			search.search();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		results = search.getHits();
		assertEquals(2, results.size());
		assertEquals(1201L, results.get(0).getId());
	}

	@Test
	public void testSearchByExtendedAttribute() throws SearchException {
		FolderCriterion criterion = new FolderCriterion();
		criterion.setField("val1 type:" + Attribute.Type.STRING.ordinal());
		criterion.setOperator(FolderCriterion.OPERATOR_EQUALS);
		criterion.setValue("test_val_1");

		List<Hit> results = search(criterion);
		assertEquals(1, results.size());
		assertEquals(1202L, results.get(0).getId());
	}

	@Test
	public void testExtendedAttributeNameIsNotInjectable() throws SearchException {
		// No attribute has this name, but concatenated into the SQL it would
		// match any attribute holding the value
		FolderCriterion criterion = new FolderCriterion();
		criterion.setField("x' or 1=1 or 'x'=' type:" + Attribute.Type.STRING.ordinal());
		criterion.setOperator(FolderCriterion.OPERATOR_EQUALS);
		criterion.setValue("test_val_1");

		assertTrue(search(criterion).isEmpty());
	}

	@Test
	public void testFieldNameIsNotInjectable() {
		// Concatenated into the SQL this would match every folder
		FolderCriterion criterion = new FolderCriterion();
		criterion.setField("name is not null or ld_tgs");
		criterion.setOperator(FolderCriterion.OPERATOR_EQUALS);
		criterion.setValue("zzz");

		assertThrows(SearchException.class, () -> search(criterion));
	}

	@Test
	public void testLanguageValueIsNotInjectable() throws SearchException {
		// Concatenated into the SQL this value would match every folder
		FolderCriterion criterion = new FolderCriterion();
		criterion.setField("val1 type:" + FolderCriterion.TYPE_LANGUAGE);
		criterion.setOperator(FolderCriterion.OPERATOR_EQUALS);
		criterion.setValue("x' or 'a'='a");

		assertTrue(search(criterion).isEmpty());
	}

	@Test
	public void testStringValueIsBoundAsParameter() throws SearchException {
		// Doubling the quotes doesn't protect a quoted value on MariaDB and
		// MySQL, where this backslash would escape the closing quote. HSQLDB
		// can't show that, so check that the value never reaches the SQL text.
		String value = "zzz\\' or 1=1 -- ";
		for (String operator : List.of(FolderCriterion.OPERATOR_EQUALS, FolderCriterion.OPERATOR_NOTEQUAL,
				FolderCriterion.OPERATOR_CONTAINS, FolderCriterion.OPERATOR_NOTCONTAINS,
				FolderCriterion.OPERATOR_BEGINSWITH, FolderCriterion.OPERATOR_ENDSWITH))
			for (boolean caseSensitive : List.of(true, false)) {
				FolderCriterion criterion = new FolderCriterion();
				criterion.setField("name");
				criterion.setOperator(operator);
				criterion.setValue(value);

				FolderSearchOptions opt = newOptions(criterion);
				opt.setCaseSensitive(caseSensitive);
				search(opt);

				assertTrue(operator + " caseSensitive=" + caseSensitive, opt.getParameters().values().stream()
						.anyMatch(param -> param.toString().contains(value)));
			}
	}

	private List<Hit> search(FolderCriterion criterion) throws SearchException {
		return search(newOptions(criterion));
	}

	private List<Hit> search(FolderSearchOptions opt) throws SearchException {
		FolderSearch search = new FolderSearch();
		search.setOptions(opt);
		return search.search();
	}

	private FolderSearchOptions newOptions(FolderCriterion criterion) {
		FolderSearchOptions opt = new FolderSearchOptions();
		opt.setMaxHits(100);
		opt.setUserId(1);
		opt.setCriteria(List.of(criterion));
		return opt;
	}
}