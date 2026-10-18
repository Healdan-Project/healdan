package com.logicaldoc.healdan.matter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * Tells where a folder sits: in a Store, in a matter, in the templates, or
 * elsewhere.
 * <p>
 * It looks at the structure alone. A live folder directly under a Store is a
 * matter's workspace whether or not its {@code hd_matter} row exists, because
 * a matter's workspace and its folders are created before the row is inserted
 * and listeners must still classify them. The parents are walked by id, never
 * through {@code ld_path}.
 * <p>
 * The listeners of one store each ask where the same folders sit. Inside
 * {@link #remember(Map)} the answers are kept in the store's dictionary and
 * reused: nothing moves a folder while the listeners check a store.
 */
@Component
public class MatterLocator {

	/** Key in a listener dictionary of the positions found for that store */
	private static final String POSITIONS = MatterLocator.class.getName() + ".positions";

	/** The positions kept for the store whose listener is running, null outside one */
	private static final ThreadLocal<Map<String, FolderPosition>> REMEMBERED = new ThreadLocal<>();

	/**
	 * Ends what {@link MatterLocator#remember(Map)} started
	 */
	public interface Remembering extends AutoCloseable {
		@Override
		void close();
	}

	/**
	 * @return the locator bean
	 */
	public static MatterLocator get() {
		return Context.get(MatterLocator.class);
	}

	/**
	 * Until the result is closed, keeps the positions found in the dictionary of
	 * the store being checked, and reuses those that the store's earlier
	 * listeners found. For a listener's {@code beforeStore} only: once the
	 * store is saved, folders may sit elsewhere.
	 *
	 * @param dictionary the dictionary CE passes the listeners of one store
	 * @return closes it, restoring what an enclosing listener call kept
	 */
	public static Remembering remember(Map<String, Object> dictionary) {
		@SuppressWarnings("unchecked")
		Map<String, FolderPosition> positions = (Map<String, FolderPosition>) dictionary.computeIfAbsent(POSITIONS,
				key -> new HashMap<String, FolderPosition>());
		Map<String, FolderPosition> enclosing = REMEMBERED.get();
		REMEMBERED.set(positions);
		return () -> {
			if (enclosing != null)
				REMEMBERED.set(enclosing);
			else
				REMEMBERED.remove();
		};
	}

	/**
	 * @param folderId the id of the folder
	 * @return where the folder sits; OUTSIDE if it doesn't exist or is deleted
	 * @throws PersistenceException error at data layer
	 */
	public FolderPosition locate(long folderId) throws PersistenceException {
		return locate(FolderDAO.get().findById(folderId));
	}

	/**
	 * @param folder the folder
	 * @return where the folder sits; OUTSIDE if it is null
	 * @throws PersistenceException error at data layer
	 */
	public FolderPosition locate(Folder folder) throws PersistenceException {
		if (folder == null)
			return position(Kind.OUTSIDE, null, null, null, -1, false);

		boolean search = folder.getType() == HealdanConstants.FOLDER_TYPE_SEARCH;
		if (folder.getId() == Folder.ROOTID || folder.getId() == folder.getParentId())
			return position(Kind.ROOT, null, null, null, -1, search);

		// A top-level folder's own type tells a Store, so it is part of the key
		Map<String, FolderPosition> remembered = folder.getId() != 0L ? REMEMBERED.get() : null;
		String key = folder.getId() + ":" + folder.getType();
		FolderPosition known = remembered != null ? remembered.get(key) : null;
		if (known != null)
			return known;

		FolderPosition position = walk(folder, search);
		if (remembered != null)
			remembered.put(key, position);
		return position;
	}

	private FolderPosition walk(Folder folder, boolean search) throws PersistenceException {
		// Root first; the folder itself last
		List<Folder> chain = new ArrayList<>(FolderDAO.get().findParents(folder.getId()));
		chain.add(folder);
		if (chain.size() < 2 || chain.get(0).getId() != Folder.ROOTID)
			return position(Kind.OUTSIDE, null, null, null, -1, search);

		Folder top = chain.get(1);
		if (top.getId() == Folder.DEFAULTWORKSPACEID)
			return position(chain.size() == 2 ? Kind.DEFAULT_WORKSPACE : Kind.OUTSIDE, null, null, null, -1, search);

		if (Long.valueOf(top.getId()).equals(HealdanSettings.get().templatesStoreId()))
			return position(chain.size() == 2 ? Kind.TEMPLATES_STORE : Kind.IN_TEMPLATES, null, null, null, -1,
					search);

		// Only a workspace directly under the root is a Store
		if (top.getType() != Folder.TYPE_WORKSPACE)
			return position(Kind.OUTSIDE, null, null, null, -1, search);

		if (chain.size() == 2)
			return position(Kind.STORE, top.getId(), null, null, -1, search);

		Folder workspace = chain.get(2);
		Matter matter = MatterDAO.get().findByFolderId(workspace.getId());
		return position(chain.size() == 3 ? Kind.MATTER_WORKSPACE : Kind.IN_MATTER, top.getId(), matter,
				workspace.getId(), chain.size() - 3, search);
	}

	private static FolderPosition position(Kind kind, Long storeId, Matter matter, Long workspaceId, int depth,
			boolean search) {
		return new FolderPosition(kind, storeId, matter, workspaceId, depth, search);
	}
}
