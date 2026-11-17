// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicaldoc.util.spring.Context;

/**
 * The Trash's record, in <code>hd_trash</code> and <code>hd_trash_member</code>: each delete, and where each
 * folder or document it took stands.
 */
@Component
public class TrashStore {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** What the page's sort names stand for; the text sent is never put in the SQL */
	private static final Map<String, String> SORTS = Map.of("name", "lower(t.hd_name)", "deleted", "t.hd_deletedat",
			"removed", "m.hd_removedat");

	/** An item's top member is the folder or document the person deleted */
	private static final String TOP = " from hd_trash t join hd_trash_member m on m.hd_trashid = t.hd_id"
			+ " and m.hd_objectid = t.hd_objectid and m.hd_objecttype = (case when t.hd_kind = 'folder' then 'F' else 'D' end)";

	private static final String ITEM_COLUMNS = "t.hd_id, t.hd_tenantid, t.hd_kind, t.hd_objectid, t.hd_name,"
			+ " t.hd_docnumber, t.hd_fromfolderid, t.hd_frompath, t.hd_matterid, t.hd_folders, t.hd_documents,"
			+ " t.hd_deletedby, t.hd_deletedbyname, t.hd_deletedat";

	private static final String INSERT_MEMBER = "insert into hd_trash_member (hd_trashid, hd_objecttype, hd_objectid,"
			+ " hd_state) values (?, ?, ?, ?)";

	/** The most characters {@code hd_frompath} holds */
	static final int PATH_LIMIT = 4000;

	private final JdbcTemplate jdbc;

