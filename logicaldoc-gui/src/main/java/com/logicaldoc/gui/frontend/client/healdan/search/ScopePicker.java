package com.logicaldoc.gui.frontend.client.healdan.search;

import java.util.LinkedHashMap;

import com.google.gwt.user.client.Cookies;
import com.logicaldoc.gui.frontend.client.healdan.HealdanLabels;
import com.smartgwt.client.widgets.form.fields.SelectItem;

/**
 * The picker left of the search box (spec 8): what the box searches. It
 * remembers the last choice in a session cookie.
 */
public class ScopePicker extends SelectItem {

	/** Documents and emails: CE's own search flow */
	public static final String DOCS_EMAILS = "docsemails";

	public static final String DOCUMENTS = "documents";

	public static final String EMAILS = "emails";

	public static final String FOLDERS = "folders";

	public static final String MATTERS = "matters";

	public static final String CLIENTS = "clients";

	private static final String COOKIE = "healdan.scope";

	public ScopePicker() {
		super("scope");
		setShowTitle(false);
		setWidth(120);
		setAllowEmptyValue(false);
		refreshTitles();
		setValue(remembered());
		addChangedHandler(event -> remember(getValueAsString()));
	}

	/**
	 * Sets the titles again, for when the matter label has loaded
	 */
	public final void refreshTitles() {
		LinkedHashMap<String, String> valueMap = new LinkedHashMap<>();
		valueMap.put(DOCUMENTS, "Documents");
		valueMap.put(EMAILS, "Emails");
		valueMap.put(DOCS_EMAILS, "Docs & Emails");
		valueMap.put(FOLDERS, "Folders");
		valueMap.put(MATTERS, HealdanLabels.plural());
		valueMap.put(CLIENTS, "Clients");
		setValueMap(valueMap);
	}

	private static String remembered() {
		String value = null;
		try {
			value = Cookies.getCookie(COOKIE);
		} catch (Exception e) {
			// Cookies unavailable: use the default
		}
		if (DOCUMENTS.equals(value) || EMAILS.equals(value) || FOLDERS.equals(value) || MATTERS.equals(value)
				|| CLIENTS.equals(value))
			return value;
		return DOCS_EMAILS;
	}

	private static void remember(String value) {
		try {
			Cookies.setCookie(COOKIE, value);
		} catch (Exception e) {
			// Cookies unavailable: the choice just isn't kept
		}
	}
}
