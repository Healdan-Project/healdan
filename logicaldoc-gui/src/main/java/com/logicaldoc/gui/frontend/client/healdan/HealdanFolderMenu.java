package com.logicaldoc.gui.frontend.client.healdan;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.common.client.Session;
import com.logicaldoc.gui.common.client.beans.GUIAccessControlEntry;
import com.logicaldoc.gui.common.client.beans.GUIFolder;
import com.logicaldoc.gui.frontend.client.folder.FolderNavigator;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIFolderRole;
import com.logicaldoc.gui.frontend.client.healdan.matter.MatterDialog;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanMatterService;
import com.smartgwt.client.widgets.menu.Menu;
import com.smartgwt.client.widgets.menu.MenuItem;
import com.smartgwt.client.widgets.menu.MenuItemSeparator;

/**
 * Healdan's items in the folder tree's context menu. What a folder is comes
 * from the server, so the items arrive a moment after the menu opens.
 */
public final class HealdanFolderMenu {

	private HealdanFolderMenu() {
	}

	/**
	 * Adds Healdan's items for the selected folder, if exactly one is
	 * selected, where the menu's items end now
	 * 
	 * @param menu the folder context menu
	 * @param tree the folder tree
	 * @param acl the user's permissions on the selected folder
	 */
	public static void contribute(Menu menu, FolderNavigator tree, GUIAccessControlEntry acl) {
		List<GUIFolder> selected = tree.getSelectedFolders();
		if (selected.size() != 1)
			return;

		GUIFolder folder = selected.get(0);
		int position = menu.getItems().length;
		HealdanMatterService.Instance.get().describeFolder(folder.getId(), new AsyncCallback<GUIFolderRole>() {
			@Override
			public void onFailure(Throwable caught) {
				// The menu works without Healdan's items
			}

			@Override
			public void onSuccess(GUIFolderRole role) {
				List<MenuItem> items = items(folder, role);
				if (items.isEmpty())
					return;
				int index = position;
				menu.addItem(new MenuItemSeparator(), index++);
				for (MenuItem item : items)
					menu.addItem(item, index++);
			}
		});
	}

	private static List<MenuItem> items(GUIFolder folder, GUIFolderRole role) {
		List<MenuItem> items = new ArrayList<>();
		boolean admin = Session.get().isAdmin();
		String label = HealdanLabels.singular();

		if (admin && "STORE".equals(role.getKind()))
			items.add(item("New " + label + "\u2026", () -> MatterDialog.create(folder.getId())));

		if (admin && "MATTER_WORKSPACE".equals(role.getKind()))
			items.add(item("Edit " + label + "\u2026", () -> MatterDialog.edit(folder.getId())));

		return items;
	}

	private static MenuItem item(String title, Runnable action) {
		MenuItem item = new MenuItem(title);
		item.addClickHandler(click -> action.run());
		return item;
	}
}
