package com.logicaldoc.healdan.api;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.FolderRef;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.api.model.MatterRef;
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
	 * @param d a document the caller may read
	 * @param caller the signed-in user, with its groups
	 * @return the document's view
	 * @throws PersistenceException error at data layer
	 */
	public static DocumentView document(Document d, User caller) throws PersistenceException {
		long folderId = d.getFolder().getId();
		FolderPosition position = MatterLocator.get().locate(folderId);
		HoldView hold = hold(d);
		boolean ownCheckout = hold != null && "checkout".equals(hold.kind()) && d.getLockUserId() != null
				&& d.getLockUserId() == caller.getId();
		boolean canEdit = AccessChecks.allowed(caller, Permission.WRITE, d) && !d.isImmutable()
				&& (hold == null || ownCheckout);
		long docNumber = Long.parseLong(d.getCustomId());
		return new DocumentView("document", docNumber, d.getFileName(), d.getFileSize(), d.getVersion(),
				d.getFileVersion(), docNumber + "." + d.getFileVersion(),
				new FolderRef(folderId, d.getFolder().getName(), folderKind(position)), matterOf(position),
				d.getCreation(), d.getLastModified(), hold, canEdit);
	}

	/**
	 * @param d a document
	 * @return who holds it and since when, or null if it's free
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

	private static MatterRef matterOf(FolderPosition position) throws PersistenceException {
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
}
