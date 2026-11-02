package com.logicaldoc.gui.frontend.client.healdan.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.beans.GUIGroup;
import com.logicaldoc.gui.common.client.grid.DateListGridField;
import com.logicaldoc.gui.common.client.log.GuiLog;
import com.logicaldoc.gui.common.client.util.ItemFactory;
import com.logicaldoc.gui.common.client.util.LD;
import com.logicaldoc.gui.common.client.widgets.CopyTextFormItemIcon;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIGroupMapping;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUILinkedAccount;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInProvider;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSettingsService;
import com.smartgwt.client.types.HeaderControls;
import com.smartgwt.client.types.ListGridEditEvent;
import com.smartgwt.client.types.Overflow;
import com.smartgwt.client.types.SelectionStyle;
import com.smartgwt.client.types.TitleOrientation;
import com.smartgwt.client.util.SC;
import com.smartgwt.client.widgets.Canvas;
import com.smartgwt.client.widgets.HTMLFlow;
import com.smartgwt.client.widgets.IButton;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.Window;
import com.smartgwt.client.widgets.events.ClickHandler;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.CheckboxItem;
import com.smartgwt.client.widgets.form.fields.PasswordItem;
import com.smartgwt.client.widgets.form.fields.SectionItem;
import com.smartgwt.client.widgets.form.fields.SelectItem;
import com.smartgwt.client.widgets.form.fields.StaticTextItem;
import com.smartgwt.client.widgets.form.fields.TextAreaItem;
import com.smartgwt.client.widgets.form.fields.TextItem;
import com.smartgwt.client.widgets.grid.ListGrid;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.ListGridRecord;
import com.smartgwt.client.widgets.layout.HLayout;
import com.smartgwt.client.widgets.layout.VLayout;

/**
 * Adds or edits one sign-in provider (M4 spec section 10.3): its settings,
 * its claims, its group map, a Check against what the provider publishes, and
 * the accounts linked to it, with Unlink and Unlink Everyone. The client
 * secret is never shown: its field starts empty, and only a value typed into
 * it replaces the one stored.
 */
public class ProviderDialog extends Window {

	private static final String LABEL = "label";

	private static final String ISSUER = "issuer";

	private static final String CLIENT_ID = "clientid";

	private static final String SECRET = "clientsecret";

	private static final String ENABLED = "enabled";

	private static final String LINK_BY_EMAIL = "linkbyemail";

	private static final String REDIRECT = "redirect";

	private static final String IDENTITY = "identityclaim";

	private static final String USERNAME = "usernameclaim";

	private static final String EMAIL = "emailclaim";

	private static final String GROUPS = "groupsclaim";

	private static final String VALUE = "value";

	private static final String GROUP_ID = "groupid";

	private static final String DELETED = "deleted";

	private static final String DELETED_NAME = "deletedname";

	private static final String USER_ID = "userid";

	private static final String USERNAME_FIELD = "username";

	private static final String NAME = "name";

	private static final String HOW = "how";

	private static final String LINKED = "linked";

	private static final String LAST_SIGN_IN = "lastsignin";

	private static final String UNLINK = "unlink";

	/** The issuer each preset's form suggests (spec section 10.2), shown in the empty field */
	private static final Map<String, String> ISSUER_HINTS = new HashMap<>();

	static {
		ISSUER_HINTS.put("entra", "https://login.microsoftonline.com/TENANT-ID/v2.0");
		ISSUER_HINTS.put("authentik", "https://AUTHENTIK-HOST/application/o/SLUG/");
		ISSUER_HINTS.put("other", "https://...");
	}

	private final GUISignInProvider provider;

	/** The groups a provider group can be mapped to, by id */
	private final LinkedHashMap<String, String> mappable = new LinkedHashMap<>();

	private final GUIGroup[] groups;

	private final Runnable onChange;

	private final DynamicForm form = new DynamicForm();

	private ListGrid groupMap;

	private ListGrid linkedGrid;

	/**
	 * Opens the dialog
	 *
	 * @param provider the provider to edit, or one the tab's Add started
	 * @param groups the Healdan groups a provider group can be mapped to
	 * @param onChange what to do once something is saved, removed or unlinked
	 */
	public static void open(GUISignInProvider provider, GUIGroup[] groups, Runnable onChange) {
		new ProviderDialog(provider, groups, onChange).show();
	}

