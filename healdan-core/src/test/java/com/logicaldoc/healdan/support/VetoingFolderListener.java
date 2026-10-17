package com.logicaldoc.healdan.support;

import java.util.List;
import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.folder.FolderListener;

/**
 * A folder listener that refuses every save while
 * {@link VetoSwitch#FOLDER_BEFORE_STORE} is on, as a Healdan rule would. CE
 * creates it by reflection, from the test plugin's extension.
 */
public class VetoingFolderListener implements FolderListener {

	@Override
	public void beforeStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.FOLDER_BEFORE_STORE, VetoSwitch.ids(folder.getId()), List.of());
	}

	@Override
	public void afterStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary) {
		// Nothing to check
	}
}
