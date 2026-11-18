// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.commons.io.FilenameUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.DeletedFrom;
import com.logicaldoc.healdan.api.model.FolderRef;
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.api.model.MatterRef;
import com.logicaldoc.healdan.api.model.TrashContents;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.api.model.UserRef;
import com.logicaldoc.healdan.listing.FileKinds;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.trash.TrashItemRow;
import com.logicaldoc.healdan.trash.TrashMember;
import com.logicaldoc.healdan.trash.TrashMemberRow;
import com.logicaldoc.healdan.trash.TrashStartup;
import com.logicaldoc.healdan.trash.TrashState;
import com.logicaldoc.healdan.trash.TrashStore;
import com.logicaldoc.util.CollectionUtil;
import com.logicaldoc.util.spring.Context;

/**
 * Turns the Trash's record into the API's {@link TrashItem}s (folders and
 * Trash spec 6.5), and counts what a folder holds as the caller may see it
 * (spec 4.2): a count never takes in what the caller can't read, so it tells
 * nothing of a hidden folder or document. An administrator's counts take
 * everything. CE's own checks see nothing on a deleted folder or document, so
 * the counts are {@link StoredRows}', which states the read rule for rows that
 * may be deleted.
 */
final class TrashViews {

	private static final String FOLDER = "folder";

	/** Ids per query, to stay under the databases' limits on a list */
	private static final int CHUNK = 500;

	private TrashViews() {
	}

	/**
	 * @param row an item of the Trash's record
	 * @param caller the signed-in user, with its groups
	 * @return the item as the API shows it: {@code contents} counts the
	 *         members in the item's state that the caller can read, an
	 *         admin's every one; {@code deletedFrom} and {@code matter} are
	 *         the place as it is now while it is live, in a Store and
	 *         readable by the caller, else as they were recorded at the
	 *         delete; and {@code removedBy}, {@code removedAt} and
	 *         {@code destroyAfter} only for an item in Removed
	 * @throws PersistenceException error at data layer
	 */
	static TrashItem item(TrashItemRow row, User caller) throws PersistenceException {
		TrashMemberRow top = TrashStore.get().member(row.id(), new TrashMember(isFolder(row) ? 'F' : 'D', row.objectId()))
				.orElseThrow();
		return item(row, top, HealdanSettings.get().trashRemovedDays(), caller);
	}

	/**
	 * {@link #item(TrashItemRow, User)} with the item's top member and the
	 * days removed items are kept already read, as a page of the Trash reads
	 * them once for all its items
	 *
	 * @param row an item of the Trash's record
	 * @param top its top member's row, whose state is the item's
	 * @param removedDays the days removed items are kept
	 * @param caller the signed-in user, with its groups
	 * @return the item as the API shows it
	 * @throws PersistenceException error at data layer
	 */
	static TrashItem item(TrashItemRow row, TrashMemberRow top, int removedDays, User caller)
			throws PersistenceException {
		boolean folder = isFolder(row);
		TrashState state = top.state();

		UserRef removedBy = null;
		Date removedAt = null;
		Date destroyAfter = null;
		if (state == TrashState.REMOVED) {
			removedBy = ApiViews.userRef(top.removedById(), top.removedByName());
			Instant removed = top.removedAt();
			if (removed != null) {
				removedAt = Date.from(removed);
				destroyAfter = Date.from(
						TrashStartup.destroyAfter(removed, removedDays, ZoneId.systemDefault()).toInstant());
			}
		}

		// The place's present path goes with its present matter, so the two never mix (a place moved into another matter
		// since the delete); without the place, both are as recorded at the delete. A place in no matter is a Store, which
		// a matter's own folder was deleted from: its matter is the one recorded, the folder's own (TrashRecorder.matterOf)
		Optional<FolderView> place = livePlace(row, caller);
		DeletedFrom deletedFrom;
		MatterRef matter = null;
		if (place.isPresent()) {
			FolderView view = place.get();
			deletedFrom = new DeletedFrom(new FolderRef(view.id(), view.name(), view.kind()),
					view.path().stream().map(FolderRef::name).toList());
			matter = view.matter();
		} else {
			deletedFrom = new DeletedFrom(null, row.fromPath());
		}
		if (matter == null && row.matterId() != null)
			matter = ApiViews.matterRef(MatterDAO.get().findById(row.matterId()));
		return new TrashItem(row.id(), row.kind(), row.name(),
				row.docNumber() != null ? Long.valueOf(row.docNumber()) : null,
				folder ? FOLDER : FileKinds.of(FilenameUtils.getExtension(row.name())),
				folder ? null : size(row.objectId()), folder ? contents(row, state, caller) : null, deletedFrom,
				matter, ApiViews.userRef(row.deletedById(), row.deletedByName()), Date.from(row.deletedAt()),
				removedBy, removedAt, destroyAfter);
	}

