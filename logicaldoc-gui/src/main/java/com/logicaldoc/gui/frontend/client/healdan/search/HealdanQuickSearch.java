package com.logicaldoc.gui.frontend.client.healdan.search;

import java.util.Arrays;
import java.util.List;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.logicaldoc.gui.common.client.Constants;
import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.Session;
import com.logicaldoc.gui.common.client.beans.GUICriterion;
import com.logicaldoc.gui.common.client.beans.GUISearchOptions;
import com.logicaldoc.gui.common.client.i18n.I18N;
import com.logicaldoc.gui.frontend.client.document.DocumentsPanel;
import com.logicaldoc.gui.frontend.client.document.grid.DocumentGridUtil;
import com.logicaldoc.gui.frontend.client.document.grid.DocumentsListGrid;
import com.logicaldoc.gui.frontend.client.healdan.HealdanDocumentOpener;
import com.logicaldoc.gui.frontend.client.healdan.HealdanGuiConstants;
import com.logicaldoc.gui.frontend.client.healdan.HealdanLabels;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIDocNumberResult;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIMatter;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanLookupService;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanMatterService;
import com.logicaldoc.gui.frontend.client.search.HitsListPanel;
import com.logicaldoc.gui.frontend.client.search.Search;
import com.logicaldoc.gui.frontend.client.search.SearchObserver;
import com.logicaldoc.gui.frontend.client.search.SearchPanel;
import com.smartgwt.client.util.SC;
import com.smartgwt.client.widgets.grid.ListGridField;

/**
 * What the search box does for each scope of the {@link ScopePicker} (spec 8)
 */
public final class HealdanQuickSearch {

	private static final String CLIENT_FIELD = "ext_clientname";

	private static boolean observing = false;

	private HealdanQuickSearch() {
	}

	/**
	 * Starts what the search box needs at startup: it watches searches to show
	 * the Client column, and the matter label loads
	 *
	 * @param then what to run when the label has loaded; may be null
	 */
	public static void init(Runnable then) {
		if (!observing) {
			observing = true;
			Search.get().addObserver(new ClientColumnObserver());
		}
		HealdanDocumentOpener.register();
		HealdanLabels.load(then);
	}

	/**
	 * Runs the search the scope asks for
	 *
	 * @param scope the {@link ScopePicker} value
	 * @param text what is in the box
	 *
	 * @return false if CE's own flow should handle it (Docs and Emails)
	 */
	public static boolean handle(String scope, String text) {
		if (scope == null || ScopePicker.DOCS_EMAILS.equals(scope))
			return false;

		String value = text == null ? "" : text.trim();
		// CE's box holds a placeholder until it is clicked
		if ((I18N.message("search") + "...").equals(value))
			value = "";

		switch (scope) {
		case ScopePicker.DOCUMENTS:
			if (value.startsWith("#"))
				lookup(value);
			else
				search(HealdanGuiConstants.SEARCH_DOCUMENTS_ONLY, value);
			break;
		case ScopePicker.EMAILS:
			search(HealdanGuiConstants.SEARCH_EMAILS_ONLY, value);
			break;
		case ScopePicker.FOLDERS:
			searchFolders(value);
			break;
		case ScopePicker.MATTERS:
			matters(value);
			break;
		case ScopePicker.CLIENTS:
			search(HealdanGuiConstants.SEARCH_CLIENTS, value);
			break;
		default:
			return false;
		}
		return true;
	}

	private static void lookup(String value) {
		HealdanLookupService.Instance.get().lookup(value, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUIDocNumberResult result) {
				if (!result.isFound()) {
					// SC.warn renders HTML, and the message can repeat what was typed
					SC.warn(SafeHtmlUtils.htmlEscape(result.getMessage()));
					return;
				}
				search(HealdanGuiConstants.SEARCH_DOC_NUMBER, value);
				if (result.getVersionId() != null)
					VersionResultDialog.open(result);
			}
		});
	}

	private static void matters(String value) {
		// A number, or client.matter
		if (!value.matches("^\\s*\\d+(\\.\\d+)?\\s*$")) {
			search(HealdanGuiConstants.SEARCH_MATTERS, value);
			return;
		}

		HealdanMatterService.Instance.get().resolve(value, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(List<GUIMatter> matters) {
				if (matters != null && matters.size() == 1)
					DocumentsPanel.get().openInFolder(matters.get(0).getFolderId(), null);
				else
					search(HealdanGuiConstants.SEARCH_MATTERS, value);
			}
		});
	}

	private static void searchFolders(String value) {
		GUISearchOptions options = newOptions(GUISearchOptions.TYPE_FOLDERS);
		options.setTopOperator("matchall");
		GUICriterion criterion = new GUICriterion();
		criterion.setField("name");
		criterion.setOperator("contains");
		criterion.setStringValue(value);
		options.setCaseSensitive(false);
		options.setCriteria(Arrays.asList(criterion));
		run(options);
	}

	private static void search(int type, String value) {
		GUISearchOptions options = newOptions(type);
		options.setExpression(value);
		if (type == HealdanGuiConstants.SEARCH_DOCUMENTS_ONLY || type == HealdanGuiConstants.SEARCH_EMAILS_ONLY) {
			options.setExpressionLanguage(I18N.getLocale());
			options.setFields(Constants.getFulltextDefaultFields());
			options.setCriteria(null);
		}
		run(options);
	}

	private static GUISearchOptions newOptions(int type) {
		GUISearchOptions options = new GUISearchOptions();
		Integer pageSize = DocumentGridUtil.getPageSizeFromSpec(Session.get().getUser().getHitsGrid());
		if (pageSize == null)
			pageSize = Session.get().getConfigAsInt("search.hits");
		options.setMaxHits(pageSize);
		options.setType(type);
		return options;
	}

	private static void run(GUISearchOptions options) {
		SearchPanel.get().onDraw();
		Search.get().setOptions(options);
		Search.get().search();
	}

	/**
	 * Titles the results grid's {@code ext_clientname} field "Client" and shows
	 * it for the Matters and Clients results only. CE's own hits panel refills
	 * the grid (it may rebuild it) in its own handling, so this runs after it.
	 */
	private static class ClientColumnObserver implements SearchObserver {

		@Override
		public void onSearchArrived() {
			Scheduler.get().scheduleDeferred(() -> {
				HitsListPanel panel = SearchPanel.get().getListingPanel();
				if (panel == null || !(panel.getGrid() instanceof DocumentsListGrid grid))
					return;
				// getFieldNum only counts the visible fields, so a column hidden by an earlier search needs getAllFields
				if (!hasField(grid))
					return;

				int type = Search.get().getOptions().getType();
				if (type == HealdanGuiConstants.SEARCH_MATTERS || type == HealdanGuiConstants.SEARCH_CLIENTS) {
					grid.setFieldTitle(CLIENT_FIELD, "Client");
					grid.showField(CLIENT_FIELD);
				} else
					grid.hideField(CLIENT_FIELD);
			});
		}

		private static boolean hasField(DocumentsListGrid grid) {
			ListGridField[] all = grid.getAllFields();
			if (all != null)
				for (ListGridField field : all)
					if (CLIENT_FIELD.equals(field.getName()))
						return true;
			return false;
		}

		@Override
		public void onOptionsChanged(GUISearchOptions newOptions) {
			// Nothing to do
		}
	}
}