	private ProviderDialog(GUISignInProvider provider, GUIGroup[] groups, Runnable onChange) {
		this.provider = provider;
		this.groups = groups;
		this.onChange = onChange;
		for (GUIGroup group : groups)
			mappable.put(Long.toString(group.getId()), group.getName());

		setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		setTitle(provider.isAdding() ? "Add a Sign-In Provider" : SafeHtmlUtils.htmlEscape(provider.getLabel()));
		setIsModal(true);
		setShowModalMask(true);
		setWidth(720);
		setHeight(provider.isAdding() ? 640 : 760);
		setCanDragResize(true);
		centerInPage();

		VLayout content = new VLayout();
		content.setMargin(10);
		content.setMembersMargin(8);
		content.setWidth100();
		content.setHeight100();
		content.setOverflow(Overflow.AUTO);

		prepareForm();
		content.addMember(form);
		content.addMember(title("Group Map"));
		content.addMember(note("Only people in a mapped provider group can sign in, and each sign-in puts the person "
				+ "in the Healdan groups their provider groups map to, and takes them out of the other mapped "
				+ "groups. A provider group is an Entra ID group's object id or app role, or an Authentik group's "
				+ "name. Rows whose Healdan group was deleted are ignored, and go when you save."));
		content.addMember(prepareGroupMap());
		content.addMember(buttons(button("Add Row", click -> addRow()), button("Remove Row", click -> removeRow())));
		content.addMember(buttons(button("Save", click -> save()), button("Check", click -> check()),
				button("Close", click -> destroy())));

		if (!provider.isAdding()) {
			content.addMember(title("Linked Accounts"));
			content.addMember(note("Unlinking turns an account back into a password account, with a temporary "
					+ "password the person changes at their next sign-in. It keeps its documents and groups, and its "
					+ "devices are signed out. Signing in through the provider again links it again."));
			content.addMember(prepareLinked());
			content.addMember(buttons(button("Unlink Everyone", click -> unlinkEveryone())));
			refreshLinked();
		}

		addItem(content);
	}

	private void prepareForm() {
		form.setWidth(660);
		form.setColWidths(150, "*");
		form.setTitleOrientation(TitleOrientation.LEFT);
		form.setWrapItemTitles(false);

		StaticTextItem id = ItemFactory.newStaticTextItem("id", "Id", provider.getId());
		id.setEscapeHTML(true);
		id.setShouldSaveValue(false);

		StaticTextItem preset = ItemFactory.newStaticTextItem("preset", "Preset",
				SignInTab.presetName(provider.getPreset()));
		preset.setShouldSaveValue(false);

		TextItem label = ItemFactory.newTextItem(LABEL, "Button Label", provider.getLabel());
		label.setRequired(true);
		label.setLength(60);
		label.setWidth(300);

		TextItem issuer = ItemFactory.newTextItemPreventAutocomplete(ISSUER, "Issuer", provider.getIssuer());
		issuer.setRequired(true);
		issuer.setLength(1000);
		issuer.setWidth(480);
		String issuerHint = ISSUER_HINTS.get(provider.getPreset());
		issuer.setHint(issuerHint != null ? issuerHint : ISSUER_HINTS.get("other"));
		issuer.setShowHintInField(true);

		TextItem clientId = ItemFactory.newTextItemPreventAutocomplete(CLIENT_ID, "Client ID", provider.getClientId());
		clientId.setRequired(true);
		clientId.setLength(255);
		clientId.setWidth(300);

		// Never the stored secret: the field starts empty, and only what's typed in it replaces the secret
		PasswordItem secret = ItemFactory.newPasswordItemPreventAutocomplete(SECRET, "Client Secret", null);
		secret.setWidth(300);
		secret.setHint(provider.isHasSecret() ? "Set: leave empty to keep it" : "Not Set");

		CheckboxItem enabled = ItemFactory.newCheckbox(ENABLED, "On");
		enabled.setValue(provider.isEnabled());
		enabled.setHint("off hides its button and stops its sign-ins; nothing is unlinked");

		CheckboxItem linkByEmail = ItemFactory.newCheckbox(LINK_BY_EMAIL, "Link by Email");
		linkByEmail.setValue(provider.isLinkByEmail());
		linkByEmail.setHint("a first sign-in links to the account with the same email, if there's exactly one");

		StaticTextItem redirect = ItemFactory.newStaticTextItem(REDIRECT, "Redirect Address", provider.getRedirect());
		redirect.setEscapeHTML(true);
		redirect.setShouldSaveValue(false);
		redirect.setIcons(new CopyTextFormItemIcon(provider.getRedirect(), "Copy"));
		redirect.setHint("register this at the provider");

		TextItem identity = claim(IDENTITY, "Identity Claim", provider.getIdentityClaim());
		if (provider.getLinked() > 0) {
			identity.setDisabled(true);
			identity.setHint("can't change while accounts are linked");
		} else {
			identity.setHint("names the person, and never changes");
		}
		TextItem username = claim(USERNAME, "Username Claim", provider.getUsernameClaim());
		username.setHint("a new account's username");
		TextItem email = claim(EMAIL, "Email Claim", provider.getEmailClaim());
		TextItem groupsClaim = claim(GROUPS, "Groups Claim", provider.getGroupsClaim());
		groupsClaim.setHint("the provider groups the map reads");

		SectionItem claims = new SectionItem("claims");
		claims.setDefaultValue("Claims");
		claims.setSectionExpanded(false);
		claims.setItemIds(IDENTITY, USERNAME, EMAIL, GROUPS);

		form.setItems(id, preset, label, issuer, clientId, secret, enabled, linkByEmail, redirect, claims, identity,
				username, email, groupsClaim);
		form.rememberValues();
	}

