package com.logicaldoc.healdan.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.util.spring.Context;

/**
 * Favorites (spec 9.3): the user's CE bookmarks, so the same ones CE's
 * Bookmarks section shows, apart from those on matter workspaces, which are
 * My Matters ({@link MyMatters}). No storage of its own. Whether the user may
 * read a target is the caller's to ask; the list can't tell.
 */
@Component
public class Favorites {

	/** Folders first, then by title without regard to case */
	private static final Comparator<Bookmark> ORDER = Comparator
			.comparing((Bookmark b) -> b.getType() == Bookmark.Type.FOLDER ? 0 : 1)
			.thenComparing(b -> b.getTitle() == null ? "" : b.getTitle(), String.CASE_INSENSITIVE_ORDER)
			.thenComparingLong(Bookmark::getId);

	private final MatterLocator locator;

	@Autowired
	public Favorites(MatterLocator locator) {
		this.locator = locator;
	}

	/**
	 * @return the bean
	 */
	public static Favorites get() {
		return Context.get(Favorites.class);
	}

	/**
	 * @param userId the user
	 * @return the user's bookmarks, except those on the workspace of a live
	 *         matter, folders first and then by title
	 * @throws PersistenceException error at data layer
	 */
	public List<Bookmark> list(long userId) throws PersistenceException {
		List<Bookmark> favorites = new ArrayList<>();
		for (Bookmark bookmark : BookmarkDAO.get().findByUserId(userId))
			if (!isMyMatter(bookmark))
				favorites.add(bookmark);
		favorites.sort(ORDER);
		return favorites;
	}

	/**
	 * @param userId the user
	 * @param folderId the folder
	 * @return the user's bookmark on the folder, made now unless there is one
	 * @throws PersistenceException the folder doesn't exist, or an error at
	 *         data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public Bookmark addFolder(long userId, long folderId) throws PersistenceException {
		BookmarkDAO bookmarks = BookmarkDAO.get();
		Bookmark existing = bookmarks.findByUserIdAndFolderId(userId, folderId);
		if (existing != null)
			return existing;
		Folder folder = FolderDAO.get().findById(folderId);
		if (folder == null)
			throw new PersistenceException("Unexisting folder " + folderId);
		// As CE's addBookmarks makes a folder bookmark
		Bookmark bookmark = new Bookmark();
		bookmark.setTenantId(folder.getTenantId());
		bookmark.setType(Bookmark.Type.FOLDER);
		bookmark.setTargetId(folderId);
		bookmark.setUserId(userId);
		bookmark.setTitle(folder.getName());
		bookmarks.store(bookmark);
		return bookmark;
	}

	/**
	 * @param userId the user
	 * @param documentId the document's id (not its doc #)
	 * @return the user's bookmark on the document, made now unless there is one
	 * @throws PersistenceException the document doesn't exist, or an error at
	 *         data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public Bookmark addDocument(long userId, long documentId) throws PersistenceException {
		BookmarkDAO bookmarks = BookmarkDAO.get();
		Bookmark existing = bookmarks.findByUserIdAndDocId(userId, documentId);
		if (existing != null)
			return existing;
		Document document = DocumentDAO.get().findById(documentId);
		if (document == null)
			throw new PersistenceException("Unexisting document " + documentId);
		// As CE's addBookmarks makes a document bookmark
		Bookmark bookmark = new Bookmark();
		bookmark.setTenantId(document.getTenantId());
		bookmark.setType(Bookmark.Type.DOCUMENT);
		bookmark.setTargetId(documentId);
		bookmark.setUserId(userId);
		bookmark.setTitle(document.getFileName());
		bookmark.setFileType(document.getType());
		bookmarks.store(bookmark);
		return bookmark;
	}

	/**
	 * Removes a bookmark of the user's; one that is already gone is no error
	 *
	 * @param userId the user
	 * @param bookmarkId the bookmark
	 * @throws PermissionException the bookmark is another user's
	 * @throws PersistenceException error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public void remove(long userId, long bookmarkId) throws PersistenceException, PermissionException {
		BookmarkDAO bookmarks = BookmarkDAO.get();
		Bookmark bookmark = bookmarks.findById(bookmarkId);
		if (bookmark == null || bookmark.getDeleted() != 0)
			return;
		if (bookmark.getUserId() != userId)
			throw new PermissionException("User " + userId + " doesn't own bookmark " + bookmarkId);
		bookmarks.delete(bookmarkId);
	}

	private boolean isMyMatter(Bookmark bookmark) throws PersistenceException {
		if (bookmark.getType() != Bookmark.Type.FOLDER)
			return false;
		FolderPosition position = locator.locate(bookmark.getTargetId());
		return position.kind() == FolderPosition.Kind.MATTER_WORKSPACE && position.matter() != null;
	}
}
