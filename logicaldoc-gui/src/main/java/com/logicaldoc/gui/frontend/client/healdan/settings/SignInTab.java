package com.logicaldoc.gui.frontend.client.healdan.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.log.GuiLog;
import com.logicaldoc.gui.common.client.util.ItemFactory;
import com.logicaldoc.gui.common.client.util.LD;
import com.logicaldoc.gui.common.client.util.ValuesCallback;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInOptions;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInProvider;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSettingsService;
import com.smartgwt.client.types.Alignment;
import com.smartgwt.client.types.ListGridFieldType;
import com.smartgwt.client.types.SelectionStyle;
import com.smartgwt.client.types.TitleOrientation;
import com.smartgwt.client.util.SC;
import com.smartgwt.client.widgets.IButton;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.CheckboxItem;
import com.smartgwt.client.widgets.form.fields.FormItem;
import com.smartgwt.client.widgets.form.fields.SelectItem;
import com.smartgwt.client.widgets.form.fields.StaticTextItem;
import com.smartgwt.client.widgets.form.fields.TextItem;
import com.smartgwt.client.widgets.form.validator.RegExpValidator;
import com.smartgwt.client.widgets.grid.ListGrid;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.ListGridRecord;
import com.smartgwt.client.widgets.layout.HLayout;
import com.smartgwt.client.widgets.layout.VLayout;
import com.smartgwt.client.widgets.tab.Tab;

/**
 * The Healdan Settings panel's Sign-In tab (M4 spec section 10.3): the
 * sign-in providers, in the order of their buttons, and the options that
 * aren't a provider's. A provider is set up in its own dialog,
 * {@link ProviderDialog}.
 */
public class SignInTab extends Tab {

	/** The presets the tab's Add offers, by key */
	static final Map<String, String> PRESETS = new LinkedHashMap<>();

	static {
		PRESETS.put("entra", "Microsoft Entra ID");
		PRESETS.put("authentik", "Authentik");
		PRESETS.put("other", "Other OpenID Connect Provider");
	}

	private static final String ID = "id";

	private static final String LABEL = "label";

	private static final String PRESET = "preset";

	private static final String ON = "on";

	private static final String LINKED = "linked";

	private static final String PASSWORD_LABEL = "passwordlabel";

	private static final String ALERT = "adminalert";

	private static final String RECIPIENTS = "recipients";

	private static final String OUTGOING_EMAIL = "outgoingemail";

	private final VLayout pane = new VLayout() {
		@Override
		protected void onDraw() {
			refresh();
		}
	};

	private ListGrid providers;

	private DynamicForm options;

	/** What the tab last loaded */
	private GUISignInOptions current;

	public SignInTab() {
		super("Sign-In");
		pane.setWidth100();
		pane.setHeight100();
		pane.setMembersMargin(6);
		pane.setMargin(5);
		setPane(pane);
	}

