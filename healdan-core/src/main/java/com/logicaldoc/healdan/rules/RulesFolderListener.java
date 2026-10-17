package com.logicaldoc.healdan.rules;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.folder.FolderListener;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;

/**
 * Applies the structure rules to every folder store: it compares the folder
 * with its row as stored before (see {@link StoredRows}) to tell a create, a
 * move, a rename, a type change, an alias, a delete or a restore from an
 * ordinary save (an ACL, attributes...), which it leaves alone. Folder deletes
 * and restores through {@code deleteTree} and {@code restore} are JDBC and come
 * through {@link HealdanHooksImpl} instead. CE creates it by reflection, hence
 * the public no-arg constructor.
 */
public class RulesFolderListener implements FolderListener {

	public RulesFolderListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		FolderRow stored = folder.getId() != 0L ? StoredRows.get().folder(folder.getId()) : null;
		Vetoes.check(Vetoes.ids(folder.getId(), folder.getParentId(), stored != null ? stored.parentId() : null),
				List.of(), () -> check(folder, stored, FilingRules.actor(transaction)));
	}

	private static void check(Folder folder, FolderRow stored, User actor) throws PersistenceException {
		FilingRules rules = FilingRules.get();
		if (stored == null) {
			rules.checkFolderCreate(parentOf(folder), folder, actor);
			return;
		}

		if (stored.deleted() != 0) {
			if (folder.getDeleted() == 0)
				rules.checkFolderRestore(folder.getId(), folder.getParentId(), actor);
			return;
		}
		if (folder.getDeleted() != 0) {
			rules.checkFolderDelete(folder, actor);
			return;
		}

		boolean moved = stored.parentId() != folder.getParentId();
		if (moved)
			rules.checkFolderMove(folder, parentOf(folder), actor);
		boolean renamed = !Objects.equals(stored.name(), folder.getName());
		if (renamed && !(moved && uniqueName(stored.name(), folder.getName())))
			rules.checkFolderRename(folder, actor);
		if (stored.type() != folder.getType())
			rules.checkTypeChange(stored.type(), folder, actor);
		else if (!stored.alias() && FilingRules.isAlias(folder))
			rules.checkFolderAlias(parentOf(folder));
	}

	/**
	 * @return true if the new name is the one CE's move gives the folder when
	 *         its name is taken in the new parent, e.g. "Letters(1)"
	 */
	private static boolean uniqueName(String storedName, String name) {
		return storedName != null && name != null
				&& name.matches(Pattern.quote(storedName) + "\\(\\d+\\)");
	}

	private static Folder parentOf(Folder folder) throws PersistenceException {
		return FolderDAO.get().findById(folder.getParentId());
	}

	@Override
	public void afterStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary) {
		StoredRows.get().stored(folder);
	}
}
