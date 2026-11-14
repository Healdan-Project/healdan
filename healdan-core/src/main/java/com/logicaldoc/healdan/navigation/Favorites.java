// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
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
 * <p>
 * A favorite's name is the bookmark's title, which CE's Bookmarks section
 * shows and edits too. It starts as its folder's or document's name, follows
 * that name while the two are the same ({@link FavoriteNamesDocumentListener},
 * {@link FavoriteNamesFolderListener}), and keeps a name of its own once the
 * user gives it one (M6 spec 6.1, changed after M6: the owner's dogfooding
 * punch list, 2026-11-14).
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

	/**
	 * Gives a bookmark of the user's a name of its own, or puts back the name
	 * of its folder or document as it is now.
	 *
	 * @param userId the user
	 * @param bookmarkId the bookmark
	 * @param name the new name, which the caller has checked; null or blank
	 *        for the folder's or document's own
	 * @return the bookmark, renamed
	 * @throws PermissionException the bookmark is another user's
	 * @throws PersistenceException the bookmark, or its folder or document,
	 *         doesn't exist, or an error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public Bookmark rename(long userId, long bookmarkId, String name) throws PersistenceException, PermissionException {
		BookmarkDAO bookmarks = BookmarkDAO.get();
		Bookmark bookmark = bookmarks.findById(bookmarkId);
		if (bookmark == null)
			throw new PersistenceException("Unexisting bookmark " + bookmarkId);
		if (bookmark.getUserId() != userId)
			throw new PermissionException("User " + userId + " doesn't own bookmark " + bookmarkId);
		String title = StringUtils.isBlank(name) ? ownName(bookmark) : name.strip();
		if (title == null)
			throw new PersistenceException("Unexisting target of bookmark " + bookmarkId);
		bookmark.setTitle(title);
		bookmarks.store(bookmark);
		return bookmark;
	}

	/**
	 * @return the name of the bookmark's folder, or its document's file name,
	 *         as it is now; null if it's gone
	 */
	private static String ownName(Bookmark bookmark) throws PersistenceException {
		if (bookmark.getType() == Bookmark.Type.FOLDER) {
			Folder folder = FolderDAO.get().findById(bookmark.getTargetId());
			return folder != null ? folder.getName() : null;
		}
		Document document = DocumentDAO.get().findById(bookmark.getTargetId());
		return document != null ? document.getFileName() : null;
	}

	/**
	 * A folder or document was renamed: each bookmark on it that still has
	 * its old name, whoever's it is, takes the new one, and one given a name
	 * of its own keeps it. A document's bookmarks also take its file type,
	 * which CE's Bookmarks section shows as their icon. Runs in the caller's
	 * transaction, the store's that renamed it.
	 *
	 * @param type what was renamed
	 * @param targetId the folder's or document's id
	 * @param oldName its name before
	 * @param newName its name now
	 * @param fileType a document's file type now; null for a folder
	 * @throws PersistenceException error at data layer
	 */
	public void followRename(Bookmark.Type type, long targetId, String oldName, String newName, String fileType)
			throws PersistenceException {
		BookmarkDAO bookmarks = BookmarkDAO.get();
		for (Bookmark bookmark : bookmarks.findByWhere(
				"_entity.targetId = %d and _entity.type = %d".formatted(targetId, type.ordinal()), null, null)) {
			// Compared here, not in the query: MariaDB's = ignores case and trailing spaces, and a name of the
			// person's own that differs from the old one only so must stay
			boolean unrenamed = oldName.equals(bookmark.getTitle());
			boolean retyped = type == Bookmark.Type.DOCUMENT && !Objects.equals(fileType, bookmark.getFileType());
			if (!unrenamed && !retyped)
				continue;
			if (unrenamed)
				bookmark.setTitle(newName);
			if (retyped)
				bookmark.setFileType(fileType);
			bookmarks.store(bookmark);
		}
	}

	private boolean isMyMatter(Bookmark bookmark) throws PersistenceException {
		if (bookmark.getType() != Bookmark.Type.FOLDER)
			return false;
		FolderPosition position = locator.locate(bookmark.getTargetId());
		return position.kind() == FolderPosition.Kind.MATTER_WORKSPACE && position.matter() != null;
	}
}
