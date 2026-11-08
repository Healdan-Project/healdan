// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.settings;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.grid.DateListGridField;
import com.logicaldoc.gui.common.client.log.GuiLog;
import com.logicaldoc.gui.common.client.util.ItemFactory;
import com.logicaldoc.gui.common.client.util.LD;
import com.logicaldoc.gui.common.client.widgets.CopyTextFormItemIcon;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageEvent;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanStorageService;
import com.smartgwt.client.types.HeaderControls;
import com.smartgwt.client.types.SelectionStyle;
import com.smartgwt.client.types.TitleOrientation;
import com.smartgwt.client.util.SC;
import com.smartgwt.client.widgets.Canvas;
import com.smartgwt.client.widgets.HTMLFlow;
import com.smartgwt.client.widgets.Window;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.StaticTextItem;
import com.smartgwt.client.widgets.form.fields.TextItem;
import com.smartgwt.client.widgets.grid.ListGrid;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.ListGridRecord;
import com.smartgwt.client.widgets.layout.VLayout;

/**
 * An encrypted storage backend's recovery key (spec section 9): shown in its
 * groups when the backend is added, with the question that confirms it was
 * saved, and again from Show Recovery Key; Enter Recovery Key, which puts a
 * lost key file back; and the Key History of both (question 8).
 */
public class RecoveryKeyDialog extends Window {

	private static final String KEY = "key";

	private static final String LAST_GROUP = "lastgroup";

	private static final String DATE = "date";

	private static final String EVENT = "event";

	private static final String BY = "by";

	private final GUIStorageBackend backend;

	private final Runnable onChange;

	private final DynamicForm question = new DynamicForm();

	/**
	 * Shows a backend's recovery key
	 *
	 * @param backend the backend
	 * @param key its recovery key, in its groups
	 * @param ask whether to ask for its last group back, which lets Stores use
	 *        the backend: when it is added, and until that is done
	 * @param onChange what to do once the key is confirmed
	 */
	public static void open(GUIStorageBackend backend, String key, boolean ask, Runnable onChange) {
		new RecoveryKeyDialog(backend, key, ask, onChange).show();
	}

	private RecoveryKeyDialog(GUIStorageBackend backend, String key, boolean ask, Runnable onChange) {
		this.backend = backend;
		this.onChange = onChange;
		String name = SafeHtmlUtils.htmlEscape(backend.getName());

		prepare(this, "Recovery Key of " + name, 560);

		VLayout content = content();
		content.addMember(flow((ask ? "<b>" + name + "</b> is encrypted. This is its recovery key: save it now, away "
				+ "from this server, in a password manager or on paper kept locked away.<br><br>" : "")
				+ "The files of " + name + " can be read only with its key file, in this server's configuration "
				+ "folder, or with this recovery key, which puts the key file back. <b>Lose both, and its files are "
				+ "lost for good:</b> nobody can read them, Healdan included."));
		content.addMember(keyForm(key));

		if (ask) {
			content.addMember(flow("To show it's saved, type its last group of four characters. No Store can use "
					+ name + " until you do."));
			question.setWidth100();
			question.setTitleOrientation(TitleOrientation.LEFT);
			question.setColWidths(110, "*");
			TextItem lastGroup = ItemFactory.newTextItemPreventAutocomplete(LAST_GROUP, "Last Group", null);
			lastGroup.setRequired(true);
			lastGroup.setLength(9);
			lastGroup.setWidth(100);
			question.setItems(lastGroup);
			content.addMember(question);
			content.addMember(StorageTab.buttons(StorageTab.button("Confirm", click -> confirm()),
					StorageTab.button("Later", click -> destroy())));
		} else {
			content.addMember(StorageTab.buttons(StorageTab.button("Close", click -> destroy())));
		}
		addItem(content);
	}

	/** The key in two lines of its groups, as text, with an icon that copies it */
	private static DynamicForm keyForm(String key) {
		String[] groups = key.split(" ");
		StringBuilder lines = new StringBuilder();
		for (int i = 0; i < groups.length; i++) {
			if (i > 0)
				lines.append(i == (groups.length + 1) / 2 ? "<br>" : " ");
			lines.append(SafeHtmlUtils.htmlEscape(groups[i]));
		}

		DynamicForm form = new DynamicForm();
		form.setWidth100();
		form.setNumCols(1);
		StaticTextItem item = ItemFactory.newStaticTextItem(KEY, "", "<span style='font-family: monospace; "
				+ "font-size: 17px; line-height: 26px; letter-spacing: 1px'>" + lines + "</span>");
		item.setShowTitle(false);
		item.setShouldSaveValue(false);
		item.setIcons(new CopyTextFormItemIcon(key, "Copy"));
		form.setItems(item);
		return form;
	}

