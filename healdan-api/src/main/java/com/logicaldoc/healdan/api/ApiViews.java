package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.ClientRef;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.FolderRef;
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.api.model.MatterRef;
import com.logicaldoc.healdan.api.model.MatterView;
import com.logicaldoc.healdan.api.model.StoreRef;
import com.logicaldoc.healdan.api.model.UserRef;
import com.logicaldoc.healdan.api.model.UserView;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.security.AccessChecks;

/**
 * Turns CE's and Healdan's objects into the API's views.
 */
public final class ApiViews {

	private static final Logger log = LoggerFactory.getLogger(ApiViews.class);

	private ApiViews() {
	}

	/**
	 * @param user a user, with its groups
	 * @return the user's view; a blank email or language is null
	 */
	public static UserView user(User user) {
		return new UserView(user.getId(), user.getUsername(), user.getFullName(),
				StringUtils.defaultIfBlank(user.getEmail(), null), StringUtils.defaultIfBlank(user.getLanguage(), null),
				user.isAdmin());
	}

	/**
	 * @param id a user's id, or null
	 * @param name the name recorded with it
	 * @return a reference to the user, or null when there's no id
	 */
	public static UserRef userRef(Long id, String name) {
		return id == null ? null : new UserRef(id, name);
	}

	/**
	 * @param d a document
	 * @return whether it has a numeric doc #. Lists leave out one that
	 *         hasn't, rather than fail the page.
	 */
	public static boolean hasDocNumber(Document d) {
		String customId = d.getCustomId();
		boolean numbered = customId != null && !customId.isEmpty() && customId.length() <= 18
				&& customId.chars().allMatch(c -> c >= '0' && c <= '9');
		if (!numbered)
			log.debug("Leaving document {} out of a list: its doc # '{}' isn't a number", d.getId(), customId);
		return numbered;
	}

	/**
	 * @param d a document the caller may read
	 * @param caller the signed-in user, with its groups
	 * @return the document's view; {@code canEdit} also asks that the current
	 *         request's session may open the document ({@link #opens})
	 * @throws PersistenceException error at data layer
	 */
	public static DocumentView document(Document d, User caller) throws PersistenceException {
		long folderId = d.getFolder().getId();
		FolderPosition position = MatterLocator.get().locate(folderId);
		HoldView hold = hold(d);
		// As the check-out and the save decide: no one else's hold, and no lock at all (spec correction 10)
		boolean canEdit = AccessChecks.allowed(caller, Permission.WRITE, d) && !d.isImmutable()
				&& (hold == null || checkedOutBy(d, caller)) && opens(d, Caller.current().session());
		long docNumber = Long.parseLong(d.getCustomId());
		return new DocumentView("document", docNumber, d.getFileName(), d.getFileSize(), d.getVersion(),
				d.getFileVersion(), docNumber + "." + d.getFileVersion(),
				new FolderRef(folderId, d.getFolder().getName(), folderKind(position)), matterOf(position),
				d.getCreation(), d.getLastModified(), hold, canEdit);
	}

	/**
	 * @param d a document
	 * @param user a user
	 * @return true if the document is checked out to the user; a lock isn't a
	 *         check-out
	 */
	public static boolean checkedOutBy(Document d, User user) {
		return d.getStatus() == DocumentStatus.CHECKEDOUT && d.getLockUserId() != null
				&& d.getLockUserId() == user.getId();
	}

	/**
	 * As every CE download path asks: a document protected by a password opens
	 * only for a session that gave the password in the web page.
	 *
	 * @param d a document
	 * @param session the caller's CE session
	 * @return true if the session may open the document
	 */
	public static boolean opens(Document d, Session session) {
		return !d.isPasswordProtected() || session.getUnprotectedDocs().containsKey(d.getId());
	}

