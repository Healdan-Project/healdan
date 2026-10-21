package com.logicaldoc.gui.frontend.client.healdan.matter;

import java.util.LinkedHashMap;
import java.util.List;

import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.Session;
import com.logicaldoc.gui.common.client.beans.GUIValue;
import com.logicaldoc.gui.common.client.i18n.I18N;
import com.logicaldoc.gui.common.client.util.ItemFactory;
import com.logicaldoc.gui.common.client.util.LD;
import com.logicaldoc.gui.frontend.client.document.DocumentsPanel;
import com.logicaldoc.gui.frontend.client.folder.FolderNavigator;
import com.logicaldoc.gui.frontend.client.healdan.HealdanLabels;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIClient;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIMatter;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanMatterService;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSettingsService;
import com.logicaldoc.gui.frontend.client.healdan.settings.ClientDialog;
import com.smartgwt.client.types.HeaderControls;
import com.smartgwt.client.types.TitleOrientation;
import com.smartgwt.client.widgets.Window;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.ButtonItem;
import com.smartgwt.client.widgets.form.fields.FormItem;
import com.smartgwt.client.widgets.form.fields.IntegerItem;
import com.smartgwt.client.widgets.form.fields.SelectItem;
import com.smartgwt.client.widgets.form.fields.TextAreaItem;
import com.smartgwt.client.widgets.form.fields.TextItem;
import com.smartgwt.client.widgets.tree.TreeNode;

/**
 * The New and Edit Matter dialogs (spec 5.3). The fields come in the order an
 * admin decides them: client, number, name and description, Store, owner and
 * template. In edit mode the client and number can't change, and neither can
 * the Store and template, which only matter when the workspace is made.
 */
public class MatterDialog extends Window {

	private static final String CLIENT = "client";

	private static final String NUMBER = "number";

	private static final String NAME = "name";

	private static final String DESCRIPTION = "description";

	private static final String STORE = "store";

	private static final String OWNER = "owner";

	private static final String TEMPLATE = "template";

	/** The matter being edited, null when creating one */
	private final GUIMatter editing;

	private final DynamicForm form = new DynamicForm();

	private SelectItem clientItem;