	private static TextItem claim(String name, String title, String value) {
		TextItem item = ItemFactory.newTextItemPreventAutocomplete(name, title, value);
		item.setRequired(true);
		item.setLength(100);
		item.setWidth(220);
		return item;
	}

	private ListGrid prepareGroupMap() {
		ListGridField value = new ListGridField(VALUE, "Provider Group");
		value.setWidth("*");
		value.setRequired(true);
		value.setEscapeHTML(true);

		ListGridField group = new ListGridField(GROUP_ID, "Healdan Group", 260);
		SelectItem picker = new SelectItem();
		picker.setValueMap(mappable);
		group.setEditorProperties(picker);
		group.setRequired(true);
		group.setCellFormatter((cell, rec, rowNum, colNum) -> {
			String name = cell != null ? mappable.get(cell.toString()) : null;
			if (name != null)
				return SafeHtmlUtils.htmlEscape(name);
			// Until another group is picked for the row
			if (Boolean.TRUE.equals(rec.getAttributeAsBoolean(DELETED)))
				return SafeHtmlUtils.htmlEscape(String.valueOf(rec.getAttributeAsString(DELETED_NAME)))
						+ " <i>(deleted)</i>";
			return "";
		});

		groupMap = new ListGrid();
		groupMap.setWidth(660);
		groupMap.setHeight(150);
		groupMap.setSelectionType(SelectionStyle.SINGLE);
		groupMap.setCanEdit(true);
		groupMap.setEditEvent(ListGridEditEvent.CLICK);
		groupMap.setAutoSaveEdits(true);
		groupMap.setEmptyMessage("No rows: nobody can sign in through this provider until a group is mapped");
		groupMap.setFields(value, group);

		List<ListGridRecord> records = new ArrayList<>();
		for (GUIGroupMapping mapping : provider.getGroups()) {
			ListGridRecord rec = new ListGridRecord();
			rec.setAttribute(VALUE, mapping.getValue());
			rec.setAttribute(GROUP_ID, Long.toString(mapping.getGroupId()));
			rec.setAttribute(DELETED, mapping.isDeleted());
			rec.setAttribute(DELETED_NAME, mapping.getGroupName());
			records.add(rec);
		}
		groupMap.setData(records.toArray(new ListGridRecord[0]));
		return groupMap;
	}

	private void addRow() {
		groupMap.startEditingNew();
	}

	private void removeRow() {
		groupMap.removeSelectedData();
	}

	/** The group map as it is now: blank rows and rows of deleted groups are left out */
	private GUIGroupMapping[] mappings() {
		List<GUIGroupMapping> rows = new ArrayList<>();
		for (ListGridRecord rec : groupMap.getRecords()) {
			String value = rec.getAttributeAsString(VALUE);
			String group = rec.getAttributeAsString(GROUP_ID);
			boolean blank = (value == null || value.trim().isEmpty()) && (group == null || group.isEmpty());
			// A deleted group's row is ignored, and goes, unless another group was picked for it
			if (blank || Boolean.TRUE.equals(rec.getAttributeAsBoolean(DELETED)) && !mappable.containsKey(group))
				continue;
			rows.add(new GUIGroupMapping(value, group != null && !group.isEmpty() ? Long.parseLong(group) : 0L));
		}
		return rows.toArray(new GUIGroupMapping[0]);
	}

