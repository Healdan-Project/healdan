package com.logicaldoc.gui.frontend.client.healdan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rule {@link HealdanNames#keepCurrent} gives a template's or attribute
 * set's name field: the name it has, or a name LogicalDOC's rule accepts.
 */
public class HealdanNamesTest {

	/** LogicalDOC's rule, from TemplateNameTextValidator and SimpleTextValidator */
	private static final String RULE = "^([a-zA-Z0-9]+)$";

	private static final String PROFILE = "Healdan profile";

	@Test
	public void testKeepsTheCurrentName() {
		assertTrue(HealdanNames.accepts(PROFILE, PROFILE, RULE));
	}

	@Test
	public void testRefusesANewNameTheRuleRefuses() {
		assertFalse(HealdanNames.accepts("Healdan profile 2", PROFILE, RULE));
		assertFalse(HealdanNames.accepts("Other name", "Other", RULE));
		assertFalse(HealdanNames.accepts("healdan profile", PROFILE, RULE));
	}

	@Test
	public void testAcceptsANewNameTheRuleAccepts() {
		assertTrue(HealdanNames.accepts("HealdanProfile", PROFILE, RULE));
		assertTrue(HealdanNames.accepts("Invoices2026", null, RULE));
	}

	@Test
	public void testRefusesNoName() {
		assertFalse(HealdanNames.accepts(null, PROFILE, RULE));
		assertFalse(HealdanNames.accepts("", PROFILE, RULE));
		assertFalse(HealdanNames.accepts(null, null, RULE));
		assertFalse(HealdanNames.accepts("", "", RULE));
	}
}
