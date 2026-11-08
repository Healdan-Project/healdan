// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.settings;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.i18n.I18N;
import com.logicaldoc.gui.common.client.log.GuiLog;
import com.logicaldoc.gui.common.client.util.ItemFactory;
import com.logicaldoc.gui.common.client.util.LD;
import com.logicaldoc.gui.common.client.util.Util;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageEvent;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageOverview;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStoreBackend;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanStorageService;
import com.smartgwt.client.types.Alignment;
import com.smartgwt.client.types.ListGridEditEvent;
import com.smartgwt.client.types.ListGridFieldType;
import com.smartgwt.client.types.SelectionStyle;
import com.smartgwt.client.types.TitleOrientation;
import com.smartgwt.client.util.SC;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.SelectItem;
import com.smartgwt.client.widgets.form.fields.SpinnerItem;
import com.smartgwt.client.widgets.grid.ListGrid;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.ListGridRecord;
import com.smartgwt.client.widgets.layout.HLayout;
import com.smartgwt.client.widgets.layout.VLayout;
import com.smartgwt.client.widgets.menu.Menu;
import com.smartgwt.client.widgets.menu.MenuItem;
import com.smartgwt.client.widgets.menu.MenuItemSeparator;
import com.smartgwt.client.widgets.tab.Tab;

/**
 * The Healdan Settings panel's Storage tab (spec section 11): the storage
 * backends, the backend each Store keeps its files on, what waits to move and
 * what is stuck, and the storage settings. A backend is added or edited in its
 * own dialog, {@link BackendDialog}. Names and the server's messages, which
 * an author or an S3 server can fill, are shown as text, never as HTML.
 */
public class StorageTab extends Tab {

	private static final String ID = "id";

	private static final String NAME = "name";

	private static final String KIND = "kind";

	private static final String WHERE = "where";

	private static final String ENCRYPTED = "encrypted";

	private static final String FILES = "files";

	private static final String SIZE = "size";

	private static final String STATUS = "status";

	private static final String KEY_SHOWN = "keyshown";

	private static final String BACKEND = "backend";

	private static final String WAITING = "waiting";

	private static final String STUCK = "stuck";

	private static final String HOLD_DAYS = "holddays";

	private static final String CACHE_GB = "cachegb";

	private static final String TRANSFERS = "transfers";

	private final VLayout pane = new VLayout() {
		@Override
		protected void onDraw() {
			refresh();
		}
	};

	private ListGrid backends;

	private ListGrid stores;

	private Label otherNote;

	private DynamicForm settings;

	/** What the tab last loaded */
	private GUIStorageOverview current;

	/** Every backend's name, by id, for the Stores' list */
	private final Map<String, String> names = new LinkedHashMap<>();

	/** The backends a Store can be given, by id: not encrypted, or with their recovery key confirmed */
	private final Map<String, String> offered = new LinkedHashMap<>();

	public StorageTab() {
		super("Storage");
		pane.setWidth100();
		pane.setHeight100();
		pane.setMembersMargin(6);
		pane.setMargin(5);
		setPane(pane);
	}