	private boolean groupMapChanged() {
		GUIGroupMapping[] now = mappings();
		List<String> before = new ArrayList<>();
		for (GUIGroupMapping mapping : provider.getGroups())
			if (!mapping.isDeleted())
				before.add(mapping.getValue() + "\n" + mapping.getGroupId());
		List<String> after = new ArrayList<>();
		for (GUIGroupMapping mapping : now)
			after.add(mapping.getValue() + "\n" + mapping.getGroupId());
		return !before.equals(after);
	}

	private GUISignInProvider collect() {
		GUISignInProvider saving = new GUISignInProvider();
		saving.setId(provider.getId());
		saving.setPreset(provider.getPreset());
		saving.setAdding(provider.isAdding());
		saving.setLabel(form.getValueAsString(LABEL));
		saving.setIssuer(form.getValueAsString(ISSUER));
		saving.setClientId(form.getValueAsString(CLIENT_ID));
		saving.setEnabled(Boolean.TRUE.equals(form.getValue(ENABLED)));
		saving.setLinkByEmail(Boolean.TRUE.equals(form.getValue(LINK_BY_EMAIL)));
		saving.setIdentityClaim(form.getValueAsString(IDENTITY));
		saving.setUsernameClaim(form.getValueAsString(USERNAME));
		saving.setEmailClaim(form.getValueAsString(EMAIL));
		saving.setGroupsClaim(form.getValueAsString(GROUPS));
		saving.setGroups(mappings());
		return saving;
	}

	private void save() {
		groupMap.endEditing();
		if (!Boolean.TRUE.equals(form.validate()))
			return;

		GUISignInProvider saving = collect();
		String secret = form.getValueAsString(SECRET);
		String typed = secret != null && !secret.trim().isEmpty() ? secret : null;

		// Turning a provider off says first whom it affects (spec section 7.7)
		if (!provider.isAdding() && provider.isEnabled() && !saving.isEnabled()) {
			HealdanSettingsService.Instance.get().countLinked(provider.getId(), new DefaultAsyncCallback<>() {
				@Override
				public void handleSuccess(Integer linked) {
					if (linked == 0) {
						send(saving, typed);
						return;
					}
					String label = SafeHtmlUtils.htmlEscape(provider.getLabel());
					LD.ask("Turn " + label + " Off", people(linked) + " linked to " + label
							+ " can't sign in through it while it's off. Its button leaves the sign-in page, and "
							+ "Healdan stops contacting it. Nothing is unlinked, and Healdan Helpers already signed in "
							+ "keep working. Turn it off?", 440, yes -> {
								if (Boolean.TRUE.equals(yes))
									send(saving, typed);
							});
				}
			});
			return;
		}
		send(saving, typed);
	}

