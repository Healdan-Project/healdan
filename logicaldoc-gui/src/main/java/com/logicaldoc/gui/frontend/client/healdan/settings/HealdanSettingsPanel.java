package com.logicaldoc.gui.frontend.client.healdan.settings;

import java.util.LinkedHashMap;
import java.util.List;

import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.i18n.I18N;
import com.logicaldoc.gui.common.client.log.GuiLog;
import com.logicaldoc.gui.common.client.util.ItemFactory;
import com.logicaldoc.gui.common.client.util.LD;
import com.logicaldoc.gui.frontend.client.administration.AdminPanel;
import com.logicaldoc.gui.frontend.client.healdan.HealdanLabels;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIClient;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIHealdanSettings;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSettingsService;
import com.smartgwt.client.types.ListGridFieldType;
import com.smartgwt.client.types.SelectionStyle;
import com.smartgwt.client.types.TitleOrientation;
import com.smartgwt.client.util.SC;
import com.smartgwt.client.widgets.IButton;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.IntegerItem;
import com.smartgwt.client.widgets.form.fields.SelectItem;
import com.smartgwt.client.widgets.form.fields.SpinnerItem;
import com.smartgwt.client.widgets.form.fields.TextItem;
import com.smartgwt.client.widgets.grid.ListGrid;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.ListGridRecord;
import com.smartgwt.client.widgets.layout.HLayout;

/**
 * Healdan's settings and its client list, under Administration (spec 8.6)
 */
public class HealdanSettingsPanel extends AdminPanel {

	private static final String LABEL = "matterlabel";

	private static final String PLURAL = "matterlabelplural";

	private static final String NUMBERING = "numbering";

	private static final String CLASS = "defaultclass";

	private static final String RECENT = "recentdays";

	private static final String NEXT = "nextdocnumber";

	private static final String ID = "id";

	private static final String NUMBER = "number";

	private static final String NAME = "name";

	private DynamicForm form;

	private ListGrid clients;

	/** The settings as last loaded or saved */
	private GUIHealdanSettings current;

	public HealdanSettingsPanel() {
		super("Healdan settings");
	}

