package com.logicaldoc.healdan.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.folder.FolderListener;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.rules.Vetoes;

/**
 * Applies the permission rules (spec §7.3, {@link PermissionRules}) to every
 * folder store inside a matter, comparing the folder with its row as stored
 * before (see {@link StoredRows}).
 * <ul>
 * <li>A matter's workspace is checked against P2 when its ACL is saved
 * (event {@code PERMISSION}), its security reference changes, or it is created
 * or moved to another Store.</li>
 * <li>A folder below the workspace is checked against P1 when its ACL is
 * saved, its security reference changes, or it is created with an ACL of its
 * own. Any other store (a move, the stores of a folder copy, a rename...)
 * narrows the folder to its new parent instead of refusing it.</li>
 * <li>After a store that changed a folder's permissions, or moved it, the
 * folders below it are narrowed; after a Store's permissions change, the
 * folders inside its matters are (the workspaces are left to P2).</li>
 * </ul>
 * Folder stores are top-level, so a refusal here is safe (plan, D3); every
 * refusal goes through {@link Vetoes}. CE creates it by reflection, hence the
 * public no-arg constructor.
 */
public class PermissionsFolderListener implements FolderListener {

	/** Key in the dictionary: narrow the folders below once the folder is stored */
	static final String NARROW = "healdan.narrowDescendants";

	public PermissionsFolderListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (folder.getDeleted() != 0 || folder.getFoldRef() != null || folder.getType() == Folder.TYPE_ALIAS)
			return;
		MatterLocator locator = MatterLocator.get();
		FolderPosition parent = locator.locate(folder.getParentId());
		boolean workspace = parent.kind() == Kind.STORE;
		boolean underRoot = parent.kind() == Kind.ROOT && folder.getId() != 0L;
		if (!workspace && !underRoot && !parent.isInsideMatter())
			return;

		FolderRow stored = folder.getId() != 0L ? StoredRows.get().folder(folder.getId()) : null;
		boolean created = stored == null;
		boolean moved = !created && (stored.parentId() != folder.getParentId() || stored.deleted() != 0);
		boolean refChanged = !created && !Objects.equals(stored.securityRef(), folder.getSecurityRef());
		boolean permission = transaction != null && FolderEvent.PERMISSION.toString().equals(transaction.getEvent());
		List<Long> ids = Vetoes.ids(folder.getId(), folder.getParentId(), stored != null ? stored.parentId() : null);
		PermissionRules rules = PermissionRules.get();

		if (underRoot) {
			// A Store's permissions reach the matters that inherit them
			if (!created && (permission || refChanged) && locator.locate(folder.getId()).kind() == Kind.STORE)
				dictionary.put(NARROW, Boolean.TRUE);
			return;
		}

		if (workspace) {
			if (permission || refChanged || moved || created) {
				Matter matter = created ? null : MatterDAO.get().findByFolderId(folder.getId());
				Long owner = matter != null ? matter.getOwnerId() : null;
				Vetoes.check(ids, List.of(),
						() -> rules.checkP2(rules.prospective(folder), folder.getParentId(), owner, folder.getId()));
			}
			if (permission || refChanged)
				dictionary.put(NARROW, Boolean.TRUE);
			return;
		}

		boolean ownAcl = folder.getSecurityRef() == null;
		if (!PermissionRules.isCopying() && (permission || refChanged || (created && ownAcl))) {
			Vetoes.check(ids, List.of(), () -> rules.checkP1(folder));
			if (!created)
				dictionary.put(NARROW, Boolean.TRUE);
		} else if (rules.fit(folder) || moved) {
			if (!created)
				dictionary.put(NARROW, Boolean.TRUE);
		}
	}

	@Override
	public void afterStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (dictionary.remove(NARROW) != null && folder.getId() != 0L)
			PermissionRules.get().narrowDescendants(folder.getId());
	}
}