	/** Loads the providers and the options again, and shows them */
	void refresh() {
		HealdanSettingsService.Instance.get().loadSignIn(new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUISignInOptions loaded) {
				current = loaded;
				if (providers == null)
					build();
				show(loaded);
			}
		});
	}

	private void build() {
		Label providersTitle = new Label("<b>Sign-In Providers</b>");
		providersTitle.setHeight(20);

		Label about = new Label("Each provider is a button on the sign-in page, in this order. People sign in through "
				+ "it with their account there. The password link stays on the page, for admin and other "
				+ "accounts that aren't linked.");
		about.setWidth(640);
		about.setHeight(1);
		about.setAutoHeight();

		IButton add = button("Add", click -> add());
		IButton edit = button("Edit", click -> editSelected());
		IButton remove = button("Remove", click -> removeSelected());
		IButton up = button("Move Up", click -> move(-1));
		IButton down = button("Move Down", click -> move(1));

		HLayout buttons = new HLayout();
		buttons.setMembersMargin(5);
		buttons.setHeight(1);
		buttons.setMembers(add, edit, remove, up, down);

		ListGridField label = new ListGridField(LABEL, "Label");
		label.setWidth("*");
		label.setEscapeHTML(true);
		ListGridField id = new ListGridField(ID, "Id", 120);
		id.setEscapeHTML(true);
		ListGridField preset = new ListGridField(PRESET, "Preset", 190);
		ListGridField on = new ListGridField(ON, "On", 50);
		on.setType(ListGridFieldType.BOOLEAN);
		on.setAlign(Alignment.CENTER);
		ListGridField linked = new ListGridField(LINKED, "Linked Accounts", 120);
		linked.setType(ListGridFieldType.INTEGER);

		providers = new ListGrid();
		providers.setWidth(640);
		providers.setHeight(180);
		providers.setSelectionType(SelectionStyle.SINGLE);
		providers.setEmptyMessage("No sign-in providers yet: people sign in with their passwords");
		providers.setFields(label, id, preset, on, linked);
		providers.addDoubleClickHandler(click -> editSelected());

		Label optionsTitle = new Label("<b>Options</b>");
		optionsTitle.setHeight(20);

		options = new DynamicForm();
		options.setWidth(640);
		options.setColWidths(230, "*");
		options.setTitleOrientation(TitleOrientation.LEFT);

		TextItem passwordLabel = ItemFactory.newTextItem(PASSWORD_LABEL, "Password Link's Label", null);
		passwordLabel.setRequired(true);
		passwordLabel.setLength(60);
		passwordLabel.setWidth(260);
		passwordLabel.setHint("on the sign-in page, below the providers' buttons");

		CheckboxItem alert = ItemFactory.newCheckbox(ALERT, "Email When admin Signs In with Its Password");
		alert.setHint("only while a provider is on");

		TextItem recipients = ItemFactory.newTextItem(RECIPIENTS, "Alert Recipients", null);
		recipients.setWidth(260);
		recipients.setHint("usernames, separated by commas");

		StaticTextItem outgoing = ItemFactory.newStaticTextItem(OUTGOING_EMAIL, "Outgoing Email", null);
		outgoing.setShouldSaveValue(false);

		options.setItems(passwordLabel, alert, recipients, outgoing);

		IButton save = button("Save", click -> saveOptions());
		HLayout optionButtons = new HLayout();
		optionButtons.setMembersMargin(5);
		optionButtons.setHeight(1);
		optionButtons.setMembers(save);

		pane.setMembers(providersTitle, about, buttons, providers, optionsTitle, options, optionButtons);
	}

	private static IButton button(String title, com.smartgwt.client.widgets.events.ClickHandler handler) {
		IButton button = new IButton(title);
		button.setAutoFit(true);
		button.addClickHandler(handler);
		return button;
	}

	private void show(GUISignInOptions loaded) {
		String selected = providers.getSelectedRecord() != null
				? providers.getSelectedRecord().getAttributeAsString(ID)
				: null;
		ListGridRecord[] records = new ListGridRecord[loaded.getProviders().length];
		for (int i = 0; i < records.length; i++) {
			GUISignInProvider provider = loaded.getProviders()[i];
			records[i] = new ListGridRecord();
			records[i].setAttribute(ID, provider.getId());
			records[i].setAttribute(LABEL, provider.getLabel());
			records[i].setAttribute(PRESET, presetName(provider.getPreset()));
			records[i].setAttribute(ON, provider.isEnabled());
			records[i].setAttribute(LINKED, provider.getLinked());
		}
		providers.setData(records);
		if (selected != null)
			for (int i = 0; i < records.length; i++)
				if (selected.equals(records[i].getAttributeAsString(ID)))
					providers.selectRecord(i);

		options.setValue(PASSWORD_LABEL, loaded.getPasswordLabel());
		options.setValue(ALERT, loaded.isAdminAlert());
		options.setValue(RECIPIENTS, String.join(", ", loaded.getRecipients()));
		options.setValue(OUTGOING_EMAIL, loaded.isOutgoingEmail() ? "Set up: the alert can be sent"
				: "Needs outgoing email, which isn't set up");
		options.rememberValues();
	}

	static String presetName(String key) {
		String name = PRESETS.get(key);
		return name != null ? name : PRESETS.get("other");
	}

	private GUISignInProvider selected() {
		ListGridRecord record = providers.getSelectedRecord();
		if (record == null || current == null)
			return null;
		String id = record.getAttributeAsString(ID);
		return Arrays.stream(current.getProviders()).filter(p -> p.getId().equals(id)).findFirst().orElse(null);
	}

	/** Asks for the new provider's id and preset, then opens its dialog */
	private void add() {
		TextItem id = ItemFactory.newTextItem(ID, "Id", null);
		id.setRequired(true);
		id.setLength(32);
		id.setHint("lower-case letters, digits and hyphens; it's in the redirect address, so it can't change");
		RegExpValidator idRule = new RegExpValidator("^[a-z0-9-]{1,32}$");
		idRule.setErrorMessage("Use only lower-case letters, digits and hyphens, at most 32");
		id.setValidators(idRule);

		SelectItem preset = ItemFactory.newSelectItem(PRESET, "Preset");
		preset.setEscapeHTML(true);
		preset.setValueMap(new LinkedHashMap<>(PRESETS));
		preset.setValue("entra");
		preset.setRequired(true);

		List<FormItem> items = new ArrayList<>();
		items.add(id);
		items.add(preset);
		LD.askForValues("Add a Sign-In Provider", null, items, 420, new ValuesCallback() {
			@Override
			public void execute(Map<String, Object> values) {
				String newId = values.get(ID) != null ? values.get(ID).toString().trim() : "";
				String key = values.get(PRESET) != null ? values.get(PRESET).toString() : "other";
				HealdanSettingsService.Instance.get().newProvider(newId, key, new DefaultAsyncCallback<>() {
					@Override
					public void handleSuccess(GUISignInProvider fresh) {
						ProviderDialog.open(fresh, current.getGroups(), SignInTab.this::refresh);
					}
				});
			}

			@Override
			public void execute(String value) {
				// Two values: execute(Map) has them
			}
		});
	}

	private void editSelected() {
		GUISignInProvider provider = selected();
		if (provider != null)
			ProviderDialog.open(provider, current.getGroups(), this::refresh);
	}

	/** Removes the selected provider, which only one with no linked accounts can be (spec section 7.7) */
	private void removeSelected() {
		GUISignInProvider provider = selected();
		if (provider == null)
			return;
		String label = SafeHtmlUtils.htmlEscape(provider.getLabel());
		HealdanSettingsService.Instance.get().countLinked(provider.getId(), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(Integer linked) {
				if (linked > 0) {
					SC.warn(label + " has " + ProviderDialog.accounts(linked) + " linked, so it can't be removed. "
							+ "Turn it off, or open it and Unlink Everyone, first. Turning it off keeps the links.");
					return;
				}
				LD.ask("Remove " + label,
						"Remove " + label + "? No account is linked to it. Its button, its settings and its group map "
								+ "go. The record of the accounts it once linked stays.",
						yes -> {
							if (Boolean.TRUE.equals(yes))
								HealdanSettingsService.Instance.get().removeProvider(provider.getId(),
										new DefaultAsyncCallback<>() {
											@Override
											public void handleSuccess(Void result) {
												GuiLog.info("Removed " + label, null);
												refresh();
											}
										});
						});
			}
		});
	}

	/** Moves the selected provider's button up or down by one */
	private void move(int by) {
		ListGridRecord record = providers.getSelectedRecord();
		if (record == null)
			return;
		ListGridRecord[] records = providers.getRecords();
		List<String> ids = new ArrayList<>();
		for (ListGridRecord r : records)
			ids.add(r.getAttributeAsString(ID));
		int from = ids.indexOf(record.getAttributeAsString(ID));
		int to = from + by;
		if (from < 0 || to < 0 || to >= ids.size())
			return;
		ids.add(to, ids.remove(from));

		HealdanSettingsService.Instance.get().reorderProviders(ids.toArray(new String[0]),
				new DefaultAsyncCallback<>() {
					@Override
					public void handleSuccess(Void result) {
						refresh();
					}
				});
	}

	private void saveOptions() {
		if (!Boolean.TRUE.equals(options.validate()))
			return;

		List<String> recipients = new ArrayList<>();
		String typed = options.getValueAsString(RECIPIENTS);
		if (typed != null)
			for (String name : typed.split(","))
				if (!name.trim().isEmpty())
					recipients.add(name.trim());
		boolean alert = Boolean.TRUE.equals(options.getValue(ALERT));

		LD.contactingServer();
		HealdanSettingsService.Instance.get().saveSignInOptions(options.getValueAsString(PASSWORD_LABEL), alert,
				recipients.toArray(new String[0]), new DefaultAsyncCallback<>() {
					@Override
					public void handleSuccess(Void result) {
						GuiLog.info("Sign-in options saved", null);
						refresh();
					}
				});
	}
}