	/**
	 * @param row an item of the Trash's record
	 * @return true for a folder's delete, false for a document's
	 */
	static boolean isFolder(TrashItemRow row) {
		return FOLDER.equals(row.kind());
	}

	/**
	 * @param folderId a live folder
	 * @param caller the signed-in user, with its groups
	 * @return the live folders below it and the live documents in its tree
	 *         that the caller can read
	 * @throws PersistenceException error at data layer
	 */
	static TrashContents liveContents(long folderId, User caller) throws PersistenceException {
		StoredRows rows = StoredRows.get();
		List<Long> tree = rows.tree(folderId);
		return new TrashContents(rows.readableFolders(tree.subList(1, tree.size()), caller),
				rows.readableLiveDocumentsIn(tree, caller));
	}

	/**
	 * The members of a folder's item in its state, the folder itself left out,
	 * and the aliases outside its tree, which went with it but weren't in it
	 * (plan correction 4)
	 */
	private static TrashContents contents(TrashItemRow row, TrashState state, User caller)
			throws PersistenceException {
		List<Long> folderIds = new ArrayList<>();
		List<Long> documentIds = new ArrayList<>();
		for (TrashMember member : TrashStore.get().members(row.id(), state))
			(member.type() == 'F' ? folderIds : documentIds).add(member.objectId());

		Set<Long> members = new HashSet<>(folderIds);
		members.add(row.objectId());
		List<Long> inside = new ArrayList<>();
		for (List<Long> chunk : chunks(folderIds))
			jdbc().query("select ld_id, ld_parentid, ld_foldref from ld_folder where ld_id in (:ids)",
					new MapSqlParameterSource("ids", chunk), rs -> {
						long id = rs.getLong(1);
						long parentId = rs.getLong(2);
						rs.getLong(3);
						boolean alias = !rs.wasNull();
						if (id != row.objectId() && (!alias || members.contains(parentId)))
							inside.add(id);
					});

		StoredRows rows = StoredRows.get();
		return new TrashContents(rows.readableFolders(inside, caller), rows.readableDocuments(documentIds, caller));
	}

	/**
	 * @return the folder the item was deleted from, as it is now, while it is
	 *         live, in a Store and readable by the caller; empty otherwise
	 */
	private static Optional<FolderView> livePlace(TrashItemRow row, User caller) throws PersistenceException {
		if (row.fromFolderId() == null)
			return Optional.empty();
		Optional<Folder> from = AccessChecks.readableFolder(row.fromFolderId(), caller);
		return from.isPresent() && from.get().getType() != Folder.TYPE_ALIAS ? ApiViews.folder(from.get(), caller)
				: Optional.empty();
	}

	/** @return a document's file size, deleted or not; null when its row is gone */
	private static Long size(long docId) {
		return jdbc().queryForList("select ld_filesize from ld_document where ld_id = :id",
				new MapSqlParameterSource("id", docId), Long.class).stream().findFirst().orElse(null);
	}

	private static Collection<List<Long>> chunks(Collection<Long> ids) {
		return CollectionUtil.partition(new ArrayList<>(ids), CHUNK);
	}

	private static NamedParameterJdbcTemplate jdbc() {
		return new NamedParameterJdbcTemplate(Context.get(DataSource.class));
	}
}