	/**
	 * Opens the New Matter dialog
	 * 
	 * @param storeId the Store that was clicked, pre-selected
	 */
	public static void create(long storeId) {
		HealdanSettingsService.Instance.get().listClients(new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(List<GUIClient> clients) {
				HealdanMatterService.Instance.get().listStores(new DefaultAsyncCallback<>() {
					@Override
					public void handleSuccess(List<GUIValue> stores) {
						HealdanMatterService.Instance.get().listTemplates(new DefaultAsyncCallback<>() {
							@Override
							public void handleSuccess(List<GUIValue> templates) {
								new MatterDialog(null, clients, stores, templates, storeId).show();
							}
						});
					}
				});
			}
		});
	}

	/**
	 * Opens the Edit Matter dialog
	 * 
	 * @param folderId the matter's workspace
	 */
	public static void edit(long folderId) {
		HealdanMatterService.Instance.get().getByFolder(folderId, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUIMatter matter) {
				new MatterDialog(matter, null, null, null, matter.getStoreId()).show();
			}
		});
	}

	private MatterDialog(GUIMatter editing, List<GUIClient> clients, List<GUIValue> stores,
			List<GUIValue> templates, long storeId) {
		this.editing = editing;

		setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		setTitle((editing == null ? "New " : "Edit ") + HealdanLabels.singular());
		setIsModal(true);
		setShowModalMask(true);
		centerInPage();
		setAutoSize(true);

		form.setWidth(460);
		form.setNumCols(3);
		form.setColWidths(110, 260, "*");
		form.setTitleOrientation(TitleOrientation.LEFT);

		if (editing == null)
			form.setFields(newMatterFields(clients, stores, templates, storeId));
		else
			form.setFields(editFields());
		addItem(form);

		if (editing == null && clients != null && !clients.isEmpty())
			suggestNumber();
	}

	private FormItem[] newMatterFields(List<GUIClient> clients, List<GUIValue> stores, List<GUIValue> templates,
			long storeId) {
		clientItem = ItemFactory.newSelectItem(CLIENT, "Client");
		clientItem.setRequired(true);
		clientItem.setWidth(260);
		setClients(clients, clients.isEmpty() ? null : clients.get(0).getId());
		clientItem.addChangedHandler(changed -> suggestNumber());

		ButtonItem newClient = new ButtonItem("newclient", "New client\u2026");
		newClient.setStartRow(false);
		newClient.setEndRow(true);
		newClient.setAutoFit(true);
		newClient.addClickHandler(click -> ClientDialog.open(null, saved -> reloadClients(saved.getId())));

		IntegerItem number = ItemFactory.newLongItem(NUMBER, "Number", null);
		number.setRequired(true);
		number.setWidth(100);
		number.setEndRow(true);

		SelectItem store = ItemFactory.newSelectItem(STORE, "Store");
		store.setValueMap(valueMap(stores));
		store.setRequired(true);
		store.setWidth(260);
		store.setEndRow(true);
		store.setValue(Long.toString(storeId));

		SelectItem owner = ownerItem();
		owner.setValue(Long.toString(Session.get().getUser().getId()));

		SelectItem template = ItemFactory.newSelectItem(TEMPLATE, "Template");
		template.setValueMap(valueMap(templates));
		template.setRequired(true);
		template.setWidth(260);
		template.setEndRow(true);
		if (templates.size() == 1)
			template.setValue(templates.get(0).getCode());

		ButtonItem create = new ButtonItem("create", I18N.message("create"));
		create.setAutoFit(true);
		create.setColSpan(2);
		create.addClickHandler(click -> onCreate());

		return new FormItem[] { clientItem, newClient, number, nameItem(null), descriptionItem(null), store, owner,
				template, create };
	}

	private FormItem[] editFields() {
		FormItem client = ItemFactory.newStaticTextItem(CLIENT, "Client",
				editing.getClientNumber() + " \u2013 " + editing.getClientName());
		client.setEndRow(true);
		FormItem number = ItemFactory.newStaticTextItem(NUMBER, "Number", Long.toString(editing.getNumber()));
		number.setEndRow(true);

		SelectItem owner = ownerItem();
		owner.setValue(Long.toString(editing.getOwnerId()));

		ButtonItem save = new ButtonItem("save", I18N.message("save"));
		save.setAutoFit(true);
		save.setColSpan(2);
		save.addClickHandler(click -> onSave());

		return new FormItem[] { client, number, nameItem(editing.getName()), descriptionItem(editing.getDescription()),
				owner, save };
	}

	private static TextItem nameItem(String value) {
		TextItem name = ItemFactory.newTextItem(NAME, "Name", value);
		name.setRequired(true);
		name.setWidth(260);
		name.setEndRow(true);
		return name;
	}

	private static TextAreaItem descriptionItem(String value) {
		TextAreaItem description = ItemFactory.newTextAreaItem(DESCRIPTION, "Description", value);
		description.setWidth(260);
		description.setHeight(50);
		description.setEndRow(true);
		return description;
	}

	private static SelectItem ownerItem() {
		SelectItem owner = ItemFactory.newUserSelector(OWNER, "Owner", null, true, true);
		owner.setWidth(260);
		owner.setEndRow(true);
		return owner;
	}

	private static LinkedHashMap<String, String> valueMap(List<GUIValue> values) {
		LinkedHashMap<String, String> map = new LinkedHashMap<>();
		for (GUIValue value : values)
			map.put(value.getCode(), value.getValue());
		return map;
	}

	private void setClients(List<GUIClient> clients, Long selectedId) {
		LinkedHashMap<String, String> map = new LinkedHashMap<>();
		for (GUIClient client : clients)
			map.put(Long.toString(client.getId()), client.getNumber() + " \u2013 " + client.getName());
		clientItem.setValueMap(map);
		if (selectedId != null)
			clientItem.setValue(Long.toString(selectedId));
	}

	private void reloadClients(long selectedId) {
		HealdanSettingsService.Instance.get().listClients(new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(List<GUIClient> clients) {
				setClients(clients, selectedId);
				suggestNumber();
			}
		});
	}

	/** Fills in the number to suggest for the chosen client */
	private void suggestNumber() {
		String client = form.getValueAsString(CLIENT);
		if (client == null || client.isEmpty())
			return;
		HealdanMatterService.Instance.get().suggestMatterNumber(Long.parseLong(client), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(Long suggested) {
				form.getItem(NUMBER).setValue(suggested);
			}
		});
	}

	private void onCreate() {
		if (!Boolean.TRUE.equals(form.validate()))
			return;

		GUIMatter matter = new GUIMatter();
		matter.setClientId(Long.parseLong(form.getValueAsString(CLIENT)));
		matter.setNumber(Long.parseLong(form.getValueAsString(NUMBER)));
		matter.setName(form.getValueAsString(NAME));
		matter.setDescription(form.getValueAsString(DESCRIPTION));
		matter.setStoreId(Long.parseLong(form.getValueAsString(STORE)));
		matter.setOwnerId(Long.parseLong(form.getValueAsString(OWNER)));
		matter.setTemplateId(Long.valueOf(form.getValueAsString(TEMPLATE)));

		LD.contactingServer();
		HealdanMatterService.Instance.get().create(matter, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUIMatter created) {
				destroy();
				reloadStore(created.getStoreId());
				DocumentsPanel.get().openInFolder(created.getFolderId(), null);
			}
		});
	}

	private void onSave() {
		if (!Boolean.TRUE.equals(form.validate()))
			return;

		GUIMatter matter = new GUIMatter();
		matter.setId(editing.getId());
		matter.setName(form.getValueAsString(NAME));
		matter.setDescription(form.getValueAsString(DESCRIPTION));
		matter.setOwnerId(Long.parseLong(form.getValueAsString(OWNER)));

		if (matter.getOwnerId() == editing.getOwnerId()) {
			update(matter);
			return;
		}

		LD.ask("Change the owner",
				"The new owner gets full rights on this " + HealdanLabels.singular().toLowerCase()
						+ "; the old owner's entry is removed.",
				yes -> {
					if (Boolean.TRUE.equals(yes))
						update(matter);
				});
	}

	private void update(GUIMatter matter) {
		LD.contactingServer();
		HealdanMatterService.Instance.get().update(matter, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUIMatter saved) {
				destroy();
				reloadStore(saved.getStoreId());
			}
		});
	}

	/** The Store's children again, so a new or renamed workspace shows */
	private static void reloadStore(long storeId) {
		TreeNode store = FolderNavigator.get().getNode(storeId);
		if (store != null)
			FolderNavigator.get().getTree().reloadChildren(store);
	}
}
