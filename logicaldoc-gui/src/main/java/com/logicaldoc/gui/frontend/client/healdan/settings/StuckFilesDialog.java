// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.settings;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.log.GuiLog;
import com.logicaldoc.gui.common.client.util.LD;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStuckFile;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanStorageService;
import com.smartgwt.client.types.HeaderControls;
import com.smartgwt.client.types.ListGridFieldType;
import com.smartgwt.client.types.SelectionStyle;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.Window;
import com.smartgwt.client.widgets.grid.ListGrid;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.ListGridRecord;
import com.smartgwt.client.widgets.layout.VLayout;

/**
 * The files of a Store the mover gave up on after ten failures in a row (spec
 * section 7), each with its last error, and Retry, which puts them back to
 * waiting. File names and errors, which an author or an S3 server can fill,
 * are shown as text.
 */
public class StuckFilesDialog extends Window {

	private static final String DOC_ID = "docid";

	private static final String FILE_NAME = "filename";

	private static final String RESOURCE = "resource";

	private static final String ATTEMPTS = "attempts";

	private static final String LAST_ERROR = "lasterror";

	private final long storeId;

	private final String name;

	private final Runnable onChange;

	private final ListGrid grid = new ListGrid();

	private final Label count = new Label();

	/**
	 * Opens the dialog
	 *
	 * @param storeId the Store's folder id; 0 for the files with no Store
	 * @param storeName the Store's name, or the line's for the files with no
	 *        Store
	 * @param stuck how many of its files are stuck
	 * @param onChange what to do once they are retried
	 */
	public static void open(long storeId, String storeName, long stuck, Runnable onChange) {
		new StuckFilesDialog(storeId, storeName, stuck, onChange).show();
	}

	private StuckFilesDialog(long storeId, String storeName, long stuck, Runnable onChange) {
		this.storeId = storeId;
		this.name = SafeHtmlUtils.htmlEscape(storeName);
		this.onChange = onChange;

		setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		setTitle("Stuck Files of " + name);
		setIsModal(true);
		setShowModalMask(true);
		setWidth(820);
		setHeight(460);
		setCanDragResize(true);
		centerInPage();
		addCloseClickHandler(event -> destroy());

		VLayout content = new VLayout();
		content.setMargin(10);
		content.setMembersMargin(8);
		content.setWidth100();
		content.setHeight100();

		Label about = new Label("The mover gave up on these files after ten failures in a row, and leaves them where "
				+ "they are until you retry them. Retry puts all " + StorageTab.files(stuck)
				+ " back to waiting, and they move with the others.");
		about.setWidth100();
		about.setHeight(1);
		about.setAutoHeight();

		count.setWidth100();
		count.setHeight(1);
		count.setAutoHeight();

		ListGridField docId = new ListGridField(DOC_ID, "Document", 80);
		docId.setType(ListGridFieldType.INTEGER);
		ListGridField fileName = new ListGridField(FILE_NAME, "File Name", 180);
		fileName.setEscapeHTML(true);
		ListGridField resource = new ListGridField(RESOURCE, "Resource", 120);
		resource.setEscapeHTML(true);
		ListGridField attempts = new ListGridField(ATTEMPTS, "Tries", 60);
		attempts.setType(ListGridFieldType.INTEGER);
		ListGridField lastError = new ListGridField(LAST_ERROR, "Last Error");
		lastError.setWidth("*");
		lastError.setEscapeHTML(true);

		grid.setWidth100();
		grid.setHeight("*");
		grid.setSelectionType(SelectionStyle.SINGLE);
		grid.setWrapCells(true);
		grid.setFixedRecordHeights(false);
		grid.setCanSelectText(true);
		grid.setEmptyMessage("No stuck files");
		grid.setFields(docId, fileName, resource, attempts, lastError);

		content.setMembers(about, count, grid, StorageTab.buttons(StorageTab.button("Retry", click -> retry()),
				StorageTab.button("Close", click -> destroy())));
		addItem(content);
		load(stuck);
	}

	private void load(long stuck) {
		HealdanStorageService.Instance.get().stuckFiles(storeId, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUIStuckFile[] files) {
				ListGridRecord[] records = new ListGridRecord[files.length];
				for (int i = 0; i < files.length; i++) {
					GUIStuckFile file = files[i];
					records[i] = new ListGridRecord();
					records[i].setAttribute(DOC_ID, file.getDocId());
					records[i].setAttribute(FILE_NAME,
							file.getFileName() != null ? file.getFileName() : "(document deleted)");
					records[i].setAttribute(RESOURCE, file.getResource());
					records[i].setAttribute(ATTEMPTS, file.getAttempts());
					records[i].setAttribute(LAST_ERROR, file.getLastError());
				}
				grid.setData(records);
				count.setContents(files.length < stuck ? "Listed: the files of the newest documents, " + files.length
						+ " of " + stuck + "." : "");
				count.setVisible(files.length < stuck);
			}
		});
	}

	private void retry() {
		LD.contactingServer();
		HealdanStorageService.Instance.get().retry(storeId, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(Void result) {
				destroy();
				GuiLog.info("The stuck files of " + name + " wait to move again", null);
				onChange.run();
			}
		});
	}
}
