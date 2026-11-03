// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.common.client.healdan;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.smartgwt.client.widgets.form.fields.ComboBoxItem;
import com.smartgwt.client.widgets.form.fields.MultiComboBoxItem;
import com.smartgwt.client.widgets.grid.ListGridField;

/**
 * Shows what people type, such as a group's name or description, as text, not
 * HTML, where LogicalDOC's screens write it into HTML: cell formatters, item
 * formatters, and a multi-combo's buttons and choices.
 */
public final class HealdanText {

	private HealdanText() {
	}

	/**
	 * A value as text in HTML. LogicalDOC's {@code Util.escapeHTML} escapes only
	 * {@code <} and {@code >}, so an {@code &} would still be read as the start
	 * of an entity.
	 *
	 * @param value the value, of any type
	 *
	 * @return the value with every HTML special character escaped, or an empty
	 *         string for null
	 */
	public static String escape(Object value) {
		return value == null ? "" : SafeHtmlUtils.htmlEscape(value.toString());
	}

	/**
	 * Shows a multi-combo's chosen values and its choices as text. Each chosen
	 * value is a button titled with the item's display value, which SmartGWT
	 * writes as HTML; the item's value formatter gets that display value, so the
	 * formatter set here escapes it.
	 *
	 * @param item the multi-combo
	 * @param displayField the option data source's field the item displays
	 */
	public static void showAsText(MultiComboBoxItem item, String displayField) {
		item.setValueFormatter((value, rec, form, formItem) -> escape(value));
		showChoicesAsText(item, displayField);
	}

	/**
	 * Shows a multi-combo's choices, its combo box's pick list, as text
	 *
	 * @param item the multi-combo
	 * @param displayField the option data source's field the item displays
	 */
	public static void showChoicesAsText(MultiComboBoxItem item, String displayField) {
		ListGridField choice = new ListGridField(displayField);
		choice.setEscapeHTML(true);
		ComboBoxItem comboBox = new ComboBoxItem();
		comboBox.setPickListFields(choice);
		item.setComboBoxProperties(comboBox);
	}
}
