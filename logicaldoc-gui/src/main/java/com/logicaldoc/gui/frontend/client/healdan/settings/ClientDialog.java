package com.logicaldoc.gui.frontend.client.healdan.settings;

import java.util.function.Consumer;

import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.i18n.I18N;
import com.logicaldoc.gui.common.client.util.ItemFactory;
import com.logicaldoc.gui.common.client.util.LD;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIClient;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSettingsService;
import com.smartgwt.client.types.HeaderControls;
import com.smartgwt.client.widgets.Window;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.ButtonItem;
import com.smartgwt.client.widgets.form.fields.FormItem;
import com.smartgwt.client.widgets.form.fields.IntegerItem;
import com.smartgwt.client.widgets.form.fields.TextItem;

/**
 * Creates a client, or renames one (spec 8.5). A client's number can't change
 * once it is saved.
 */
public class ClientDialog extends Window {

	private static final String NUMBER = "number";

	private static final String NAME = "name";

	/**
	 * Opens the dialog
	 *
	 * @param client the client to rename, or null to create one
	 * @param onSaved what to do with the client once it is saved
	 */
	public static void open(GUIClient client, Consumer<GUIClient> onSaved) {
		if (client != null) {
			new ClientDialog(client, client.getNumber(), onSaved).show();
			return;
		}

		HealdanSettingsService.Instance.get().suggestClientNumber(new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(Long suggested) {
				new ClientDialog(null, suggested, onSaved).show();
			}
		});
	}

	private ClientDialog(GUIClient client, long number, Consumer<GUIClient> onSaved) {
		setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		setTitle(client == null ? "New client" : "Edit client");
		setIsModal(true);
		setShowModalMask(true);
		centerInPage();
		setAutoSize(true);

		DynamicForm form = new DynamicForm();
		form.setWidth(340);
		form.setColWidths(80, "*");

		FormItem numberItem;
		if (client == null) {
			IntegerItem item = ItemFactory.newLongItem(NUMBER, "Number", number);
			item.setRequired(true);
			item.setWidth(100);
			item.setHint("suggested");
			numberItem = item;
		} else {
			numberItem = ItemFactory.newStaticTextItem(NUMBER, "Number", Long.toString(number));
		}

		TextItem name = ItemFactory.newTextItem(NAME, "Name", client != null ? client.getName() : null);
		name.setRequired(true);
		name.setLength(255);
		name.setWidth(220);

		ButtonItem save = new ButtonItem();
		save.setTitle(I18N.message("save"));
		save.setAutoFit(true);
		save.addClickHandler(click -> {
			if (!Boolean.TRUE.equals(form.validate()))
				return;

			GUIClient saving = new GUIClient();
			if (client != null) {
				saving.setId(client.getId());
				saving.setNumber(client.getNumber());
			} else {
				saving.setNumber(Long.parseLong(form.getValueAsString(NUMBER)));
			}
			saving.setName(form.getValueAsString(NAME));

			LD.contactingServer();
			HealdanSettingsService.Instance.get().saveClient(saving, new DefaultAsyncCallback<>() {
				@Override
				public void handleSuccess(GUIClient saved) {
					destroy();
					onSaved.accept(saved);
				}
			});
		});

		form.setFields(numberItem, name, save);
		addItem(form);
	}
}
