// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.settings;

import java.util.LinkedHashMap;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.log.GuiLog;
import com.logicaldoc.gui.common.client.util.ItemFactory;
import com.logicaldoc.gui.common.client.util.LD;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanStorageService;
import com.logicaldoc.gui.frontend.client.healdan.services.SecretKeyMissingException;
import com.smartgwt.client.types.HeaderControls;
import com.smartgwt.client.types.TitleOrientation;
import com.smartgwt.client.widgets.HTMLFlow;
import com.smartgwt.client.widgets.Window;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.CheckboxItem;
import com.smartgwt.client.widgets.form.fields.FormItem;
import com.smartgwt.client.widgets.form.fields.PasswordItem;
import com.smartgwt.client.widgets.form.fields.SelectItem;
import com.smartgwt.client.widgets.form.fields.StaticTextItem;
import com.smartgwt.client.widgets.form.fields.TextItem;
import com.smartgwt.client.widgets.layout.VLayout;

/**
 * Adds or edits one storage backend (spec section 11): a local folder, or a
 * bucket at an S3 service. Test tries the settings as typed, and Save tests
 * them first. The S3 secret is never shown: its field starts empty, and only
 * a value typed into it replaces the one stored. Encryption is chosen when the
 * backend is added, as its recovery key is shown then, and the bucket, prefix
 * and path can't change once files are there; Original can only be renamed.
 */
public class BackendDialog extends Window {

	private static final String KIND = "kind";

	private static final String NAME = "name";

	private static final String PATH = "path";

	private static final String ENDPOINT = "endpoint";

	private static final String REGION = "region";

	private static final String BUCKET = "bucket";

	private static final String PREFIX = "prefix";

	private static final String ACCESS_KEY = "accesskey";

	private static final String SECRET = "secret";

	private static final String PATH_STYLE = "pathstyle";

	private static final String ENCRYPT = "encrypt";

	/** The S3 settings' items, shown only for an S3 backend */
	private static final String[] S3_ITEMS = { ENDPOINT, REGION, BUCKET, PREFIX, ACCESS_KEY, SECRET, PATH_STYLE };

	private final GUIStorageBackend backend;

	/** Why the bucket, prefix and path can't change; null if they can */
	private final String placeFixed;

	private final Runnable onChange;

	private final DynamicForm form = new DynamicForm();

	/**
	 * Opens the dialog
	 *
	 * @param backend the backend to edit, or a new one, with id 0, to add
	 * @param placeFixed why its bucket, prefix and path can't change, such as
	 *        files being there; null if they can
	 * @param onChange what to do once a backend is added or saved
	 */
	public static void open(GUIStorageBackend backend, String placeFixed, Runnable onChange) {
		new BackendDialog(backend, placeFixed, onChange).show();
	}

	private BackendDialog(GUIStorageBackend backend, String placeFixed, Runnable onChange) {
		this.backend = backend;
		this.placeFixed = placeFixed;
		this.onChange = onChange;

		setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		setTitle(adding() ? "Add a Storage Backend" : SafeHtmlUtils.htmlEscape(backend.getName()));
		setIsModal(true);
		setShowModalMask(true);
		setWidth(680);
		setAutoSize(true);
		centerInPage();
		// Gone when closed, so a secret typed and not saved doesn't stay in a hidden field
		addCloseClickHandler(event -> destroy());

		VLayout content = new VLayout();
		content.setMargin(10);
		content.setMembersMargin(8);
		content.setWidth100();
		content.setAutoHeight();

		prepareForm();
		content.addMember(form);
		content.addMember(StorageTab.buttons(StorageTab.button("Test", click -> test()),
				StorageTab.button(adding() ? "Add" : "Save", click -> save()),
				StorageTab.button("Close", click -> destroy())));
		addItem(content);
	}

	private boolean adding() {
		return backend.getId() == 0;
	}

