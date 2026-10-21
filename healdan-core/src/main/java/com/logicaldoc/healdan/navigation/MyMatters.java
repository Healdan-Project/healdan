package com.logicaldoc.healdan.navigation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * My Matters (spec 8.2): the user's own list of matters, kept as CE folder
 * bookmarks on the matters' workspaces, so no storage of its own. A matter
 * added here is in CE's Bookmarks section too, and a matter's workspace
 * bookmarked there is here (plan, call 9).
 */
@Component
public class MyMatters {

	static final String NOT_A_MATTER = "Only a %s can be added to My %s.";

	static final String NO_READ = "You need the read permission on this %s to add it to My %s.";

	private final MatterLocator locator;

	private final HealdanSettings settings;

	@Autowired
	public MyMatters(MatterLocator locator, HealdanSettings settings) {
		this.locator = locator;
		this.settings = settings;
	}

	/**
	 * @return the bean
	 */
	public static MyMatters get() {
		return Context.get(MyMatters.class);
	}

	/**
	 * @param userId the user
	 * @return the user's folder bookmarks whose target is the workspace of a
	 *         live matter the user can read, each matter once, by display
	 *         (client number, then matter number); their last change is null
	 * @throws PersistenceException error at data layer
	 */
	public List<MatterEntry> list(long userId) throws PersistenceException {
		Set<Long> seen = new HashSet<>();
		List<ReadableMatter> matters = new ArrayList<>();
		for (Bookmark bookmark : BookmarkDAO.get().findByUserId(userId)) {
			// CE could bookmark a folder twice before its fix
			if (bookmark.getType() != Bookmark.Type.FOLDER || !seen.add(bookmark.getTargetId()))
				continue;
			FolderPosition position = locator.locate(bookmark.getTargetId());
			if (position.kind() == FolderPosition.Kind.MATTER_WORKSPACE)
				ReadableMatter.of(position, userId).ifPresent(matters::add);
		}
		matters.sort(ReadableMatter.DISPLAY_ORDER);
		return matters.stream().map(matter -> matter.entry(null)).toList();
	}

	/**
	 * Adds a matter, as a CE folder bookmark on its workspace, unless the user
	 * has one already
	 *
	 * @param userId the user
	 * @param folderId the matter's workspace
	 * @throws PersistenceException a refusal (a {@link HealdanRuleException}
	 *         with a message for the user) if the folder isn't the workspace of
	 *         a live matter or the user can't read it, or an error at data
	 *         layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public void add(long userId, long folderId) throws PersistenceException {
		FolderDAO folders = FolderDAO.get();
		Folder folder = folders.findById(folderId);
		if (folder == null)
			throw refusal(NOT_A_MATTER);
		// Before telling whether it's a matter, so an unreadable folder says nothing of that
		if (!folders.isReadAllowed(folderId, userId))
			throw refusal(NO_READ);
		if (!isMatter(locator.locate(folder)))
			throw refusal(NOT_A_MATTER);

		BookmarkDAO bookmarks = BookmarkDAO.get();
		if (bookmarks.findByUserIdAndFolderId(userId, folderId) != null)
			return;
		// As CE's addBookmarks makes a folder bookmark
		Bookmark bookmark = new Bookmark();
		bookmark.setTenantId(folder.getTenantId());
		bookmark.setType(Bookmark.Type.FOLDER);
		bookmark.setTargetId(folderId);
		bookmark.setUserId(userId);
		bookmark.setTitle(folder.getName());
		bookmarks.store(bookmark);
	}

	/**
	 * Removes the user's folder bookmarks on that folder, if there are any;
	 * other users' bookmarks stay. A folder that isn't the workspace of a live
	 * matter is never in My Matters, so its bookmarks stay too: they are only
	 * CE's.
	 *
	 * @param userId the user
	 * @param folderId the matter's workspace
	 * @throws PersistenceException error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public void remove(long userId, long folderId) throws PersistenceException {
		if (!isMatter(locator.locate(folderId)))
			return;
		BookmarkDAO bookmarks = BookmarkDAO.get();
		// All of them: CE could bookmark a folder twice before its fix
		for (Bookmark bookmark : bookmarks.findByUserId(userId))
			if (bookmark.getType() == Bookmark.Type.FOLDER && bookmark.getTargetId() == folderId)
				bookmarks.delete(bookmark.getId());
	}

	/** Whether the folder is the workspace of a live matter, which has its row */
	private static boolean isMatter(FolderPosition position) {
		return position.kind() == FolderPosition.Kind.MATTER_WORKSPACE && position.matter() != null;
	}

	private HealdanRuleException refusal(String message) {
		return new HealdanRuleException(
				message.formatted(settings.matterLabel().toLowerCase(Locale.ROOT), settings.matterLabelPlural()));
	}
}
