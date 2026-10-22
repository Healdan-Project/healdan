package com.logicaldoc.gui.frontend.client.healdan.navigation;

import java.util.List;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.i18n.I18N;
import com.logicaldoc.gui.frontend.client.document.DocumentsPanel;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIMatter;
import com.smartgwt.client.types.SelectionStyle;
import com.smartgwt.client.widgets.grid.HoverCustomizer;
import com.smartgwt.client.widgets.grid.ListGrid;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.ListGridRecord;
import com.smartgwt.client.widgets.layout.VLayout;

/**
 * A list of matters in the left pane (spec 8.2). Each line reads
 * {@code 998.1 - Healdan Demo (Healdan)}, the client smaller and muted, cut
 * off at the end when the pane is too narrow, with the whole line on hover. A
 * click opens the matter in the Browser tree.
 */
public class MatterListPanel extends VLayout {

	/** Where the list's matters come from */
	@FunctionalInterface
	public interface Source {
		void load(AsyncCallback<List<GUIMatter>> callback);
	}

	private static final String FOLDER_ID = "folderId";

	private static final String DISPLAY = "display";

	private static final String CLIENT_NAME = "clientName";

	private final Source source;

	private final ListGrid list = new ListGrid();

	/**
	 * @param source where the matters come from
	 */
	public MatterListPanel(Source source) {
		this.source = source;
		setWidth100();
		setHeight100();

		ListGridField display = new ListGridField(DISPLAY);
		display.setWidth("*");
		display.setCellFormatter((value, rec, row, col) -> entryHtml(rec));
		display.setShowHover(true);
		display.setHoverCustomizer(new HoverCustomizer() {
			@Override
			public String hoverHTML(Object value, ListGridRecord rec, int rowNum, int colNum) {
				return SafeHtmlUtils.htmlEscape(entryText(rec));
			}
		});

		list.setWidth100();
		list.setHeight100();
		list.setShowHeader(false);
		list.setSelectionType(SelectionStyle.SINGLE);
		list.setCanEdit(false);
		list.setEmptyMessage(I18N.message("notitemstoshow"));
		list.setFields(display);
		list.addRecordClickHandler(click -> DocumentsPanel.get()
				.openInFolder(click.getRecord().getAttributeAsLong(FOLDER_ID), null));
		addMember(list);
	}

	/**
	 * Loads the matters again
	 */
	public void refresh() {
		source.load(new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(List<GUIMatter> matters) {
				ListGridRecord[] records = new ListGridRecord[matters.size()];
				for (int i = 0; i < records.length; i++) {
					GUIMatter matter = matters.get(i);
					ListGridRecord rec = new ListGridRecord();
					rec.setAttribute(FOLDER_ID, matter.getFolderId());
					rec.setAttribute(DISPLAY, matter.getDisplay());
					rec.setAttribute(CLIENT_NAME, matter.getClientName());
					records[i] = rec;
				}
				list.setData(records);
			}
		});
	}

	private static String entryHtml(ListGridRecord rec) {
		String client = rec.getAttributeAsString(CLIENT_NAME);
		return "<div style='overflow: hidden; white-space: nowrap; text-overflow: ellipsis'>"
				+ SafeHtmlUtils.htmlEscape(rec.getAttributeAsString(DISPLAY))
				+ (client != null
						? " <span style='font-size: 85%; opacity: 0.65'>(" + SafeHtmlUtils.htmlEscape(client) + ")</span>"
						: "")
				+ "</div>";
	}

	private static String entryText(ListGridRecord rec) {
		String client = rec.getAttributeAsString(CLIENT_NAME);
		return rec.getAttributeAsString(DISPLAY) + (client != null ? " (" + client + ")" : "");
	}
}