	private void send(GUISignInProvider saving, String secret) {
		LD.contactingServer();
		HealdanSettingsService.Instance.get().saveProvider(saving, secret, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUISignInProvider saved) {
				GuiLog.info("Saved " + SafeHtmlUtils.htmlEscape(saved.getLabel()), null);
				onChange.run();
				// Opened again as the provider now is: the secret's field empty again, Check and the linked accounts
				destroy();
				open(saved, groups, onChange);
			}
		});
	}

	private void check() {
		if (provider.isAdding()) {
			SC.say("Save the provider first: Check tests its saved settings.");
			return;
		}
		groupMap.endEditing();
		if (Boolean.TRUE.equals(form.valuesHaveChanged()) || groupMapChanged()) {
			SC.say("Save your changes first: Check tests the saved settings.");
			return;
		}

		LD.contactingServer();
		HealdanSettingsService.Instance.get().checkProvider(provider.getId(), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(String[] lines) {
				showCheck(lines);
			}
		});
	}

	/** Shows the Check's answer: the redirect address, then each problem, or that there are none */
	private void showCheck(String[] lines) {
		Window window = new Window();
		window.setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		window.setTitle("Check of " + SafeHtmlUtils.htmlEscape(provider.getLabel()));
		window.setIsModal(true);
		window.setShowModalMask(true);
		window.setWidth(600);
		window.setAutoSize(true);
		window.centerInPage();

		VLayout content = new VLayout();
		content.setMargin(10);
		content.setMembersMargin(8);
		content.setWidth100();
		content.setAutoHeight();

		content.addMember(copyable("Redirect Address", lines.length > 0 ? lines[0] : "", 1));

		StringBuilder html = new StringBuilder();
		if (lines.length <= 1) {
			html.append("<b>Everything checks out.</b> Register the redirect address above at the provider, if you "
					+ "haven't, then try a sign-in.");
		} else {
			html.append("<b>").append(lines.length - 1 == 1 ? "One problem" : (lines.length - 1) + " problems")
					.append(":</b><ul>");
			for (int i = 1; i < lines.length; i++)
				html.append("<li>").append(SafeHtmlUtils.htmlEscape(lines[i])).append("</li>");
			html.append("</ul>");
		}
		HTMLFlow result = new HTMLFlow(html.toString());
		result.setWidth100();
		content.addMember(result);
		content.addMember(buttons(button("Close", click -> window.destroy())));

		window.addItem(content);
		window.show();
	}

	private ListGrid prepareLinked() {
		ListGridField username = new ListGridField(USERNAME_FIELD, "Username", 130);
		username.setEscapeHTML(true);
		ListGridField name = new ListGridField(NAME, "Name");
		name.setWidth("*");
		name.setEscapeHTML(true);
		ListGridField how = new ListGridField(HOW, "Linked By", 100);
		ListGridField linked = new DateListGridField(LINKED, "Linked");
		linked.setWidth(130);
		ListGridField last = new DateListGridField(LAST_SIGN_IN, "Last Sign-In");
		last.setWidth(130);
		ListGridField unlink = new ListGridField(UNLINK, " ", 80);
		unlink.setCanSort(false);

		linkedGrid = new ListGrid() {
			@Override
			protected Canvas createRecordComponent(ListGridRecord rec, Integer colNum) {
				if (!UNLINK.equals(getFieldName(colNum)))
					return super.createRecordComponent(rec, colNum);
				IButton button = new IButton("Unlink");
				button.setHeight(18);
				button.setWidth(70);
				button.addClickHandler(click -> unlink(rec));
				return button;
			}
		};
		linkedGrid.setWidth(660);
		linkedGrid.setHeight(180);
		linkedGrid.setSelectionType(SelectionStyle.SINGLE);
		linkedGrid.setShowRecordComponents(true);
		linkedGrid.setShowRecordComponentsByCell(true);
		linkedGrid.setEmptyMessage("No account is linked to this provider");
		linkedGrid.setFields(username, name, how, linked, last, unlink);
		return linkedGrid;
	}

	private void refreshLinked() {
		HealdanSettingsService.Instance.get().linkedAccounts(provider.getId(), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUILinkedAccount[] accounts) {
				ListGridRecord[] records = new ListGridRecord[accounts.length];
				for (int i = 0; i < accounts.length; i++) {
					GUILinkedAccount account = accounts[i];
					records[i] = new ListGridRecord();
					records[i].setAttribute(USER_ID, Long.toString(account.getUserId()));
					records[i].setAttribute(USERNAME_FIELD, account.getUsername());
					records[i].setAttribute(NAME, account.getName());
					records[i].setAttribute(HOW, "email".equals(account.getHow()) ? "Email" : "New Account");
					records[i].setAttribute(LINKED, account.getLinked());
					records[i].setAttribute(LAST_SIGN_IN, account.getLastSignIn());
				}
				linkedGrid.setData(records);
			}
		});
	}

	private void unlink(ListGridRecord rec) {
		long userId = Long.parseLong(rec.getAttributeAsString(USER_ID));
		String username = SafeHtmlUtils.htmlEscape(rec.getAttributeAsString(USERNAME_FIELD));
		String label = SafeHtmlUtils.htmlEscape(provider.getLabel());
		LD.ask("Unlink " + username, "Unlink " + username + " from " + label
				+ "? The account goes back to a password account, with a temporary password, emailed if outgoing "
				+ "email is set up. Its devices are signed out. It keeps its documents and groups.", 440, yes -> {
					if (!Boolean.TRUE.equals(yes))
						return;
					LD.contactingServer();
					HealdanSettingsService.Instance.get().unlink(userId, new DefaultAsyncCallback<>() {
						@Override
						public void handleSuccess(String password) {
							changed();
							if (password == null)
								SC.say("Unlinked " + username + ", and emailed them a temporary password.");
							else
								showOnce("Unlinked " + username, "Unlinked " + username + ". Their temporary "
										+ "password isn't shown again, so pass it on to them now. They change it "
										+ "when they next sign in.", "Temporary Password", password, 1);
						}
					});
				});
	}

	private void unlinkEveryone() {
		String label = SafeHtmlUtils.htmlEscape(provider.getLabel());
		HealdanSettingsService.Instance.get().countLinked(provider.getId(), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(Integer linked) {
				if (linked == 0) {
					SC.say("No account is linked to " + label + ".");
					return;
				}
				LD.ask("Unlink Everyone", "Unlink " + accounts(linked) + " from " + label
						+ "? Each goes back to a password account, with a temporary password, emailed if outgoing "
						+ "email is set up. Their devices are signed out. They keep their documents and groups.", 440,
						yes -> {
							if (Boolean.TRUE.equals(yes))
								sendUnlinkEveryone();
						});
			}
		});
	}

	private void sendUnlinkEveryone() {
		LD.contactingServer();
		HealdanSettingsService.Instance.get().unlinkEveryone(provider.getId(), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(String[] passwords) {
				changed();
				if (passwords.length == 0)
					SC.say("Unlinked everyone, and emailed each a temporary password.");
				else
					showOnce("Unlinked Everyone", "Unlinked everyone. These temporary passwords weren't emailed, "
							+ "and aren't shown again, so pass each on now. Each person changes theirs when they next "
							+ "sign in.", "Temporary Passwords", String.join("\n", passwords),
							Math.min(passwords.length, 12));
			}
		});
	}

	/** After an unlink: the list and the tab's count change */
	private void changed() {
		refreshLinked();
		onChange.run();
	}

	/**
	 * Shows text the person can copy that won't be shown again, such as a
	 * temporary password
	 */
	private static void showOnce(String title, String message, String fieldTitle, String text, int rows) {
		Window window = new Window();
		window.setHeaderControls(HeaderControls.HEADER_LABEL);
		window.setTitle(title);
		window.setIsModal(true);
		window.setShowModalMask(true);
		window.setWidth(460);
		window.setAutoSize(true);
		window.centerInPage();

		VLayout content = new VLayout();
		content.setMargin(10);
		content.setMembersMargin(8);
		content.setWidth100();
		content.setAutoHeight();

		HTMLFlow about = new HTMLFlow(message);
		about.setWidth100();
		content.addMember(about);
		content.addMember(copyable(fieldTitle, text, rows));
		content.addMember(buttons(button("Close", click -> window.destroy())));

		window.addItem(content);
		window.show();
	}

	/** A read-only field whose text can be selected, with an icon that copies it */
	private static DynamicForm copyable(String title, String text, int rows) {
		DynamicForm form = new DynamicForm();
		form.setWidth100();
		form.setTitleOrientation(TitleOrientation.TOP);
		form.setNumCols(1);
		TextAreaItem item = ItemFactory.newTextAreaItem("copyable", title, text);
		item.setCanEdit(false);
		item.setWidth("100%");
		item.setHeight(Math.max(1, rows) * 18 + 10);
		item.setShouldSaveValue(false);
		item.setIcons(new CopyTextFormItemIcon(text, "Copy"));
		form.setItems(item);
		return form;
	}

	/** "1 person" or "3 people" */
	static String people(int count) {
		return count == 1 ? "1 person" : count + " people";
	}

	/** "1 account" or "3 accounts" */
	static String accounts(int count) {
		return count == 1 ? "1 account" : count + " accounts";
	}

	private static Label title(String text) {
		Label label = new Label("<b>" + text + "</b>");
		label.setHeight(20);
		return label;
	}

	private static Label note(String text) {
		Label label = new Label(text);
		label.setWidth(660);
		label.setHeight(1);
		label.setAutoHeight();
		return label;
	}

	private static IButton button(String title, ClickHandler handler) {
		IButton button = new IButton(title);
		button.setAutoFit(true);
		button.addClickHandler(handler);
		return button;
	}

	private static HLayout buttons(IButton... members) {
		HLayout buttons = new HLayout();
		buttons.setMembersMargin(5);
		buttons.setHeight(1);
		buttons.setMembers(members);
		return buttons;
	}
}