	/**
	 * @param d a document
	 * @return who holds it and since when, or null if it's free; the holder is
	 *         null when CE's data doesn't say who it is
	 * @throws PersistenceException error at data layer
	 */
	public static HoldView hold(Document d) throws PersistenceException {
		String kind;
		if (d.getStatus() == DocumentStatus.CHECKEDOUT)
			kind = "checkout";
		else if (d.getStatus() == DocumentStatus.LOCKED)
			kind = "lock";
		else
			return null;

		List<DocumentHistory> latest = DocumentHistoryDAO.get().findByWhere(
				"_entity.docId = :docId and _entity.event in (:events)",
				Map.of("docId", d.getId(), "events",
						List.of(DocumentEvent.CHECKEDOUT.toKey(), DocumentEvent.LOCKED.toKey())),
				"_entity.date desc, _entity.id desc", 1);
		return new HoldView(kind, userRef(d.getLockUserId(), d.getLockUser()),
				latest.isEmpty() ? null : latest.get(0).getDate());
	}

	/**
	 * @param folderId a folder's id
	 * @return the matter the folder is, or is inside; null if there is none
	 * @throws PersistenceException error at data layer
	 */
	public static MatterRef matterOf(long folderId) throws PersistenceException {
		return matterOf(MatterLocator.get().locate(folderId));
	}

	static MatterRef matterOf(FolderPosition position) throws PersistenceException {
		Matter matter = position.matter();
		if (matter == null)
			return null;
		Client client = ClientDAO.get().findById(matter.getClientId());
		return new MatterRef(matter.getId(), MatterDisplay.format(client.getNumber(), matter.getNumber(), matter.getName()));
	}

	/**
	 * @param position where a folder sits
	 * @return {@code store}, {@code matter} for a matter's workspace,
	 *         {@code searchFolder} or {@code folder}
	 */
	public static String folderKind(FolderPosition position) {
		if (position.searchFolder())
			return "searchFolder";
		return switch (position.kind()) {
		case STORE -> "store";
		case MATTER_WORKSPACE -> "matter";
		default -> "folder";
		};
	}

	/**
	 * @param f a folder the caller may read
	 * @param caller the signed-in user, with its groups
	 * @return the folder's view; empty for a folder outside the Stores (the
	 *         root, the Default workspace, the Templates Store and what is in
	 *         them), which the API doesn't show
	 * @throws PersistenceException error at data layer
	 */
	public static Optional<FolderView> folder(Folder f, User caller) throws PersistenceException {
		FolderPosition position = MatterLocator.get().locate(f);
		if (!position.isUnderAStore())
			return Optional.empty();

		FolderDAO folders = FolderDAO.get();
		List<FolderRef> path = new ArrayList<>();
		for (Folder parent : folders.findParents(f.getId())) {
			// CE's parents run from the root, which isn't part of a path
			FolderPosition above = MatterLocator.get().locate(parent);
			if (above.isUnderAStore())
				path.add(new FolderRef(parent.getId(), parent.getName(), folderKind(above)));
		}
		String kind = folderKind(position);
		path.add(new FolderRef(f.getId(), f.getName(), kind));

		boolean canFile = "folder".equals(kind) && position.depth() >= 1
				&& folders.isWriteAllowed(f.getId(), caller.getId());
		return Optional.of(new FolderView("folder", f.getId(), f.getName(), kind, matterOf(position),
				storeRef(position.storeId()), List.copyOf(path), canFile));
	}

	private static StoreRef storeRef(Long storeId) throws PersistenceException {
		if (storeId == null)
			return null;
		Folder store = FolderDAO.get().findById(storeId);
		return store == null ? null : new StoreRef(store.getId(), store.getName());
	}

	/**
	 * @param m a matter whose workspace the caller may read
	 * @param lastChange the caller's latest change in it, or null
	 * @return the matter's view
	 * @throws PersistenceException error at data layer
	 */
	public static MatterView matter(Matter m, Date lastChange) throws PersistenceException {
		Client client = ClientDAO.get().findById(m.getClientId());
		Folder workspace = FolderDAO.get().findById(m.getFolderId());
		return new MatterView("matter", m.getId(), m.getNumber(),
				MatterDisplay.format(client.getNumber(), m.getNumber(), m.getName()), m.getName(),
				StringUtils.defaultIfBlank(m.getDescription(), null),
				new ClientRef(client.getId(), client.getNumber(), client.getName()),
				storeRef(workspace.getParentId()), m.getFolderId(), lastChange);
	}
}