	private void prepareForm() {
		form.setWidth(640);
		form.setColWidths(170, "*");
		form.setTitleOrientation(TitleOrientation.LEFT);
		form.setWrapItemTitles(false);

		FormItem kind;
		if (adding()) {
			SelectItem select = ItemFactory.newSelectItem(KIND, "Kind");
			LinkedHashMap<String, String> kinds = new LinkedHashMap<>();
			kinds.put(GUIStorageBackend.S3, "S3");
			kinds.put(GUIStorageBackend.LOCAL, "Local Folder");
			select.setValueMap(kinds);
			select.setValue(backend.getKind());
			select.setRequired(true);
			select.setWidth(200);
			select.addChangedHandler(event -> kindChanged());
			kind = select;
		} else {
			kind = fixed(KIND, "Kind", backend.isS3() ? "S3" : "Local Folder", null);
		}

		TextItem name = text(NAME, "Name", backend.getName(), 100, 300);
		name.setRequired(true);
		if (backend.isOriginal())
			name.setHint("Original can be renamed, but nothing else about it changes");

		FormItem path;
		if (backend.isOriginal())
			path = fixed(PATH, "Path", backend.getPath(), "from the configuration");
		else if (placeFixed != null)
			path = fixed(PATH, "Path", backend.getPath(), placeFixed);
		else {
			TextItem typed = text(PATH, "Path", backend.getPath(), 255, 420);
			typed.setHint("the folder's whole path in the container, such as /mnt/archive");
			typed.setShowHintInField(true);
			path = typed;
		}

		TextItem endpoint = text(ENDPOINT, "Endpoint", backend.getEndpoint(), 1000, 420);
		endpoint.setHint("such as https://s3.eu-central-1.wasabisys.com");
		endpoint.setShowHintInField(true);
		TextItem region = text(REGION, "Region", backend.getRegion(), 100, 200);

		FormItem bucket = placeFixed != null ? fixed(BUCKET, "Bucket", backend.getBucket(), placeFixed)
				: text(BUCKET, "Bucket", backend.getBucket(), 255, 300);
		FormItem prefix;
		if (placeFixed != null) {
			prefix = fixed(PREFIX, "Prefix", backend.getPrefix(), placeFixed);
		} else {
			TextItem typed = text(PREFIX, "Prefix", backend.getPrefix(), 255, 300);
			typed.setHint("optional: a folder in the bucket, so one bucket can serve several backends");
			prefix = typed;
		}

		TextItem accessKey = text(ACCESS_KEY, "Access Key", backend.getAccessKey(), 255, 300);

		// Never the stored secret: the field starts empty, and only what's typed in it replaces the secret
		PasswordItem secret = ItemFactory.newPasswordItemPreventAutocomplete(SECRET, "Secret", null);
		secret.setWidth(300);
		if (!adding())
			secret.setHint(backend.isHasSecret() ? "Set: leave empty to keep it" : "Not Set: enter it again");

		CheckboxItem pathStyle = ItemFactory.newCheckbox(PATH_STYLE, "Path-Style Addressing");
		pathStyle.setValue(backend.isPathStyle());
		pathStyle.setHint("the bucket in the address's path, not its host name, as some services need");

		CheckboxItem encrypt = ItemFactory.newCheckbox(ENCRYPT, "Encrypt");
		if (adding()) {
			encrypt.setValue(backend.isS3());
			encrypt.setHint("on for S3 unless you turn it off; it can't change once the backend is added");
		} else {
			encrypt.setValue(StorageTab.encrypted(backend));
			encrypt.setDisabled(true);
			encrypt.setHint("chosen when the backend was added");
		}

		if (backend.isOriginal()) {
			form.setItems(kind, name, path, encrypt);
		} else {
			form.setItems(kind, name, path, endpoint, region, bucket, prefix, accessKey, secret, pathStyle, encrypt);
		}
		showKindItems(backend.isS3());
		form.rememberValues();
	}

	private static TextItem text(String name, String title, String value, int length, int width) {
		TextItem item = ItemFactory.newTextItemPreventAutocomplete(name, title, value);
		item.setLength(length);
		item.setWidth(width);
		return item;
	}

	/** A setting shown as text, which can't change here, and why */
	private static StaticTextItem fixed(String name, String title, String value, String why) {
		StaticTextItem item = ItemFactory.newStaticTextItem(name, title, value);
		item.setEscapeHTML(true);
		item.setShouldSaveValue(false);
		if (why != null)
			item.setHint(why);
		return item;
	}

	private boolean s3() {
		return adding() ? GUIStorageBackend.S3.equals(form.getValueAsString(KIND)) : backend.isS3();
	}

	private void kindChanged() {
		boolean s3 = s3();
		showKindItems(s3);
		form.setValue(ENCRYPT, s3);
	}

	/** Shows the kind's own settings, which are the only ones required */
	private void showKindItems(boolean s3) {
		if (backend.isOriginal())
			return;
		for (String name : S3_ITEMS) {
			FormItem item = form.getItem(name);
			item.setVisible(s3);
			if (item instanceof TextItem && !PREFIX.equals(name))
				item.setRequired(s3 && (!SECRET.equals(name) || adding()));
		}
		FormItem path = form.getItem(PATH);
		path.setVisible(!s3);
		if (path instanceof TextItem)
			path.setRequired(!s3);
		form.markForRedraw();
	}