	@Override
	protected void onDraw() {
		HealdanSettingsService.Instance.get().load(new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUIHealdanSettings settings) {
				init(settings);
			}
		});
	}

	private void init(GUIHealdanSettings settings) {
		current = settings;

		form = new DynamicForm();
		form.setWidth(560);
		form.setColWidths(190, "*");
		form.setTitleOrientation(TitleOrientation.LEFT);

		TextItem label = ItemFactory.newTextItem(LABEL, "Matter label", settings.getMatterLabel());
		label.setRequired(true);
		label.setLength(50);
		label.setHint("singular, e.g. Matter or Project");

		TextItem plural = ItemFactory.newTextItem(PLURAL, "Plural label", settings.getMatterLabelPlural());
		plural.setRequired(true);
		plural.setLength(50);
		plural.setHint("e.g. Matters or Projects");

		SelectItem numbering = ItemFactory.newSelectItem(NUMBERING, "Numbering");
		LinkedHashMap<String, String> modes = new LinkedHashMap<>();
		modes.put("PER_CLIENT", "Per client (998.1 and 999.1 can both exist)");
		modes.put("SYSTEM_WIDE", "System-wide (one number space for all clients)");
		numbering.setValueMap(modes);
		numbering.setValue(settings.getNumberingMode());
		numbering.setWidth(340);
		numbering.setRequired(true);

		SelectItem docClass = ItemFactory.newSelectItem(CLASS, "Default Class");
		LinkedHashMap<String, String> classes = new LinkedHashMap<>();
		for (String value : settings.getClasses())
			classes.put(value, value);
		classes.put(settings.getDefaultClass(), settings.getDefaultClass());
		docClass.setValueMap(classes);
		docClass.setValue(settings.getDefaultClass());
		docClass.setWidth(220);
		docClass.setRequired(true);

		SpinnerItem recent = ItemFactory.newSpinnerItem(RECENT,
				"Recent " + settings.getMatterLabelPlural() + " window", settings.getRecentMattersDays(), 1, 3650);
		recent.setHint("days");
		recent.setRequired(true);

		IntegerItem next = ItemFactory.newLongItem(NEXT, "Next doc #", settings.getNextDocNumber());
		next.setRequired(true);
		next.setWidth(120);
		next.setHint("Can only be raised. Lowest allowed: " + settings.getNextDocNumber());

		form.setItems(label, plural, numbering, docClass, recent, next);

		Label clientsTitle = new Label("<b>Clients</b>");
		clientsTitle.setHeight(20);

		IButton add = new IButton("Add client");
		add.setAutoFit(true);
		add.addClickHandler(click -> ClientDialog.open(null, saved -> refreshClients()));

		IButton edit = new IButton("Edit");
		edit.setAutoFit(true);
		edit.addClickHandler(click -> editSelected());

		HLayout clientButtons = new HLayout();
		clientButtons.setMembersMargin(5);
		clientButtons.setHeight(1);
		clientButtons.setMembers(add, edit);

		ListGridField numberField = new ListGridField(NUMBER, "Number", 90);
		numberField.setType(ListGridFieldType.INTEGER);
		ListGridField nameField = new ListGridField(NAME, "Name");
		nameField.setWidth("*");

		clients = new ListGrid();
		clients.setWidth(420);
		clients.setHeight(200);
		clients.setSelectionType(SelectionStyle.SINGLE);
		clients.setEmptyMessage("No clients yet");
		clients.setFields(numberField, nameField);
		clients.addDoubleClickHandler(click -> editSelected());

		IButton save = new IButton(I18N.message("save"));
		save.addClickHandler(click -> save());

		HLayout buttons = new HLayout();
		buttons.setMembersMargin(5);
		buttons.setLayoutTopMargin(10);
		buttons.setMembers(save);

		body.setMembersMargin(6);
		body.setMembers(form, clientsTitle, clientButtons, clients, buttons);

		refreshClients();
	}

	private void refreshClients() {
		HealdanSettingsService.Instance.get().listClients(new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(List<GUIClient> list) {
				ListGridRecord[] records = new ListGridRecord[list.size()];
				for (int i = 0; i < records.length; i++) {
					GUIClient client = list.get(i);
					records[i] = new ListGridRecord();
					records[i].setAttribute(ID, Long.toString(client.getId()));
					records[i].setAttribute(NUMBER, client.getNumber());
					records[i].setAttribute(NAME, client.getName());
				}
				clients.setData(records);
			}
		});
	}

	private void editSelected() {
		ListGridRecord selected = clients.getSelectedRecord();
		if (selected == null)
			return;

		GUIClient client = new GUIClient();
		client.setId(Long.parseLong(selected.getAttributeAsString(ID)));
		client.setNumber(Long.parseLong(selected.getAttributeAsString(NUMBER)));
		client.setName(selected.getAttributeAsString(NAME));
		ClientDialog.open(client, saved -> refreshClients());
	}

	private void save() {
		if (!Boolean.TRUE.equals(form.validate()))
			return;

		GUIHealdanSettings settings = new GUIHealdanSettings();
		settings.setMatterLabel(form.getValueAsString(LABEL));
		settings.setMatterLabelPlural(form.getValueAsString(PLURAL));
		settings.setNumberingMode(form.getValueAsString(NUMBERING));
		settings.setDefaultClass(form.getValueAsString(CLASS));
		settings.setRecentMattersDays(Integer.parseInt(form.getValueAsString(RECENT)));

		Long next;
		try {
			next = Long.valueOf(form.getValueAsString(NEXT));
		} catch (NumberFormatException e) {
			SC.warn("The next doc # must be a whole number.");
			return;
		}
		// Sent only when changed, so documents filed meanwhile don't make it "lower"
		if (next.equals(current.getNextDocNumber())) {
			send(settings);
			return;
		}

		settings.setNextDocNumber(next);
		LD.ask("Raise the next doc #",
				"Raise the next doc # from " + current.getNextDocNumber() + " to " + next
						+ "? It can't be lowered again.",
				yes -> {
					if (Boolean.TRUE.equals(yes))
						send(settings);
				});
	}

	private void send(GUIHealdanSettings settings) {
		LD.contactingServer();
		HealdanSettingsService.Instance.get().save(settings, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUIHealdanSettings saved) {
				current = saved;
				HealdanLabels.set(saved.getMatterLabel(), saved.getMatterLabelPlural());
				form.getItem(NEXT).setValue(saved.getNextDocNumber());
				form.getItem(NEXT).setHint("Can only be raised. Lowest allowed: " + saved.getNextDocNumber());
				form.getItem(RECENT).setTitle("Recent " + saved.getMatterLabelPlural() + " window");
				form.markForRedraw();
				GuiLog.info(I18N.message("settingssaved"), null);
			}
		});
	}
}
