package com.logicaldoc.gui.frontend.client.healdan.searchfolder;

import java.util.LinkedHashMap;
import java.util.List;

import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.i18n.I18N;
import com.logicaldoc.gui.common.client.util.ItemFactory;
import com.logicaldoc.gui.common.client.util.LD;
import com.logicaldoc.gui.frontend.client.document.DocumentsPanel;
import com.logicaldoc.gui.frontend.client.folder.FolderNavigator;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISearchFolderRule;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSearchFolderService;
import com.smartgwt.client.types.HeaderControls;
import com.smartgwt.client.types.TitleOrientation;
import com.smartgwt.client.widgets.Window;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.ButtonItem;
import com.smartgwt.client.widgets.form.fields.CheckboxItem;
import com.smartgwt.client.widgets.form.fields.SelectItem;
import com.smartgwt.client.widgets.form.fields.SpinnerItem;
import com.smartgwt.client.widgets.form.fields.TextItem;
import com.smartgwt.client.widgets.tree.TreeNode;

/**
 * The New and Edit Search Folder dialogs (spec 5.4): a name and a rule, which
 * lists the documents of the folder's matter changed in the last so many days,
 * by anyone or only by the viewer, of a kind, and optionally of a Class and a
 * Subclass
 */
public class SearchFolderDialog extends Window {

	private static final String NAME = "name";

	private static final String DAYS = "days";

	private static final String ONLY_MINE = "onlymine";

	private static final String KIND = "kind";

	private static final String CLASS = "docclass";

	private static final String SUBCLASS = "docsubclass";

	/** The search folder being edited, null when creating one */
	private final GUISearchFolderRule editing;

	/** Where a new search folder goes */
	private final long parentId;

	private final DynamicForm form = new DynamicForm();