	/** The settings as they now are; what can't change here, as it was */
	private GUIStorageBackend collect() {
		GUIStorageBackend saving = new GUIStorageBackend();
		saving.setId(backend.getId());
		boolean s3 = s3();
		saving.setKind(s3 ? GUIStorageBackend.S3 : GUIStorageBackend.LOCAL);
		saving.setName(form.getValueAsString(NAME));
		boolean placeTyped = !backend.isOriginal() && placeFixed == null;
		if (s3) {
			saving.setEndpoint(form.getValueAsString(ENDPOINT));
			saving.setRegion(form.getValueAsString(REGION));
			saving.setBucket(placeTyped ? form.getValueAsString(BUCKET) : backend.getBucket());
			saving.setPrefix(placeTyped ? form.getValueAsString(PREFIX) : backend.getPrefix());
			saving.setAccessKey(form.getValueAsString(ACCESS_KEY));
			saving.setPathStyle(Boolean.TRUE.equals(form.getValue(PATH_STYLE)));
		} else {
			saving.setPath(placeTyped ? form.getValueAsString(PATH) : backend.getPath());
		}
		saving.setEncrypted(adding() ? Boolean.TRUE.equals(form.getValue(ENCRYPT)) : backend.getEncrypted());
		return saving;
	}

	/** The secret as typed; null if none was */
	private String typedSecret() {
		String secret = s3() ? form.getValueAsString(SECRET) : null;
		return secret != null && !secret.trim().isEmpty() ? secret : null;
	}

	private String displayName() {
		String name = form.getValueAsString(NAME);
		return name != null && !name.trim().isEmpty() ? name.trim() : "the backend";
	}

	private void test() {
		if (!Boolean.TRUE.equals(form.validate()))
			return;
		LD.contactingServer();
		HealdanStorageService.Instance.get().testNew(collect(), typedSecret(), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(String[] problems) {
				showTest(displayName(), problems);
			}
		});
	}

	/** An add is tested by the server before it saves anything; a save is tested here first */
	private void save() {
		if (!Boolean.TRUE.equals(form.validate()))
			return;
		GUIStorageBackend saving = collect();
		String secret = typedSecret();
		if (adding()) {
			send(saving, secret);
			return;
		}

		LD.contactingServer();
		HealdanStorageService.Instance.get().testNew(saving, secret, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(String[] problems) {
				if (problems.length == 0) {
					send(saving, secret);
					return;
				}
				LD.ask("Test Failed", "Test found " + problemsHtml(problems)
						+ "Save these settings anyway? The backend fails until they work.", 480, yes -> {
							if (Boolean.TRUE.equals(yes))
								send(saving, secret);
						});
			}
		});
	}

	private void send(GUIStorageBackend saving, String secret) {
		LD.contactingServer();
		DefaultAsyncCallback<GUIStorageBackend> callback = new DefaultAsyncCallback<>() {
			@Override
			public void onFailure(Throwable caught) {
				if (caught instanceof SecretKeyMissingException && secret != null && !saving.isNewKey()) {
					LD.clearPrompt();
					NewKeyQuestion.ask("S3 secret", ((SecretKeyMissingException) caught).getSealed(), () -> {
						saving.setNewKey(true);
						send(saving, secret);
					});
				} else {
					super.onFailure(caught);
				}
			}

			@Override
			public void handleSuccess(GUIStorageBackend saved) {
				destroy();
				onChange.run();
				if (saved.getRecoveryKey() != null) {
					RecoveryKeyDialog.open(saved, saved.getRecoveryKey(), true, onChange);
				} else {
					GuiLog.info((adding() ? "Added " : "Saved ") + SafeHtmlUtils.htmlEscape(saved.getName()), null);
				}
			}
		};
		if (adding())
			HealdanStorageService.Instance.get().addBackend(saving, secret, callback);
		else
			HealdanStorageService.Instance.get().saveBackend(saving, secret, callback);
	}

	/**
	 * Shows a Test's answer: that it passed, or each problem, as text
	 *
	 * @param name the backend's name
	 * @param problems what Test found; none if it passed
	 */
	static void showTest(String name, String[] problems) {
		Window window = new Window();
		window.setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		window.setTitle("Test of " + SafeHtmlUtils.htmlEscape(name));
		window.addCloseClickHandler(event -> window.destroy());
		window.setIsModal(true);
		window.setShowModalMask(true);
		window.setWidth(560);
		window.setAutoSize(true);
		window.centerInPage();

		VLayout content = new VLayout();
		content.setMargin(10);
		content.setMembersMargin(8);
		content.setWidth100();
		content.setAutoHeight();

		HTMLFlow result = new HTMLFlow(
				problems.length == 0 ? "<b>Test passed.</b>" : "Test found " + problemsHtml(problems));
		result.setWidth100();
		content.addMember(result);
		content.addMember(StorageTab.buttons(StorageTab.button("Close", click -> window.destroy())));

		window.addItem(content);
		window.show();
	}

	/** "one problem:" or "3 problems:", then each, escaped, in a list */
	private static String problemsHtml(String[] problems) {
		StringBuilder html = new StringBuilder();
		html.append(problems.length == 1 ? "one problem:" : problems.length + " problems:").append("<ul>");
		for (String problem : problems)
			html.append("<li>").append(SafeHtmlUtils.htmlEscape(problem)).append("</li>");
		return html.append("</ul>").toString();
	}
}
