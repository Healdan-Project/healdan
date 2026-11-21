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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicaldoc.healdan.listing.ListingSql;
import com.logicaldoc.util.CollectionUtil;
import com.logicaldoc.util.spring.Context;

/**
 * The Trash's record, in <code>hd_trash</code> and <code>hd_trash_member</code>: each delete, and where each
 * folder or document it took stands.
 */
@Component
public class TrashStore {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The most digits a recorded doc # has, as {@code TrashRecorder.docNumber} records one */
	private static final int DOC_NUMBER_DIGITS = 18;

	/** The doc # as a number; null for a folder, which records none */
	private static final String DOC_NUMBER = "case when %s then cast(t.hd_docnumber as decimal(%d,0)) end"
			.formatted(ListingSql.digits("t.hd_docnumber", DOC_NUMBER_DIGITS), DOC_NUMBER_DIGITS);

	/** Where it was deleted from: the path recorded at the delete, step by step ({@link #pathOrder}) */
	private static final String FROM_PATH = pathOrder("t.hd_frompath");

	/**
	 * What the page's sort names stand for; the text sent is never put in the SQL. Deleted By and Removed By are the
	 * names recorded at the time, and Destroyed sorts as Removed, as the night it's destroyed follows its removal.
	 */
	private static final Map<String, String> SORTS = Map.of("name", "lower(t.hd_name)", "docNumber", DOC_NUMBER,
			"deletedFrom", FROM_PATH, "deletedBy", "lower(t.hd_deletedbyname)", "deleted", "t.hd_deletedat",
			"removedBy", "lower(m.hd_removedbyname)", "removed", "m.hd_removedat", "destroyed", "m.hd_removedat");

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

	/** Ids in an {@code in (...)} list at a time */
	static final int CHUNK = 1000;

	/** An item's folders removed before a cut-off, given as {@code :id}, {@code :removed} and {@code :cutoff} */
	private static final String REMOVED_FOLDERS = "select m.hd_objectid from hd_trash_member m where m.hd_trashid = :id"
			+ " and m.hd_objecttype = 'F' and m.hd_state = :removed and m.hd_removedat < :cutoff";

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	@Autowired
	public TrashStore(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
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
		// PostgreSQL's driver gives every column of the inserted row as its keys, so hd_id is taken by its name; a map
		// of one is that key alone
		Map<String, Object> generated = keys.getKeyList().get(0);
		Object key = generated.size() == 1 ? generated.values().iterator().next() : generated.get("hd_id");
		long id = ((Number) key).longValue();
		if (!members.isEmpty())
			jdbc.batchUpdate(INSERT_MEMBER, members.stream().map(member -> new Object[] { id,
					String.valueOf(member.type()), member.objectId(), TrashState.DELETED.code() }).toList());
		return id;
	}

	/**
	 * Moves members, in whatever state, from one item to another, up to
	 * {@link #CHUNK} of a type in a statement
	 *
	 * @param from the item that holds them
	 * @param members the members
	 * @param to the item they go to, which mustn't hold them already
	 * @return how many moved
	 */
	public int moveMembers(long from, Collection<TrashMember> members, long to) {
		return byType(members, new MapSqlParameterSource("to", to).addValue("from", from),
				"update hd_trash_member set hd_trashid = :to where hd_trashid = :from and hd_objecttype = :type"
						+ " and hd_objectid in (:ids)");
	}

	/**
	 * Drops the item's deleted members whose rows are live: what a delete
	 * recorded before it ran and then didn't take (spec 5.2), so that a later
	 * delete of them records an item of its own
	 *
	 * @param id the item
	 * @return how many were dropped
	 */
	public int dropLive(long id) {
		return jdbc.update("delete from hd_trash_member where hd_trashid = ? and hd_state = ? and ((hd_objecttype = 'F'"
				+ " and hd_objectid in (select f.ld_id from ld_folder f where f.ld_deleted = 0)) or (hd_objecttype = 'D'"
				+ " and hd_objectid in (select d.ld_id from ld_document d where d.ld_deleted = 0)))", id,
				TrashState.DELETED.code());
	}

