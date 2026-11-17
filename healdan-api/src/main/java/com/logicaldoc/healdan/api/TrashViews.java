// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.sql.Timestamp;
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
import com.logicaldoc.healdan.api.model.TrashContents;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.api.model.UserRef;
import com.logicaldoc.healdan.listing.FileKinds;
import com.logicaldoc.healdan.matter.Matter;
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
import com.logicaldoc.util.spring.Context;

/**
 * Turns the Trash's record into the API's {@link TrashItem}s (folders and
 * Trash spec 6.5), and counts what a folder holds as the caller may see it
 * (spec 4.2): a count never takes in what the caller can't read, so it tells
 * nothing of a hidden folder or document. An administrator's counts take
 * everything.
 * <p>
 * The caller reads a folder, deleted or not, when one of its groups has Read
 * on it, or on the folder it takes its security from; and a document as CE's
 * {@code DocumentDAO.getAllowedPermissions} reads a live one: by its own
 * entries for the caller's groups when they grant anything, else by its
 * folder's, and published unless the caller sees what isn't, as
 * {@link AccessChecks#readableDocument} decides. CE's own checks see nothing
 * on a deleted folder or document, so the rule is SQL here.
 */
final class TrashViews {

	private static final String FOLDER = "folder";

	/** Ids per query, to stay under the databases' limits on a list */
	private static final int CHUNK = 500;

	private static final String USER_GROUPS = "select g.ld_groupid from ld_usergroup g where g.ld_userid = :user";

	/** The folder {@code f} is readable */
	private static final String FOLDER_READ = """
			exists (select 1 from ld_folder_acl b where b.ld_folderid = coalesce(f.ld_securityref, f.ld_id)
			   and b.ld_read = 1 and b.ld_groupid in (%s))""".formatted(USER_GROUPS);

	/** The document {@code d}, in the folder {@code f}, is readable */
	private static final String DOCUMENT_READ = """
			(exists (select 1 from ld_document_acl a where a.ld_docid = d.ld_id and a.ld_read = 1
			   and a.ld_groupid in (%1$s))
			 or (not exists (select 1 from ld_document_acl a where a.ld_docid = d.ld_id and a.ld_groupid in (%1$s)
			   and (%2$s) > 0) and %3$s))""".formatted(USER_GROUPS, AccessChecks.anyPermission("a."), FOLDER_READ);

	/** The document {@code d} is published now, as {@code Document.isPublishing} decides */
	private static final String PUBLISHED = """
			d.ld_published = 1 and (d.ld_startpublishing is null or d.ld_startpublishing <= :now)
			   and (d.ld_stoppublishing is null or d.ld_stoppublishing > :now)""";

	private TrashViews() {
	}

	/**
	 * @param row an item of the Trash's record
	 * @param caller the signed-in user, with its groups
	 * @return the item as the API shows it: {@code contents} counts the
	 *         members in the item's state that the caller can read, an
	 *         admin's every one; and {@code removedBy}, {@code removedAt} and
	 *         {@code destroyAfter} only for an item in Removed
	 * @throws PersistenceException error at data layer
	 */
	static TrashItem item(TrashItemRow row, User caller) throws PersistenceException {
		boolean folder = isFolder(row);
		TrashStore store = TrashStore.get();
		TrashState state = store.state(row.id());

		UserRef removedBy = null;
		Date removedAt = null;
		Date destroyAfter = null;
		Optional<TrashMemberRow> top = store.member(row.id(), new TrashMember(folder ? 'F' : 'D', row.objectId()));
		if (state == TrashState.REMOVED && top.isPresent()) {
			removedBy = ApiViews.userRef(top.get().removedById(), top.get().removedByName());
			Instant removed = top.get().removedAt();
			if (removed != null) {
				removedAt = Date.from(removed);
				destroyAfter = Date.from(TrashStartup
						.destroyAfter(removed, HealdanSettings.get().trashRemovedDays(), ZoneId.systemDefault())
						.toInstant());
			}
		}

		Matter matter = row.matterId() != null ? MatterDAO.get().findById(row.matterId()) : null;
		return new TrashItem(row.id(), row.kind(), row.name(),
				row.docNumber() != null ? Long.valueOf(row.docNumber()) : null,
				folder ? FOLDER : FileKinds.of(FilenameUtils.getExtension(row.name())),
				folder ? null : size(row.objectId()), folder ? contents(row, state, caller) : null,
				deletedFrom(row, caller), ApiViews.matterRef(matter),
				ApiViews.userRef(row.deletedById(), row.deletedByName()), Date.from(row.deletedAt()), removedBy,
				removedAt, destroyAfter);
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
		List<Long> tree = StoredRows.get().tree(folderId);
		boolean admin = AccessChecks.isAdmin(caller);
		return new TrashContents(folders(tree.subList(1, tree.size()), admin, caller),
				documents("d.ld_deleted = 0 and d.ld_folderid in (:ids)", tree, admin, caller));
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

		boolean admin = AccessChecks.isAdmin(caller);
		return new TrashContents(admin ? inside.size() : folders(inside, false, caller),
				admin ? documentIds.size() : documents("d.ld_id in (:ids)", documentIds, false, caller));
	}

