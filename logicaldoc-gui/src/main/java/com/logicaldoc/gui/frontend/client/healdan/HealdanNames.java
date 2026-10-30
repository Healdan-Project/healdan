package com.logicaldoc.gui.frontend.client.healdan;

import com.google.gwt.regexp.shared.RegExp;
import com.smartgwt.client.widgets.form.fields.FormItem;
import com.smartgwt.client.widgets.form.validator.CustomValidator;
import com.smartgwt.client.widgets.form.validator.RegExpValidator;

/**
 * LogicalDOC's forms take only letters and digits in the name of a template or
 * attribute set, and check it at every save. The Healdan profile's are both
 * "Healdan profile", so they could never be saved. This lets a name field keep
 * the name it has, while a new name still has to pass LogicalDOC's rule.
 */
public final class HealdanNames {

	private HealdanNames() {
	}

	/**
	 * Makes a name field accept the name it has, as well as what LogicalDOC's
	 * rule accepts
	 *
	 * @param item the name field
	 * @param current the name the template or attribute set has
	 * @param rule the validator LogicalDOC gives the field; its expression and
	 *        message are kept
	 */
	public static void keepCurrent(FormItem item, String current, RegExpValidator rule) {
		String expression = rule.getExpression();
		CustomValidator validator = new CustomValidator() {
			@Override
			protected boolean condition(Object value) {
				return accepts(value != null ? value.toString() : null, current, expression);
			}
		};
		validator.setErrorMessage(rule.getAttributeAsString("errorMessage"));
		item.setValidators(validator);
	}

	/**
	 * @param value the name in the field
	 * @param current the name the template or attribute set has
	 * @param expression LogicalDOC's rule
	 * @return true if the name isn't empty and is the current one, or passes
	 *         the rule
	 */
	static boolean accepts(String value, String current, String expression) {
		if (value == null || value.isEmpty())
			return false;
		return value.equals(current) || RegExp.compile(expression).test(value);
	}
}