	/**
	 * Deletes the item's own row once it has no members
	 *
	 * @param id the item
	 * @return true if it was deleted
	 */
	public boolean deleteIfEmpty(long id) {
		Long left = jdbc.queryForObject("select count(*) from hd_trash_member where hd_trashid = ?", Long.class, id);
		return left != null && left == 0 && jdbc.update("delete from hd_trash where hd_id = ?", id) > 0;
	}

	/**
	 * Marks destroyed the item's members in a state whose rows no longer exist:
	 * destroyed outside the Trash, as the Classic Interface's Destroy Documents
	 * does
	 *
	 * @param id the item
	 * @param from the state they must be in
	 * @param at when it was found
	 * @return how many changed
	 */
	public int destroyedIfGone(long id, TrashState from, Instant at) {
		return jdbc.update("update hd_trash_member set hd_state = ?, hd_destroyedat = ? where hd_trashid = ?"
				+ " and hd_state = ? and ((hd_objecttype = 'F' and not exists (select 1 from ld_folder f"
				+ " where f.ld_id = hd_trash_member.hd_objectid)) or (hd_objecttype = 'D' and not exists"
				+ " (select 1 from ld_document d where d.ld_id = hd_trash_member.hd_objectid)))",
				TrashState.DESTROYED.code(), Timestamp.from(at), id, from.code());
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
	 * A member in another state is left as it is. A statement takes up to
	 * {@link #CHUNK} members of a type, so a folder of thousands changes in a
	 * few.
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
		case REMOVED -> ", hd_removedby = :by, hd_removedbyname = :byName, hd_removedat = :at";
		case RESTORED -> ", hd_restoredby = :by, hd_restoredbyname = :byName, hd_restoredat = :at";
		case DESTROYED -> ", hd_destroyedat = :at";
		case DELETED -> "";
		};
		MapSqlParameterSource params = new MapSqlParameterSource("to", to.code()).addValue("by", byId)
				.addValue("byName", byName).addValue("at", Timestamp.from(at)).addValue("id", id)
				.addValue("from", from.code());
		return byType(members, params, "update hd_trash_member set hd_state = :to" + set + " where hd_trashid = :id"
				+ " and hd_objecttype = :type and hd_objectid in (:ids) and hd_state = :from");
	}