	/** Loads the backends, the Stores and the settings again, and shows them */
	void refresh() {
		HealdanStorageService.Instance.get().load(new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUIStorageOverview loaded) {
				current = loaded;
				if (!loaded.isOn()) {
					showOff();
					return;
				}
				if (backends == null)
					build();
				show(loaded);
			}
		});
	}

	/** CE's store bean isn't Healdan's, so nothing here would take effect */
	private void showOff() {
		backends = null;
		Label off = new Label("Storage backends are off on this server: its context.properties names LogicalDOC's own "
				+ "store, not Healdan's, so it keeps every file in LogicalDOC's folders and nothing here applies. "
				+ "Healdan's Docker image always uses Healdan's.");
		off.setWidth(640);
		off.setHeight(1);
		off.setAutoHeight();
		pane.setMembers(off);
	}

	private void build() {
		Label about = note("Each backend is a place files are kept: a folder on this server, or a bucket at an S3 "
				+ "service. Each Store keeps its files on one backend. Right-click a backend for its Key History.");

		HLayout backendButtons = StorageUi.buttons(StorageUi.button("Add Backend", click -> add()),
				StorageUi.button("Edit", click -> withSelected(this::edit)),
				StorageUi.button("Test", click -> withSelected(this::test)),
				StorageUi.button("Show Recovery Key", click -> withSelected(this::showKey)),
				StorageUi.button("Enter Recovery Key", click -> withSelected(this::enterKey)),
				StorageUi.button("Remove", click -> withSelected(this::remove)),
				StorageUi.button("Refresh", click -> refresh()));

		backends = new ListGrid();
		backends.setWidth100();
		backends.setHeight(200);
		backends.setSelectionType(SelectionStyle.SINGLE);
		backends.setWrapCells(true);
		backends.setFixedRecordHeights(false);
		backends.setEmptyMessage("No backends");
		backends.setFields(prepareBackendFields());
		backends.addDoubleClickHandler(click -> withSelected(this::edit));
		backends.addCellContextClickHandler(event -> {
			event.cancel();
			GUIStorageBackend backend = selected();
			if (backend != null)
				showMenu(backend);
		});

		Label storesNote = note("Choose a Store's backend, and its files move there in the background. A new Store "
				+ "starts on Original.");

		stores = new ListGrid();
		stores.setWidth(760);
		stores.setHeight(180);
		stores.setSelectionType(SelectionStyle.SINGLE);
		stores.setWrapCells(true);
		stores.setFixedRecordHeights(false);
		stores.setCanEdit(true);
		stores.setEditEvent(ListGridEditEvent.CLICK);
		stores.setEditByCell(true);
		stores.setEmptyMessage("No Stores");
		stores.setFields(prepareStoreFields());
		stores.setEditorCustomizer(context -> {
			if (!BACKEND.equals(context.getEditField().getName()))
				return context.getDefaultProperties();
			return backendPicker(context.getEditedRecord());
		});
		stores.addCellClickHandler(event -> {
			ListGridRecord rec = event.getRecord();
			if (rec != null && STUCK.equals(stores.getFieldName(event.getColNum()))
					&& rec.getAttributeAsLong(STUCK) > 0)
				StuckFilesDialog.open(Long.parseLong(rec.getAttributeAsString(ID)), rec.getAttributeAsString(NAME),
						rec.getAttributeAsLong(STUCK), this::refresh);
		});

		otherNote = note("Other Files are files whose document isn't in a Store, such as one saved before it had a "
				+ "folder.");

		settings = new DynamicForm();
		settings.setWidth(640);
		settings.setColWidths(190, "*");
		settings.setTitleOrientation(TitleOrientation.LEFT);

		SpinnerItem holdDays = ItemFactory.newSpinnerItem(HOLD_DAYS, "Holding Period", 30, 1, 365);
		holdDays.setRequired(true);
		holdDays.setHint("days a deleted or moved file's old copy is kept before it's purged");
		SpinnerItem cacheGb = ItemFactory.newSpinnerItem(CACHE_GB, "Read Cache", 20, 1, 10000);
		cacheGb.setRequired(true);
		cacheGb.setHint("GB of S3 backends' files kept on this server for reading");
		SpinnerItem transfers = ItemFactory.newSpinnerItem(TRANSFERS, "Parallel Transfers", 4, 1, 16);
		transfers.setRequired(true);
		transfers.setHint("files moved between backends at once");
		settings.setItems(holdDays, cacheGb, transfers);

		pane.setMembers(title("Backends"), about, backendButtons, backends, title("Stores"), storesNote, stores,
				otherNote, title("Settings"), settings,
				StorageUi.buttons(StorageUi.button("Save", click -> saveSettings())));
	}

	private ListGridField[] prepareBackendFields() {
		ListGridField name = new ListGridField(NAME, "Name", 140);
		name.setEscapeHTML(true);
		ListGridField kind = new ListGridField(KIND, "Kind", 90);
		ListGridField where = new ListGridField(WHERE, "Where");
		where.setWidth("*");
		where.setEscapeHTML(true);
		ListGridField encrypted = new ListGridField(ENCRYPTED, "Encrypted", 75);
		encrypted.setType(ListGridFieldType.BOOLEAN);
		encrypted.setAlign(Alignment.CENTER);
		ListGridField files = new ListGridField(FILES, "Files", 70);
		files.setType(ListGridFieldType.INTEGER);
		ListGridField size = new ListGridField(SIZE, "Size", 80);
		size.setAlign(Alignment.RIGHT);
		ListGridField status = new ListGridField(STATUS, "Status");
		status.setWidth("*");
		status.setEscapeHTML(true);
		ListGridField keyShown = new ListGridField(KEY_SHOWN, "Key Last Shown", 150);
		keyShown.setEscapeHTML(true);
		return new ListGridField[] { name, kind, where, encrypted, files, size, status, keyShown };
	}

	private ListGridField[] prepareStoreFields() {
		ListGridField name = new ListGridField(NAME, "Store");
		name.setWidth("*");
		name.setEscapeHTML(true);
		name.setCanEdit(false);

		ListGridField backend = new ListGridField(BACKEND, "Backend", 220);
		backend.setCellFormatter((value, rec, rowNum, colNum) -> {
			String backendName = value != null ? names.get(value.toString()) : null;
			return backendName != null ? SafeHtmlUtils.htmlEscape(backendName) : "";
		});

		ListGridField waiting = new ListGridField(WAITING, "Waiting to Move", 200);
		waiting.setCanEdit(false);
		ListGridField stuck = new ListGridField(STUCK, "Stuck", 120);
		stuck.setCanEdit(false);
		stuck.setCellFormatter((value, rec, rowNum, colNum) -> {
			long count = rec.getAttributeAsLong(STUCK);
			// Built from a number only. Not an anchor: a click on one never reaches the grid's cell click
			return count > 0
					? "<span style='text-decoration: underline; cursor: pointer'>" + StorageUi.files(count) + "</span>"
					: "None";
		});
		return new ListGridField[] { name, backend, waiting, stuck };
	}

	/** The drop-down of the backends a Store can be given: the confirmed ones, and the one it has */
	private SelectItem backendPicker(ListGridRecord rec) {
		LinkedHashMap<String, String> choices = new LinkedHashMap<>(offered);
		String now = rec.getAttributeAsString(BACKEND);
		if (now != null && !choices.containsKey(now) && names.containsKey(now))
			choices.put(now, names.get(now));
		SelectItem picker = new SelectItem();
		// Backend names are any administrator's: shown as text, never as HTML
		picker.setEscapeHTML(true);
		picker.setValueMap(choices);
		picker.addChangeHandler(event -> {
			// Asked first; the grid shows the new backend once it is saved and loaded again
			event.cancel();
			stores.cancelEditing();
			if (event.getValue() != null)
				switchBackend(rec, Long.parseLong(event.getValue().toString()));
		});
		return picker;
	}

	private void show(GUIStorageOverview loaded) {
		String selectedId = backends.getSelectedRecord() != null
				? backends.getSelectedRecord().getAttributeAsString(ID)
				: null;
		names.clear();
		offered.clear();
		ListGridRecord[] records = new ListGridRecord[loaded.getBackends().length];
		for (int i = 0; i < records.length; i++) {
			GUIStorageBackend backend = loaded.getBackends()[i];
			String id = Long.toString(backend.getId());
			names.put(id, backend.getName());
			if (backend.isOffered())
				offered.put(id, backend.getName());
			records[i] = new ListGridRecord();
			records[i].setAttribute(ID, id);
			records[i].setAttribute(NAME, backend.getName());
			records[i].setAttribute(KIND, backend.isS3() ? "S3" : "Local Folder");
			records[i].setAttribute(WHERE, where(backend));
			records[i].setAttribute(ENCRYPTED, StorageUi.encrypted(backend));
			records[i].setAttribute(FILES, backend.getFiles());
			records[i].setAttribute(SIZE, Util.formatSizeW7((double) backend.getSize()));
			records[i].setAttribute(STATUS, status(backend));
			records[i].setAttribute(KEY_SHOWN, keyShown(backend));
		}
		backends.setData(records);
		if (selectedId != null)
			for (int i = 0; i < records.length; i++)
				if (selectedId.equals(records[i].getAttributeAsString(ID)))
					backends.selectRecord(i);

		boolean other = loaded.getOtherWaiting() > 0 || loaded.getOtherStuck() > 0;
		ListGridRecord[] storeRecords = new ListGridRecord[loaded.getStores().length + (other ? 1 : 0)];
		for (int i = 0; i < loaded.getStores().length; i++) {
			GUIStoreBackend store = loaded.getStores()[i];
			storeRecords[i] = storeRecord(store.getStoreId(), store.getName(), waiting(store.getWaiting(),
					store.getWaitingSize()), store.getStuck());
			storeRecords[i].setAttribute(BACKEND, Long.toString(store.getBackendId()));
		}
		if (other) {
			// The files with no Store: they have no backend to choose
			ListGridRecord rec = storeRecord(0, "Other Files",
					waiting(loaded.getOtherWaiting(), loaded.getOtherWaitingSize()), loaded.getOtherStuck());
			rec.set_canEdit(false);
			storeRecords[storeRecords.length - 1] = rec;
		}
		stores.setData(storeRecords);
		otherNote.setVisible(other);

		settings.setValue(HOLD_DAYS, loaded.getHoldDays());
		settings.setValue(CACHE_GB, loaded.getCacheGb());
		settings.setValue(TRANSFERS, loaded.getTransfers());
		settings.rememberValues();
	}

	private static ListGridRecord storeRecord(long storeId, String name, String waiting, long stuck) {
		ListGridRecord rec = new ListGridRecord();
		rec.setAttribute(ID, Long.toString(storeId));
		rec.setAttribute(NAME, name);
		rec.setAttribute(WAITING, waiting);
		rec.setAttribute(STUCK, stuck);
		return rec;
	}

	/** The path, or the bucket, its prefix and the endpoint */
	private static String where(GUIStorageBackend backend) {
		if (!backend.isS3())
			return backend.getPath();
		String prefix = backend.getPrefix() != null ? backend.getPrefix() : "";
		return backend.getBucket() + "/" + prefix + " at " + backend.getEndpoint();
	}

	/** OK, or Failing with the last error and since when; and whether a Store may use it yet */
	private static String status(GUIStorageBackend backend) {
		String status;
		if ("ok".equals(backend.getStatus()))
			status = "OK";
		else if ("failing".equals(backend.getStatus()))
			status = "Failing" + since(backend.getSince())
					+ (backend.getError() != null ? ": " + backend.getError() : "");
		else
			status = "Not Checked Yet";
		// The server's error may end its own sentence
		if (!backend.isOffered())
			status += (status.endsWith(".") ? " " : ". ")
					+ "Its recovery key isn't confirmed, so no Store can use it yet.";
		return status;
	}

	private static String since(Date since) {
		return since != null ? " since " + I18N.formatDate(since) : "";
	}

	private static String keyShown(GUIStorageBackend backend) {
		if (backend.getKeyShown() == null)
			return StorageUi.encrypted(backend) ? "Never" : "";
		return I18N.formatDate(backend.getKeyShown()) + " to " + backend.getKeyShownBy();
	}

	/** "None", or "12 files (3.1 MB)" */
	private static String waiting(long files, long size) {
		return files == 0 ? "None" : StorageUi.files(files) + " (" + Util.formatSizeW7((double) size) + ")";
	}

	private GUIStorageBackend selected() {
		ListGridRecord rec = backends.getSelectedRecord();
		if (rec == null || current == null)
			return null;
		long id = Long.parseLong(rec.getAttributeAsString(ID));
		for (GUIStorageBackend backend : current.getBackends())
			if (backend.getId() == id)
				return backend;
		return null;
	}

	private void withSelected(Consumer<GUIStorageBackend> action) {
		GUIStorageBackend backend = selected();
		if (backend == null)
			SC.say("Select a backend first.");
		else
			action.accept(backend);
	}

	private void showMenu(GUIStorageBackend backend) {
		Menu menu = new Menu();
		menu.setItems(menuItem("Edit", () -> edit(backend)), menuItem("Test", () -> test(backend)),
				new MenuItemSeparator(), menuItem("Show Recovery Key", () -> showKey(backend)),
				menuItem("Enter Recovery Key", () -> enterKey(backend)),
				menuItem("Key History", () -> keyHistory(backend)), new MenuItemSeparator(),
				menuItem("Remove", () -> remove(backend)));
		menu.showContextMenu();
	}

	private static MenuItem menuItem(String title, Runnable action) {
		MenuItem item = new MenuItem(title);
		item.addClickHandler(click -> action.run());
		return item;
	}

	private void add() {
		GUIStorageBackend fresh = new GUIStorageBackend();
		fresh.setKind(GUIStorageBackend.S3);
		BackendDialog.open(fresh, null, this::refresh);
	}

	private void edit(GUIStorageBackend backend) {
		BackendDialog.open(backend, placeFixed(backend), this::refresh);
	}

	/** Why the backend's bucket, prefix or path can't change; null if they can */
	private String placeFixed(GUIStorageBackend backend) {
		if (backend.isInUse())
			return "can't change once files are there";
		for (GUIStoreBackend store : current.getStores())
			if (store.getBackendId() == backend.getId())
				return "can't change while a Store keeps its files here";
		return null;
	}

	private void test(GUIStorageBackend backend) {
		LD.contactingServer();
		HealdanStorageService.Instance.get().testBackend(backend.getId(), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(String[] problems) {
				StorageUi.showTest(backend.getName(), problems);
				refresh();
			}
		});
	}

	private void showKey(GUIStorageBackend backend) {
		if (!StorageUi.encrypted(backend)) {
			SC.say(SafeHtmlUtils.htmlEscape(backend.getName()) + " isn't encrypted, so it has no recovery key.");
			return;
		}
		LD.contactingServer();
		HealdanStorageService.Instance.get().showRecoveryKey(backend.getId(), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(String key) {
				// An unconfirmed key is asked for again: confirming it is what lets Stores use the backend
				RecoveryKeyDialog.open(backend, key, !backend.isKeyConfirmed(), StorageTab.this::refresh);
				refresh();
			}
		});
	}

	private void enterKey(GUIStorageBackend backend) {
		if (StorageUi.encrypted(backend))
			RecoveryKeyDialog.enter(backend, this::refresh);
		else
			SC.say(SafeHtmlUtils.htmlEscape(backend.getName()) + " isn't encrypted, so it has no recovery key.");
	}

	/** Every showing and entering of the backend's recovery key, newest first (question 8) */
	private void keyHistory(GUIStorageBackend backend) {
		LD.contactingServer();
		HealdanStorageService.Instance.get().keyEvents(backend.getId(), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUIStorageEvent[] events) {
				RecoveryKeyDialog.history(backend, events);
			}
		});
	}

	private void remove(GUIStorageBackend backend) {
		String name = SafeHtmlUtils.htmlEscape(backend.getName());
		if (backend.isOriginal()) {
			SC.warn("Original can be renamed, but not removed: it keeps the files of every Store that has no other "
					+ "backend.");
			return;
		}
		String key = StorageUi.encrypted(backend) ? " Its key file is deleted too." : "";
		LD.ask("Remove " + name, "Remove " + name + "? Only a backend no Store uses, with no file on it, can be "
				+ "removed." + key, 440, yes -> {
					if (!Boolean.TRUE.equals(yes))
						return;
					LD.contactingServer();
					HealdanStorageService.Instance.get().removeBackend(backend.getId(), new DefaultAsyncCallback<>() {
						@Override
						public void handleSuccess(Void result) {
							GuiLog.info("Removed " + name, null);
							refresh();
						}
					});
				});
	}

	/** Asks with the files that would move, then gives the Store the backend */
	private void switchBackend(ListGridRecord rec, long backendId) {
		long storeId = Long.parseLong(rec.getAttributeAsString(ID));
		if (storeId == 0 || Long.toString(backendId).equals(rec.getAttributeAsString(BACKEND)))
			return;
		String store = SafeHtmlUtils.htmlEscape(rec.getAttributeAsString(NAME));
		String backend = SafeHtmlUtils.htmlEscape(String.valueOf(names.get(Long.toString(backendId))));
		LD.contactingServer();
		HealdanStorageService.Instance.get().previewStoreSwitch(storeId, backendId, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(long[] moving) {
				String what = moving[0] == 0
						? "No file needs to move: from now on, " + store + " keeps its files on " + backend + "."
						: StorageUi.files(moving[0]) + " (" + Util.formatSizeW7((double) moving[1]) + ") will move to "
								+ backend + " in the background. Until each has moved, it's read from where it is now.";
				LD.ask("Change the Backend of " + store, what + "<br><br>Give " + store + " this backend?", 440,
						yes -> {
							if (Boolean.TRUE.equals(yes))
								setBackend(storeId, store, backendId, backend);
						});
			}
		});
	}

	private void setBackend(long storeId, String store, long backendId, String backend) {
		LD.contactingServer();
		HealdanStorageService.Instance.get().setStoreBackend(storeId, backendId, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(Void result) {
				GuiLog.info(store + " keeps its files on " + backend + " from now on", null);
				refresh();
			}
		});
	}

	private void saveSettings() {
		if (!Boolean.TRUE.equals(settings.validate()))
			return;
		int holdDays = Integer.parseInt(settings.getValueAsString(HOLD_DAYS));
		int cacheGb = Integer.parseInt(settings.getValueAsString(CACHE_GB));
		int transfers = Integer.parseInt(settings.getValueAsString(TRANSFERS));
		LD.contactingServer();
		HealdanStorageService.Instance.get().saveSettings(holdDays, cacheGb, transfers, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(Void result) {
				GuiLog.info("Storage settings saved", null);
				refresh();
			}
		});
	}

	private static Label title(String text) {
		Label label = new Label("<b>" + text + "</b>");
		label.setHeight(20);
		return label;
	}

	private static Label note(String text) {
		Label label = new Label(text);
		label.setWidth(760);
		label.setHeight(1);
		label.setAutoHeight();
		return label;
	}
}
