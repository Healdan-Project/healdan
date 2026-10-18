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
 * <li>Inside a matter a folder inherits only from a folder above it (plan,
 * R25): an ACL save or a security reference change that points elsewhere is
 * refused; any other store that leaves such a reference (a move, a copy...)
 * replaces it with an ACL of the folder's own.</li>
 * <li>A matter's workspace is checked against P2 when its ACL is saved
 * (event {@code PERMISSION}), its security reference changes, or it is created
 * or moved to another Store.</li>
 * <li>A folder below the workspace is checked against P1 when its ACL is
 * saved, its security reference changes, or it is created with an ACL of its
 * own. Any other store (a move, the stores of a folder copy, a rename...)
 * narrows the folder to its new parent instead of refusing it.</li>
 * <li>After a store that changed a folder's permissions, or moved it, the
 * folders below it are narrowed; so are those of a workspace that moved.
 * After a Store's permissions change, the folders inside its matters are,
 * once the transaction is about to commit (the workspaces are left to P2,
 * except that one inheriting from anything but its Store gets its own
 * ACL).</li>
 * </ul>
 * Folder stores are top-level, so a refusal here is safe (plan, D3); every
 * refusal goes through {@link Vetoes}. CE creates it by reflection, hence the
 * public no-arg constructor.
 */
public class PermissionsFolderListener implements FolderListener {

	/** Key in the dictionary: narrow the folders below once the folder is stored */
	static final String NARROW = "healdan.narrowDescendants";

	/** Key in the dictionary: narrow the Store's matters before the transaction commits */
	static final String NARROW_STORE = "healdan.narrowStore";

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
				dictionary.put(NARROW_STORE, Boolean.TRUE);
			return;
		}

		boolean explicit = !PermissionRules.isCopying() && (permission || refChanged);
		if (workspace) {
			boolean fitted = false;
			if (explicit)
				Vetoes.check(ids, List.of(), () -> rules.checkInheritsFromAbove(folder));
			else
				fitted = rules.fitWorkspace(folder);
			if (permission || refChanged || moved || created) {
				Matter matter = created ? null : MatterDAO.get().findByFolderId(folder.getId());
				Long owner = matter != null ? matter.getOwnerId() : null;
				Vetoes.check(ids, List.of(),
						() -> rules.checkP2(rules.prospective(folder), folder.getParentId(), owner, folder.getId()));
			}
			// After a move to another Store its folders may still inherit from the old one
			if (!created && (permission || refChanged || moved || fitted))
				dictionary.put(NARROW, Boolean.TRUE);
			return;
		}

		boolean ownAcl = folder.getSecurityRef() == null;
		if (explicit || (!PermissionRules.isCopying() && created && ownAcl)) {
			Vetoes.check(ids, List.of(), () -> {
				rules.checkInheritsFromAbove(folder);
				rules.checkP1(folder);
			});
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
		// After the store: CE's updateSecurityRef re-points the Store's heirs only then
		if (dictionary.remove(NARROW_STORE) != null)
			PermissionRules.get().narrowBeforeCommit(folder.getId());
	}
}