	@Autowired
	public TrashStore(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the Trash's store bean
	 */
	public static TrashStore get() {
		return Context.get(TrashStore.class);
	}

	/**
	 * Inserts the item and its members, all deleted. The caller supplies the
	 * transaction: a delete records its item in its own, so a failure, the
	 * delete's or the record's, leaves neither once it rolls back. A path too
	 * long for the column loses its topmost names, keeping the folder's
	 * nearest ancestors, so that no delete fails on a deep folder.
	 *
	 * @param item the item; its id is ignored
	 * @param members what the delete took, the item's own folder or document
	 *        included, each once
	 * @return the item's id
	 */
	public long insert(TrashItemRow item, Collection<TrashMember> members) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"insert into hd_trash (hd_tenantid, hd_kind, hd_objectid, hd_name, hd_docnumber, hd_fromfolderid,"
							+ " hd_frompath, hd_matterid, hd_folders, hd_documents, hd_deletedby, hd_deletedbyname,"
							+ " hd_deletedat) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			int i = 1;
			ps.setLong(i++, item.tenantId());
			ps.setString(i++, item.kind());
			ps.setLong(i++, item.objectId());
			ps.setString(i++, item.name());
			ps.setObject(i++, item.docNumber());
			ps.setObject(i++, item.fromFolderId());
			ps.setString(i++, path(item.fromPath()));
			ps.setObject(i++, item.matterId());
			ps.setInt(i++, item.folders());
			ps.setInt(i++, item.documents());
			ps.setObject(i++, item.deletedById());
			ps.setString(i++, item.deletedByName());
			ps.setTimestamp(i, Timestamp.from(item.deletedAt()));
			return ps;
		}, keys);
		long id = ((Number) keys.getKeyList().get(0).values().iterator().next()).longValue();
		jdbc.batchUpdate(INSERT_MEMBER, members.stream().map(member -> new Object[] { id,
				String.valueOf(member.type()), member.objectId(), TrashState.DELETED.code() }).toList());
		return id;
	}

	/**
	 * @param id the item
	 * @return the item, if there is one
	 */
	public Optional<TrashItemRow> item(long id) {
		return jdbc.query("select " + ITEM_COLUMNS + " from hd_trash t where t.hd_id = ?", this::itemRow, id).stream()
				.findFirst();
	}

	/**
	 * @param id the item
	 * @return the item's state, which is its top member's
	 */
	public TrashState state(long id) {
		return TrashState.of(jdbc.queryForObject("select m.hd_state" + TOP + " where t.hd_id = ?", String.class, id));
	}

	/**
	 * @param member a folder or document
	 * @param state a state
	 * @return the newest item holding the object in that state
	 */
	public Optional<Long> itemHolding(TrashMember member, TrashState state) {
		return jdbc.queryForList("select max(hd_trashid) from hd_trash_member where hd_objecttype = ?"
				+ " and hd_objectid = ? and hd_state = ?", Long.class, String.valueOf(member.type()),
				member.objectId(), state.code()).stream().filter(Objects::nonNull).findFirst();
	}

	/**
	 * @param userId a person
	 * @param state a state
	 * @return the items the person deleted whose top member is in that state, oldest first
	 */
	public List<Long> itemsDeletedBy(long userId, TrashState state) {
		return jdbc.queryForList("select t.hd_id" + TOP + " where t.hd_deletedby = ? and m.hd_state = ?"
				+ " order by t.hd_id", Long.class, userId, state.code());
	}

	/**
	 * @param id the item
	 * @param state a state
	 * @return the item's members in that state
	 */
	public List<TrashMember> members(long id, TrashState state) {
		return jdbc.query("select hd_objecttype, hd_objectid from hd_trash_member where hd_trashid = ?"
				+ " and hd_state = ? order by hd_objecttype, hd_objectid",
				(rs, n) -> new TrashMember(rs.getString(1).charAt(0), rs.getLong(2)), id, state.code());
	}

	/**
	 * Moves the listed members from one state to another, stamping who and when.
	 * A member in another state is left as it is.
	 *
	 * @param id the item
	 * @param members the members to move
	 * @param from the state they must be in
	 * @param to the state they move to
	 * @param byId who moved them
	 * @param byName that person's name
	 * @param at when
	 * @return how many changed
	 */
	public int change(long id, Collection<TrashMember> members, TrashState from, TrashState to, Long byId,
			String byName, Instant at) {
		String set = switch (to) {
		case REMOVED -> ", hd_removedby = ?, hd_removedbyname = ?, hd_removedat = ?";
		case RESTORED -> ", hd_restoredby = ?, hd_restoredbyname = ?, hd_restoredat = ?";
		case DESTROYED -> ", hd_destroyedat = ?";
		case DELETED -> "";
		};
		Timestamp time = Timestamp.from(at);
		int changed = 0;
		for (TrashMember member : members) {
			List<Object> args = new ArrayList<>();
			args.add(to.code());
			if (to == TrashState.REMOVED || to == TrashState.RESTORED) {
				args.add(byId);
				args.add(byName);
			}
			if (to != TrashState.DELETED)
				args.add(time);
			args.add(id);
			args.add(String.valueOf(member.type()));
			args.add(member.objectId());
			args.add(from.code());
			changed += jdbc.update("update hd_trash_member set hd_state = ?" + set + " where hd_trashid = ?"
					+ " and hd_objecttype = ? and hd_objectid = ? and hd_state = ?", args.toArray());
		}
		return changed;
	}

	/**
	 * Marks destroyed the object's members in every item that holds it in the
	 * Trash or in Removed: a shortcut or an alias that went with what it
	 * pointed to
	 *
	 * @param member the shortcut or alias
	 * @param at when it was destroyed
	 * @return how many members changed
	 */
	public int destroyedAlong(TrashMember member, Instant at) {
		return jdbc.update("update hd_trash_member set hd_state = ?, hd_destroyedat = ? where hd_objecttype = ?"
				+ " and hd_objectid = ? and hd_state in (?, ?)", TrashState.DESTROYED.code(), Timestamp.from(at),
				String.valueOf(member.type()), member.objectId(), TrashState.DELETED.code(),
				TrashState.REMOVED.code());
	}

	/**
	 * Adds a member, deleted, to an item.
	 *
	 * @param id the item
	 * @param member what it also took
	 */
	public void addMember(long id, TrashMember member) {
		jdbc.update(INSERT_MEMBER, id, String.valueOf(member.type()), member.objectId(), TrashState.DELETED.code());
	}

	/**
	 * @param query which items, in which order, and which page
	 * @return the page of items
	 */
	public List<TrashItemRow> page(TrashQuery query) {
		List<Object> args = new ArrayList<>();
		String where = where(query, args);
		String column = SORTS.getOrDefault(query.sort(), SORTS.get("deleted"));
		String direction = query.descending() ? " desc" : " asc";
		args.add(query.limit());
		args.add(query.offset());
		return jdbc.query("select " + ITEM_COLUMNS + TOP + where + " order by " + column + direction + ", t.hd_id"
				+ direction + " limit ? offset ?", this::itemRow, args.toArray());
	}

	/**
	 * @param query which items; the sort and the page are ignored
	 * @return how many items there are
	 */
	public long count(TrashQuery query) {
		List<Object> args = new ArrayList<>();
		String where = where(query, args);
		return jdbc.queryForObject("select count(*)" + TOP + where, Long.class, args.toArray());
	}

	/**
	 * @param cutoff the time
	 * @param limit the most items to answer
	 * @return the items with members removed before the cut-off, oldest removal first
	 */
	public List<Long> removedBefore(Instant cutoff, int limit) {
		return jdbc.queryForList("select hd_trashid from hd_trash_member where hd_state = ? and hd_removedat < ?"
				+ " group by hd_trashid order by min(hd_removedat), hd_trashid limit ?", Long.class,
				TrashState.REMOVED.code(), Timestamp.from(cutoff), limit);
	}

	/**
	 * An item with members removed before a cut-off, and when it first had one
	 * removed: the place a pass over them has got to
	 *
	 * @param id the item
	 * @param firstRemovedAt the earliest removal of any of its members, in
	 *        whatever state each is now
	 */
	public record RemovedItem(long id, Instant firstRemovedAt) {
	}

	/**
	 * A page of the items with members removed before the cut-off, the earliest
	 * first removal first. An item's first removal is the earliest of its
	 * members' removal times, which no route changes once set (a destroy or a
	 * restore keeps it), so a pass that pages on from the last item it read
	 * meets each item once, whatever it does to them meanwhile.
	 *
	 * @param cutoff the time
	 * @param after the last item of the page before, null for the first page
	 * @param limit the most items to answer
	 * @return the items after {@code after}
	 */
	public List<RemovedItem> removedBefore(Instant cutoff, RemovedItem after, int limit) {
		List<Object> args = new ArrayList<>(List.of(TrashState.REMOVED.code(), Timestamp.from(cutoff)));
		String having = "";
		if (after != null) {
			having = " having min(m.hd_removedat) > ? or (min(m.hd_removedat) = ? and m.hd_trashid > ?)";
			Timestamp first = Timestamp.from(after.firstRemovedAt());
			args.addAll(List.of(first, first, after.id()));
		}
		args.add(limit);
		return jdbc.query("select m.hd_trashid, min(m.hd_removedat) from hd_trash_member m"
				+ " where m.hd_removedat is not null and m.hd_trashid in (select r.hd_trashid from hd_trash_member r"
				+ " where r.hd_state = ? and r.hd_removedat < ?) group by m.hd_trashid" + having
				+ " order by min(m.hd_removedat), m.hd_trashid limit ?",
				(rs, n) -> new RemovedItem(rs.getLong(1), rs.getTimestamp(2).toInstant()), args.toArray());
	}

	/**
	 * @param id the item
	 * @param cutoff the time
	 * @return the item's members that are removed, and were removed before the
	 *         cut-off
	 */
	public List<TrashMember> membersRemovedBefore(long id, Instant cutoff) {
		return jdbc.query("select hd_objecttype, hd_objectid from hd_trash_member where hd_trashid = ?"
				+ " and hd_state = ? and hd_removedat < ? order by hd_objecttype, hd_objectid",
				(rs, n) -> new TrashMember(rs.getString(1).charAt(0), rs.getLong(2)), id, TrashState.REMOVED.code(),
				Timestamp.from(cutoff));
	}

	/**
	 * @param id the item
	 * @param member one of its members
	 * @return the member's row
	 */
	public Optional<TrashMemberRow> member(long id, TrashMember member) {
		return jdbc.query("select hd_trashid, hd_objecttype, hd_objectid, hd_state, hd_removedby, hd_removedbyname,"
				+ " hd_removedat, hd_restoredby, hd_restoredbyname, hd_restoredat, hd_destroyedat"
				+ " from hd_trash_member where hd_trashid = ? and hd_objecttype = ? and hd_objectid = ?",
				(rs, n) -> new TrashMemberRow(rs.getLong(1), new TrashMember(rs.getString(2).charAt(0), rs.getLong(3)),
						TrashState.of(rs.getString(4)), nullableLong(rs, 5), rs.getString(6), instant(rs, 7),
						nullableLong(rs, 8), rs.getString(9), instant(rs, 10), instant(rs, 11)),
				id, String.valueOf(member.type()), member.objectId()).stream().findFirst();
	}

	private static String where(TrashQuery query, List<Object> args) {
		args.add(query.tenantId());
		return switch (query.view()) {
		case "mine" -> {
			args.add(TrashState.DELETED.code());
			args.add(query.userId());
			yield " where t.hd_tenantid = ? and m.hd_state = ? and t.hd_deletedby = ?";
		}
		case "everyone" -> {
			args.add(TrashState.DELETED.code());
			yield " where t.hd_tenantid = ? and m.hd_state = ?";
		}
		case "removed" -> {
			args.add(TrashState.REMOVED.code());
			yield " where t.hd_tenantid = ? and m.hd_state = ?";
		}
		default -> throw new IllegalArgumentException("Unknown Trash view");
		};
	}

	private TrashItemRow itemRow(ResultSet rs, int n) throws SQLException {
		return new TrashItemRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5),
				rs.getString(6), nullableLong(rs, 7), names(rs.getString(8)), nullableLong(rs, 9), rs.getInt(10),
				rs.getInt(11), nullableLong(rs, 12), rs.getString(13), instant(rs, 14));
	}

	private static Long nullableLong(ResultSet rs, int column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static Instant instant(ResultSet rs, int column) throws SQLException {
		Timestamp time = rs.getTimestamp(column);
		return time == null ? null : time.toInstant();
	}

	/**
	 * A name may hold any character, so the path is a JSON array. One too long
	 * for the column drops its topmost names until it fits.
	 */
	private static String path(List<String> names) {
		List<String> kept = names == null ? List.of() : names;
		String json = json(kept);
		while (json.length() > PATH_LIMIT && !kept.isEmpty()) {
			kept = kept.subList(1, kept.size());
			json = json(kept);
		}
		return json;
	}

	private static String json(List<String> names) {
		try {
			return JSON.writeValueAsString(names);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<String> names(String json) {
		try {
			return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<String>>() {
			});
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}
}