	private void confirm() {
		if (!Boolean.TRUE.equals(question.validate()))
			return;
		String name = SafeHtmlUtils.htmlEscape(backend.getName());
		LD.contactingServer();
		HealdanStorageService.Instance.get().confirmRecoveryKey(backend.getId(),
				question.getValueAsString(LAST_GROUP), new DefaultAsyncCallback<>() {
					@Override
					public void handleSuccess(Void result) {
						destroy();
						GuiLog.info("The recovery key of " + name + " is confirmed: Stores can use it", null);
						onChange.run();
					}
				});
	}

	/**
	 * Asks for a backend's recovery key, and puts its key file back with it
	 *
	 * @param backend an encrypted backend
	 * @param onChange what to do once the key file is back
	 */
	static void enter(GUIStorageBackend backend, Runnable onChange) {
		String name = SafeHtmlUtils.htmlEscape(backend.getName());
		Window window = new Window();
		prepare(window, "Enter the Recovery Key of " + name, 560);

		VLayout content = content();
		content.addMember(flow("If the key file of " + name + " was lost or damaged, its recovery key puts it back. "
				+ "Healdan checks the key against the backend first, and writes nothing if it isn't the backend's."));

		DynamicForm form = new DynamicForm();
		form.setWidth100();
		form.setTitleOrientation(TitleOrientation.TOP);
		form.setNumCols(1);
		TextItem key = ItemFactory.newTextItemPreventAutocomplete(KEY, "Recovery Key", null);
		key.setRequired(true);
		key.setLength(200);
		key.setWidth("100%");
		key.setHint("52 characters in any case; spaces and hyphens are ignored");
		key.setShowHintInField(true);
		form.setItems(key);
		content.addMember(form);

		content.addMember(StorageTab.buttons(StorageTab.button("Enter", click -> {
			if (!Boolean.TRUE.equals(form.validate()))
				return;
			LD.contactingServer();
			HealdanStorageService.Instance.get().enterRecoveryKey(backend.getId(), form.getValueAsString(KEY),
					new DefaultAsyncCallback<>() {
						@Override
						public void handleSuccess(Void result) {
							window.destroy();
							SC.say("The recovery key is right, and the key file of " + name + " is back.");
							onChange.run();
						}
					});
		}), StorageTab.button("Cancel", click -> window.destroy())));

		window.addItem(content);
		window.show();
	}

	/**
	 * Lists every showing and entering of a backend's recovery key
	 *
	 * @param backend the backend
	 * @param events its showings and enterings, newest first
	 */
	static void history(GUIStorageBackend backend, GUIStorageEvent[] events) {
		Window window = new Window();
		prepare(window, "Key History of " + SafeHtmlUtils.htmlEscape(backend.getName()), 520);

		ListGridField date = new DateListGridField(DATE, "When");
		date.setWidth(150);
		date.setCanSort(false);
		ListGridField event = new ListGridField(EVENT, "What", 170);
		event.setCanSort(false);
		ListGridField by = new ListGridField(BY, "By");
		by.setWidth("*");
		by.setEscapeHTML(true);
		by.setCanSort(false);

		ListGrid grid = new ListGrid();
		grid.setWidth100();
		grid.setHeight(240);
		grid.setSelectionType(SelectionStyle.NONE);
		grid.setEmptyMessage(StorageTab.encrypted(backend)
				? "Its recovery key hasn't been shown or entered since it was added"
				: "It isn't encrypted, so it has no recovery key");
		grid.setFields(date, event, by);
		ListGridRecord[] records = new ListGridRecord[events.length];
		for (int i = 0; i < events.length; i++) {
			records[i] = new ListGridRecord();
			records[i].setAttribute(DATE, events[i].getDate());
			records[i].setAttribute(EVENT,
					GUIStorageEvent.KEY_ENTERED.equals(events[i].getEvent()) ? "Recovery Key Entered"
							: "Recovery Key Shown");
			records[i].setAttribute(BY, events[i].getUserName());
		}
		grid.setData(records);

		VLayout content = content();
		content.addMember(grid);
		content.addMember(StorageTab.buttons(StorageTab.button("Close", click -> window.destroy())));
		window.addItem(content);
		window.show();
	}

	private static void prepare(Window window, String title, int width) {
		window.setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		window.setTitle(title);
		window.setIsModal(true);
		window.setShowModalMask(true);
		window.setWidth(width);
		window.setAutoSize(true);
		window.centerInPage();
		window.addCloseClickHandler(click -> window.destroy());
	}

	private static VLayout content() {
		VLayout content = new VLayout();
		content.setMargin(10);
		content.setMembersMargin(8);
		content.setWidth100();
		content.setAutoHeight();
		return content;
	}

	private static Canvas flow(String html) {
		HTMLFlow flow = new HTMLFlow(html);
		flow.setWidth100();
		return flow;
	}
}