	/**
	 * @return where the item was deleted from: the folder with its present
	 *         path while it is live, in a Store and readable by the caller,
	 *         else the path recorded at the delete
	 */
	private static DeletedFrom deletedFrom(TrashItemRow row, User caller) throws PersistenceException {
		if (row.fromFolderId() != null) {
			Optional<Folder> from = AccessChecks.readableFolder(row.fromFolderId(), caller);
			Optional<FolderView> view = from.isPresent() && from.get().getType() != Folder.TYPE_ALIAS
					? ApiViews.folder(from.get(), caller)
					: Optional.empty();
			if (view.isPresent())
				return new DeletedFrom(new FolderRef(view.get().id(), view.get().name(), view.get().kind()),
						view.get().path().stream().map(FolderRef::name).toList());
		}
		return new DeletedFrom(null, row.fromPath());
	}

	/** @return a document's file size, deleted or not; null when its row is gone */
	private static Long size(long docId) {
		return jdbc().queryForList("select ld_filesize from ld_document where ld_id = :id",
				new MapSqlParameterSource("id", docId), Long.class).stream().findFirst().orElse(null);
	}

	/** @return how many of the folders the caller can read */
	private static long folders(Collection<Long> ids, boolean admin, User caller) {
		if (admin)
			return ids.size();
		long count = 0;
		for (List<Long> chunk : chunks(ids))
			count += jdbc().queryForObject(
					"select count(*) from ld_folder f where f.ld_id in (:ids) and " + FOLDER_READ,
					new MapSqlParameterSource("ids", chunk).addValue("user", caller.getId()), Long.class);
		return count;
	}

	/**
	 * @param scope which documents, a condition on {@code d} with the list
	 *        {@code :ids}
	 * @return how many of the documents the caller can read
	 */
	private static long documents(String scope, Collection<Long> ids, boolean admin, User caller) {
		StringBuilder where = new StringBuilder(scope);
		if (!admin)
			where.append(" and ").append(DOCUMENT_READ);
		if (!AccessChecks.seesUnpublished(caller))
			where.append(" and ").append(PUBLISHED);
		long count = 0;
		for (List<Long> chunk : chunks(ids))
			count += jdbc().queryForObject(
					"select count(*) from ld_document d join ld_folder f on f.ld_id = d.ld_folderid where " + where,
					new MapSqlParameterSource("ids", chunk).addValue("user", caller.getId()).addValue("now",
							new Timestamp(System.currentTimeMillis())),
					Long.class);
		return count;
	}

	private static List<List<Long>> chunks(Collection<Long> ids) {
		List<Long> all = new ArrayList<>(ids);
		List<List<Long>> chunks = new ArrayList<>();
		for (int i = 0; i < all.size(); i += CHUNK)
			chunks.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
		return chunks;
	}

	private static NamedParameterJdbcTemplate jdbc() {
		return new NamedParameterJdbcTemplate(Context.get(DataSource.class));
	}
}