	/**
	 * Opens the New Search Folder dialog
	 *
	 * @param parentId where the search folder goes
	 */
	public static void create(long parentId) {
		HealdanSearchFolderService.Instance.get().classes(new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(List<String> classes) {
				new SearchFolderDialog(null, parentId, classes).show();
			}
		});
	}

	/**
	 * Opens the Edit Search Folder dialog
	 *
	 * @param folderId the search folder
	 */
	public static void edit(long folderId) {
		HealdanSearchFolderService.Instance.get().get(folderId, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(GUISearchFolderRule rule) {
				HealdanSearchFolderService.Instance.get().classes(new DefaultAsyncCallback<>() {
					@Override
					public void handleSuccess(List<String> classes) {
						new SearchFolderDialog(rule, 0L, classes).show();
					}
				});
			}
		});
	}

	private SearchFolderDialog(GUISearchFolderRule editing, long parentId, List<String> classes) {
		this.editing = editing;
		this.parentId = parentId;
		GUISearchFolderRule rule = editing != null ? editing : new GUISearchFolderRule();

		setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		setTitle(editing == null ? "New search folder" : "Edit search folder");
		setIsModal(true);
		setShowModalMask(true);
		centerInPage();
		setAutoSize(true);

		form.setWidth(400);
		form.setColWidths(110, "*");
		form.setTitleOrientation(TitleOrientation.LEFT);

		TextItem name = ItemFactory.newTextItem(NAME, "Name", rule.getName());
		name.setRequired(true);
		name.setLength(255);
		name.setWidth(240);

		SpinnerItem days = ItemFactory.newSpinnerItem(DAYS, "Changed within", rule.getDays(), 1, 3650);
		days.setHint("days");
		days.setRequired(true);

		CheckboxItem onlyMine = ItemFactory.newCheckbox(ONLY_MINE, "Only my changes");
		onlyMine.setValue(rule.isOnlyMine());

		SelectItem kind = ItemFactory.newSelectItem(KIND, "Show");
		LinkedHashMap<String, String> kinds = new LinkedHashMap<>();
		kinds.put("ALL", "Documents and emails");
		kinds.put("DOCUMENTS", "Documents only");
		kinds.put("EMAILS", "Emails only");
		kind.setValueMap(kinds);
		kind.setValue(rule.getKind());
		kind.setWidth(240);
		kind.setRequired(true);

		SelectItem docClass = ItemFactory.newSelectItem(CLASS, "Class");
		LinkedHashMap<String, String> classMap = new LinkedHashMap<>();
		for (String value : classes)
			classMap.put(value, value);
		if (rule.getDocClass() != null)
			classMap.put(rule.getDocClass(), rule.getDocClass());
		docClass.setValueMap(classMap);
		docClass.setAllowEmptyValue(true);
		docClass.setValue(rule.getDocClass());
		docClass.setWidth(240);
		docClass.addChangedHandler(changed -> loadSubclasses(null));

		SelectItem docSubclass = ItemFactory.newSelectItem(SUBCLASS, "Subclass");
		docSubclass.setAllowEmptyValue(true);
		docSubclass.setWidth(240);

		ButtonItem save = new ButtonItem("save", I18N.message(editing == null ? "create" : "save"));
		save.setAutoFit(true);
		save.addClickHandler(click -> onSave());

		form.setFields(name, days, onlyMine, kind, docClass, docSubclass, save);
		addItem(form);

		loadSubclasses(rule.getDocSubclass());
	}

	/** The Subclasses of the chosen Class, keeping the given one selected */
	private void loadSubclasses(String selected) {
		String docClass = form.getValueAsString(CLASS);
		SelectItem docSubclass = (SelectItem) form.getItem(SUBCLASS);
		docSubclass.clearValue();
		docSubclass.setValueMap(new LinkedHashMap<String, String>());
		if (docClass == null || docClass.isEmpty())
			return;

		HealdanSearchFolderService.Instance.get().subclasses(docClass, new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(List<String> subclasses) {
				LinkedHashMap<String, String> map = new LinkedHashMap<>();
				for (String value : subclasses)
					map.put(value, value);
				docSubclass.setValueMap(map);
				if (selected != null && map.containsKey(selected))
					docSubclass.setValue(selected);
			}
		});
	}

	private void onSave() {
		if (!Boolean.TRUE.equals(form.validate()))
			return;

		GUISearchFolderRule rule = new GUISearchFolderRule();
		rule.setFolderId(editing != null ? editing.getFolderId() : 0L);
		rule.setName(form.getValueAsString(NAME));
		rule.setDays(Integer.parseInt(form.getValueAsString(DAYS)));
		rule.setOnlyMine(Boolean.TRUE.equals(form.getValue(ONLY_MINE)));
		rule.setKind(form.getValueAsString(KIND));
		rule.setDocClass(form.getValueAsString(CLASS));
		rule.setDocSubclass(form.getValueAsString(SUBCLASS));

		LD.contactingServer();
		if (editing == null)
			HealdanSearchFolderService.Instance.get().create(parentId, rule, new DefaultAsyncCallback<>() {
				@Override
				public void handleSuccess(Long folderId) {
					destroy();
					reloadChildren(parentId);
				}
			});
		else
			HealdanSearchFolderService.Instance.get().update(rule, new DefaultAsyncCallback<>() {
				@Override
				public void handleSuccess(Void result) {
					destroy();
					// Its parent's children again, for a new name; and its listing, for a new rule
					TreeNode node = FolderNavigator.get().getNode(rule.getFolderId());
					if (node != null)
						FolderNavigator.get().getTree().reloadChildren(FolderNavigator.get().getTree().getParent(node));
					DocumentsPanel.get().refresh();
				}
			});
	}

	/** The parent's children again, so a new search folder shows */
	private static void reloadChildren(long folderId) {
		TreeNode parent = FolderNavigator.get().getNode(folderId);
		if (parent != null)
			FolderNavigator.get().getTree().reloadChildren(parent);
	}
}