	/**
	 * Runs an update on members, a statement for each type and chunk of ids
	 *
	 * @param params the update's parameters but {@code :type} and {@code :ids}
	 * @param sql the update, naming the members by {@code :type} and
	 *        {@code :ids}
	 * @return how many rows it changed
	 */
	private int byType(Collection<TrashMember> members, MapSqlParameterSource params, String sql) {
		int changed = 0;
		for (char type : new char[] { 'F', 'D' }) {
			for (List<Long> chunk : CollectionUtil.partition(TrashMember.ids(members, type), CHUNK))
				changed += named.update(sql, new MapSqlParameterSource(params.getValues())
						.addValue("type", String.valueOf(type)).addValue("ids", chunk));
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
	 * @return the page of items: an item with no value for the sort last
	 *         either way, and ties by the item's age, in the sort's direction
	 */
	public List<TrashItemRow> page(TrashQuery query) {
		List<Object> args = new ArrayList<>();
		String where = where(query, args);
		String key = SORTS.getOrDefault(query.sort(), SORTS.get("deleted"));
		String direction = query.descending() ? " desc" : " asc";
		args.add(query.limit());
		args.add(query.offset());
		return jdbc.query("select " + ITEM_COLUMNS + TOP + where + " order by " + ListingSql.nullsLast(key) + ", " + key
				+ direction + ", t.hd_id" + direction + " limit ? offset ?", this::itemRow, args.toArray());
	}

	/**
	 * A recorded path to sort by, so that items order by its steps, ignoring
	 * case, a folder before what it holds: {@code ["A"]}, {@code ["A","B"]},
	 * {@code ["a","c"]}, {@code ["A B"]}. The path is the JSON array of names
	 * {@link #path} writes, which as text wouldn't sort so ({@code ","} sorts
	 * below {@code "]}). So the array's brackets and outer quotes are taken off
	 * and the names joined by {@code chr(1)}, which sorts below every
	 * character the text holds, as Jackson writes a control character as an
	 * escape. An escaped backslash and an escaped quote are set aside as
	 * {@code chr(2)} and {@code chr(3)} first, so that what parts two names, a
	 * quote, a comma and a quote, is found only between names, and are put back
	 * as themselves after; any other escape, a control character's, sorts as
	 * its text. An empty path, outside the Stores, shows no place, so it is
	 * null, as one not recorded is.
	 * <p>
	 * It's text functions alone, which no value fails. The database's
	 * collation sorts by code point, as Healdan makes it.
	 *
	 * @param path the column holding the path
	 * @return the order's expression
	 */
	private static String pathOrder(String path) {
		// The shortest array of a name, [""], has four characters, and a shorter one would give substring a negative
		// length
		String names = "substring(%1$s from 3 for length(%1$s) - 4)".formatted(path);
		String setAside = replace(replace(names, "concat(chr(92), chr(92))", "chr(2)"), "concat(chr(92), '\"')", "chr(3)");
		String joined = replace(setAside, "'\",\"'", "chr(1)");
		String putBack = replace(replace(joined, "chr(3)", "'\"'"), "chr(2)", "chr(92)");
		return "case when length(%s) >= 4 then lower(%s) end".formatted(path, putBack);
	}

	private static String replace(String text, String from, String to) {
		return "replace(%s, %s, %s)".formatted(text, from, to);
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
	 * @param cutoff the time
	 * @return the item's folders that are removed, and were removed before
	 *         the cut-off
	 */
	public List<Long> foldersRemovedBefore(long id, Instant cutoff) {
		return named.queryForList(REMOVED_FOLDERS, removedFolders(id, cutoff), Long.class);
	}

	/**
	 * @param id the item
	 * @param cutoff the time
	 * @return by each of {@link #foldersRemovedBefore}, the folders, live or
	 *         deleted, that take their security from it: one read of
	 *         {@code ld_folder} whatever their number
	 */
	public Map<Long, Set<Long>> securityTakers(long id, Instant cutoff) {
		Map<Long, Set<Long>> takers = new HashMap<>();
		named.query("select f.ld_id, f.ld_securityref from ld_folder f where f.ld_id <> f.ld_securityref"
				+ " and f.ld_securityref in (" + REMOVED_FOLDERS + ")", removedFolders(id, cutoff), rs -> {
					takers.computeIfAbsent(rs.getLong(2), k -> new HashSet<>()).add(rs.getLong(1));
				});
		return takers;
	}

	private static MapSqlParameterSource removedFolders(long id, Instant cutoff) {
		return new MapSqlParameterSource("id", id).addValue("removed", TrashState.REMOVED.code()).addValue("cutoff",
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
				(rs, n) -> memberRow(rs), id, String.valueOf(member.type()), member.objectId()).stream().findFirst();
	}

	/**
	 * @param ids items, such as a page's
	 * @return each item's top member's row, by item, in one query
	 */
	public Map<Long, TrashMemberRow> tops(Collection<Long> ids) {
		Map<Long, TrashMemberRow> tops = new HashMap<>();
		if (ids.isEmpty())
			return tops;
		named.query("select m.hd_trashid, m.hd_objecttype, m.hd_objectid, m.hd_state,"
				+ " m.hd_removedby, m.hd_removedbyname, m.hd_removedat, m.hd_restoredby, m.hd_restoredbyname,"
				+ " m.hd_restoredat, m.hd_destroyedat" + TOP + " where t.hd_id in (:ids)",
				new MapSqlParameterSource("ids", new ArrayList<>(ids)), rs -> {
					tops.put(rs.getLong(1), memberRow(rs));
				});
		return tops;
	}

	private static TrashMemberRow memberRow(ResultSet rs) throws SQLException {
		return new TrashMemberRow(rs.getLong(1), new TrashMember(rs.getString(2).charAt(0), rs.getLong(3)),
				TrashState.of(rs.getString(4)), nullableLong(rs, 5), rs.getString(6), instant(rs, 7),
				nullableLong(rs, 8), rs.getString(9), instant(rs, 10), instant(rs, 11));
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
