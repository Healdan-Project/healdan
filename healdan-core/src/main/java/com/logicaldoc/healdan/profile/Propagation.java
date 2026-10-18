package com.logicaldoc.healdan.profile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.profile.SubclassRule.Outcome;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Passes folder values down the tree (spec §6.7, "Propagation writes").
 * <p>
 * A folder's effective values are stamped on every folder and document below
 * it that has no local value of its own; a folder with a local value stops
 * that value's branch. A move or a restore re-stamps the whole subtree from
 * its new place, client and matter included. A Subclass that no longer
 * belongs to the effective Class is cleared.
 * <p>
 * It writes {@code ld_folder_ext} and {@code ld_document_ext} directly with
 * JDBC on CE's data source, joining the caller's transaction: no versions, no
 * history, no listener. A document whose values change is flagged for
 * indexing again. The tree is walked by {@code ld_parentid}, never by
 * {@code ld_path}, which a folder move rewrites only after its store.
 * Everything written is then evicted from the second-level cache.
 */
@Component
public class Propagation {

	/** Large id lists are queried in chunks */
	private static final int CHUNK = 500;

	private static final String VALUE_COLUMNS = "ld_stringvalue, ld_stringvalues, ld_intvalue, ld_doublevalue, ld_datevalue";

	private static final int[] UPDATE_TYPES = { Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.DOUBLE,
			Types.TIMESTAMP, Types.BIGINT, Types.VARCHAR };

	private static final int[] INSERT_TYPES = { Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.BIGINT,
			Types.DOUBLE, Types.TIMESTAMP, Types.BIGINT, Types.VARCHAR };

	/**
	 * Where a stamped folder sits
	 *
	 * @param kind what sort of place
	 * @param matter the matter the folder is, or is in; null in the templates
	 *        or while the matter's row doesn't exist yet
	 */
	public record Place(PlaceKind kind, Matter matter) {

		/**
		 * @return client and matter by attribute name, blank in the templates;
		 *         null to leave them alone (no matter row yet)
		 * @throws PersistenceException error at data layer
		 */
		public Map<String, String> fixed() throws PersistenceException {
			if (kind == PlaceKind.IN_TEMPLATES) {
				Map<String, String> blank = new LinkedHashMap<>();
				blank.put(HealdanConstants.ATTR_CLIENT, null);
				blank.put(HealdanConstants.ATTR_MATTER, null);
				return blank;
			}
			return matter != null ? Inheritance.get().fixedValues(matter) : null;
		}
	}

	/** The places where folders carry the profile */
	public enum PlaceKind {
		/** A matter's workspace, directly in a Store */
		WORKSPACE,
		/** Below a matter's workspace */
		IN_MATTER,
		/** Below the Templates Store */
		IN_TEMPLATES
	}

	/**
	 * A value as {@code ld_folder_ext} and {@code ld_document_ext} hold it
	 */
	record Value(String string, String strings, Long integer, Double decimal, Date date) {

		static final Value BLANK = new Value(null, null, null, null, null);

		static Value text(String value) {
			return value == null || value.isEmpty() ? BLANK : new Value(value, null, null, null, null);
		}

		boolean blank() {
			return (string == null || string.isEmpty()) && integer == null && decimal == null && date == null;
		}

		boolean same(Value other) {
			if (blank() || other.blank())
				return blank() && other.blank();
			return Objects.equals(string, other.string) && Objects.equals(integer, other.integer)
					&& Objects.equals(decimal, other.decimal)
					&& Objects.equals(date != null ? date.getTime() : null,
							other.date != null ? other.date.getTime() : null);
		}
	}

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	private final LocalValueDAO locals;

	private final ProfileSchema schema;

	private final MatterLocator locator;

	private final HealdanSettings settings;

	private final CacheEvictor evictor;

	@Autowired
	public Propagation(DataSource dataSource, LocalValueDAO locals, ProfileSchema schema, MatterLocator locator,
			HealdanSettings settings, CacheEvictor evictor) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
		this.locals = locals;
		this.schema = schema;
		this.locator = locator;
		this.settings = settings;
		this.evictor = evictor;
	}

	public static Propagation get() {
		return Context.get(Propagation.class);
	}

	/**
	 * Stamps the folder's effective values of the given attributes on every
	 * folder and document below it that has no local value for them. A folder
	 * with a local value stops that attribute's branch. Where the Class passes
	 * down, a Subclass that no longer belongs to it goes, a local one with its
	 * mark, and the parent's is taken if it fits ({@link SubclassRule}). Client
	 * and matter never pass down this way.
	 *
	 * @param folderId the folder whose values changed, as stored
	 * @param attributes the attributes whose effective value changed
	 */
	public void propagate(long folderId, Set<String> attributes) {
		Set<String> names = new HashSet<>(attributes);
		names.retainAll(schema.inheritable());
		names.removeAll(schema.fixed());
		if (names.isEmpty())
			return;

		Run run = new Run(false, null);
		Map<String, Value> values = stamped(LocalValueDAO.FOLDER, List.of(folderId)).getOrDefault(folderId,
				Map.of());
		run.walk(List.of(new Node(folderId, values, passing(names, Set.of()))));
		run.finish();
	}

	/**
	 * Re-stamps a folder and everything below it from where it is now, as
	 * after a move or a restore: every value that isn't local, and client and
	 * matter from the matter's row (left alone while it doesn't exist), and the
	 * profile template on every folder (locked) and document that hasn't got
	 * it, as a tree moved in from outside a matter. A local Subclass that no
	 * longer belongs to the Class goes, with its mark. A Store, the Templates
	 * Store or the root re-stamps each folder in it.
	 *
	 * @param folderId the folder
	 * @throws PersistenceException error at data layer
	 */
	public void restampSubtree(long folderId) throws PersistenceException {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select ld_parentid, ld_type, ld_foldref, ld_deleted, ld_templateid, ld_templocked from ld_folder where ld_id = ?",
				folderId);
		if (rows.isEmpty())
			return;
		Map<String, Object> row = rows.get(0);
		if (((Number) row.get("LD_DELETED")).intValue() != 0 || row.get("LD_FOLDREF") != null
				|| ((Number) row.get("LD_TYPE")).intValue() == Folder.TYPE_ALIAS)
			return;
		long parentId = ((Number) row.get("LD_PARENTID")).longValue();

		Place place = parentId != folderId ? place(parentId, folderId) : null;
		if (place == null) {
			Kind kind = locator.locate(folderId).kind();
			if (kind == Kind.ROOT || kind == Kind.STORE || kind == Kind.TEMPLATES_STORE)
				for (Long child : jdbc.queryForList(
						"select ld_id from ld_folder where ld_deleted = 0 and ld_parentid = ? and not ld_id = ?",
						Long.class, folderId, folderId))
					restampSubtree(child);
			return;
		}

		Set<String> names = new HashSet<>(schema.inheritable());
		names.removeAll(schema.fixed());
		Run run = new Run(true, values(place.fixed()));
		Map<String, Value> effective = run.stamp(LocalValueDAO.FOLDER, folderId, inherited(parentId), names,
				locals.locals(LocalValueDAO.FOLDER, List.of(folderId)),
				stamped(LocalValueDAO.FOLDER, List.of(folderId)));
		Number template = (Number) row.get("LD_TEMPLATEID");
		run.adoptTemplate(LocalValueDAO.FOLDER, folderId, template != null ? template.longValue() : null,
				((Number) row.get("LD_TEMPLOCKED")).intValue() != 0);
		run.walk(List.of(new Node(folderId, effective, names)));
		run.finish();
	}

	/**
	 * @param parentId the folder a folder is, or goes, in
	 * @param folderId the folder, 0 if it is new
	 * @return where the folder sits, null if folders there don't carry the
	 *         profile (the root, a Store, the Default workspace...)
	 * @throws PersistenceException error at data layer
	 */
	public Place place(long parentId, long folderId) throws PersistenceException {
		FolderPosition parent = locator.locate(parentId);
		if (parent.kind() == Kind.STORE)
			return new Place(PlaceKind.WORKSPACE, folderId != 0L ? MatterDAO.get().findByFolderId(folderId) : null);
		if (parent.isInsideMatter())
			return new Place(PlaceKind.IN_MATTER, parent.matter());
		if (parent.kind() == Kind.TEMPLATES_STORE || parent.kind() == Kind.IN_TEMPLATES)
			return new Place(PlaceKind.IN_TEMPLATES, null);
		return null;
	}

	/**
	 * @param folderId a folder
	 * @return its stamped values by attribute name, as the database holds them
	 */
	Map<String, Value> stamped(long folderId) {
		return stamped(LocalValueDAO.FOLDER, List.of(folderId)).getOrDefault(folderId, Map.of());
	}

	/**
	 * @return what a folder in the given one inherits: its stamped values if it
	 *         carries the profile, and the default Class when none is set
	 */
	private Map<String, Value> inherited(long parentId) throws PersistenceException {
		Map<String, Value> values = new HashMap<>();
		FolderPosition parent = locator.locate(parentId);
		if (parent.isInsideMatter() || parent.kind() == Kind.IN_TEMPLATES)
			stamped(parentId).forEach((name, value) -> {
				if (!schema.fixed().contains(name) && !value.blank())
					values.put(name, value);
			});
		if (!values.containsKey(HealdanConstants.ATTR_DOCCLASS))
			values.put(HealdanConstants.ATTR_DOCCLASS, Value.text(settings.defaultClass()));
		return values;
	}

	private static Map<String, Value> values(Map<String, String> fixed) {
		if (fixed == null)
			return null;
		Map<String, Value> values = new HashMap<>();
		fixed.forEach((name, value) -> values.put(name, Value.text(value)));
		return values;
	}

	/**
	 * @return the attributes that pass from a folder whose own passing ones
	 *         are given into an object with the given local attributes: where
	 *         the Class passes, so does the Subclass, which may have to go
	 */
	private static Set<String> passing(Set<String> names, Set<String> local) {
		Set<String> result = new HashSet<>(names);
		if (result.contains(HealdanConstants.ATTR_DOCCLASS))
			result.add(HealdanConstants.ATTR_DOCSUBCLASS);
		result.removeAll(local);
		return result;
	}

	private Map<Long, Map<String, Value>> stamped(char type, Collection<Long> ids) {
		String table = type == LocalValueDAO.FOLDER ? "ld_folder_ext" : "ld_document_ext";
		String idColumn = type == LocalValueDAO.FOLDER ? "ld_folderid" : "ld_docid";
		Map<Long, Map<String, Value>> result = new HashMap<>();
		for (List<Long> chunk : chunks(ids))
			named.query("select %s, ld_name, %s from %s where %s in (:ids)".formatted(idColumn, VALUE_COLUMNS, table,
					idColumn), new MapSqlParameterSource("ids", chunk), rs -> {
						result.computeIfAbsent(rs.getLong(1), id -> new HashMap<>()).put(rs.getString(2), value(rs));
					});
		return result;
	}

	private static Value value(ResultSet rs) throws SQLException {
		String string = rs.getString(3);
		String strings = rs.getString(4);
		long integer = rs.getLong(5);
		Long integerValue = rs.wasNull() ? null : integer;
		double decimal = rs.getDouble(6);
		Double decimalValue = rs.wasNull() ? null : decimal;
		Date date = rs.getTimestamp(7);
		return new Value(string, strings, integerValue, decimalValue, date);
	}

	private static List<List<Long>> chunks(Collection<Long> ids) {
		List<Long> all = new ArrayList<>(ids);
		List<List<Long>> chunks = new ArrayList<>();
		for (int i = 0; i < all.size(); i += CHUNK)
			chunks.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
		return chunks;
	}

	/**
	 * A folder of the walk
	 *
	 * @param id the folder
	 * @param values its effective values
	 * @param names the attributes in play on it, whose value may have changed
	 */
	private record Node(long id, Map<String, Value> values, Set<String> names) {
	}

	/**
	 * A folder or document found by the walk
	 *
	 * @param id the object
	 * @param folderId its folder or parent
	 * @param templateId its template, null if none
	 * @param locked true if it is a folder with its template locked
	 */
	private record Row(long id, long folderId, Long templateId, boolean locked) {
	}

	private static Long templateId(ResultSet rs, int column) throws SQLException {
		long id = rs.getLong(column);
		return rs.wasNull() ? null : id;
	}

	/**
	 * One propagation or re-stamp: what it writes, and what it has to evict
	 */
	private final class Run {

		/**
		 * True for a move or restore (every value, and the profile template),
		 * false for a folder value passing down
		 */
		private final boolean restamp;

		/** Client and matter for everything walked; null to leave them alone */
		private final Map<String, Value> fixed;

		private final Set<Long> folders = new HashSet<>();

		private final Set<Long> documents = new HashSet<>();

		private final Map<String, Boolean> belongs = new HashMap<>();

		private final long templateId;

		private Run(boolean restamp, Map<String, Value> fixed) {
			this.restamp = restamp;
			this.fixed = fixed;
			Long id = settings.profileTemplateId();
			this.templateId = id != null ? id : 0L;
		}

		/**
		 * Walks the tree below the given folders, level by level
		 */
		private void walk(List<Node> level) {
			while (!level.isEmpty()) {
				Map<Long, Node> nodes = new HashMap<>();
				level.forEach(node -> nodes.put(node.id(), node));
				stampDocuments(nodes);
				level = stampChildren(nodes);
			}
		}

		private void stampDocuments(Map<Long, Node> nodes) {
			List<Row> documentRows = new ArrayList<>();
			for (List<Long> chunk : chunks(nodes.keySet()))
				named.query(
						"select ld_id, ld_folderid, ld_templateid from ld_document where ld_deleted = 0 and ld_docref is null and ld_folderid in (:ids)",
						new MapSqlParameterSource("ids", chunk), rs -> {
							documentRows.add(new Row(rs.getLong(1), rs.getLong(2), templateId(rs, 3), false));
						});
			if (documentRows.isEmpty())
				return;

			List<Long> ids = documentRows.stream().map(Row::id).toList();
			Map<Long, Set<String>> local = locals.locals(LocalValueDAO.DOCUMENT, ids);
			Map<Long, Map<String, Value>> values = stamped(LocalValueDAO.DOCUMENT, ids);
			for (Row document : documentRows) {
				Node folder = nodes.get(document.folderId());
				Set<String> names = restamp ? folder.names() : passing(folder.names(), Set.of());
				stamp(LocalValueDAO.DOCUMENT, document.id(), folder.values(), names, local, values);
				adoptTemplate(LocalValueDAO.DOCUMENT, document.id(), document.templateId(), false);
			}
		}

		private List<Node> stampChildren(Map<Long, Node> nodes) {
			List<Row> children = new ArrayList<>();
			for (List<Long> chunk : chunks(nodes.keySet()))
				named.query(
						"select ld_id, ld_parentid, ld_templateid, ld_templocked from ld_folder where ld_deleted = 0 and ld_parentid in (:ids) and not ld_id = ld_parentid and not ld_type = %d and ld_foldref is null"
								.formatted(Folder.TYPE_ALIAS),
						new MapSqlParameterSource("ids", chunk), rs -> {
							children.add(new Row(rs.getLong(1), rs.getLong(2), templateId(rs, 3), rs.getInt(4) != 0));
						});
			if (children.isEmpty())
				return List.of();

			List<Long> ids = children.stream().map(Row::id).toList();
			Map<Long, Set<String>> local = locals.locals(LocalValueDAO.FOLDER, ids);
			Map<Long, Map<String, Value>> values = stamped(LocalValueDAO.FOLDER, ids);
			List<Node> next = new ArrayList<>();
			for (Row child : children) {
				Node parent = nodes.get(child.folderId());
				Set<String> names = restamp ? parent.names()
						: passing(parent.names(), ownLocals(local.get(child.id()), values.get(child.id())));
				if (names.isEmpty())
					continue;
				next.add(new Node(child.id(), stamp(LocalValueDAO.FOLDER, child.id(), parent.values(), names, local, values),
						names));
				adoptTemplate(LocalValueDAO.FOLDER, child.id(), child.templateId(), child.locked());
			}
			return next;
		}

		/**
		 * On a move or restore, gives an object the profile template (locked, on
		 * a folder) if it hasn't got it: a tree brought into a matter from
		 * outside passes the listeners only at its root. The rows of another
		 * template's attributes stay; they are the user's data.
		 *
		 * @param type {@code 'D'} or {@code 'F'}
		 * @param id the object
		 * @param current its template, null if none
		 * @param locked true if it is a folder with its template locked
		 */
		private void adoptTemplate(char type, long id, Long current, boolean locked) {
			if (!restamp || templateId == 0L)
				return;
			boolean other = current == null || current.longValue() != templateId;
			if (type == LocalValueDAO.FOLDER) {
				if (other || !locked) {
					jdbc.update("update ld_folder set ld_templateid = ?, ld_templocked = 1 where ld_id = ?", templateId,
							id);
					folders.add(id);
				}
			} else if (other) {
				jdbc.update("update ld_document set ld_templateid = ? where ld_id = ?", templateId, id);
				documents.add(id);
			}
		}

		/**
		 * @return the object's local attributes that hold a value
		 */
		private static Set<String> ownLocals(Set<String> local, Map<String, Value> values) {
			if (local == null)
				return Set.of();
			Set<String> result = new HashSet<>();
			for (String name : local)
				if (values != null && values.get(name) != null && !values.get(name).blank())
					result.add(name);
			return result;
		}

		/**
		 * Works out an object's effective values and writes those that differ
		 *
		 * @param type {@code 'D'} or {@code 'F'}
		 * @param id the object
		 * @param inherited the effective values of the folder above
		 * @param names the attributes in play
		 * @param localMarks the local marks of the objects at hand
		 * @param stamped the stamped values of the objects at hand
		 * @return the object's effective values
		 */
		private Map<String, Value> stamp(char type, long id, Map<String, Value> inherited, Set<String> names,
				Map<Long, Set<String>> localMarks, Map<Long, Map<String, Value>> stamped) {
			Map<String, Value> own = stamped.getOrDefault(id, Map.of());
			Set<String> marks = localMarks.getOrDefault(id, Set.of());
			Set<String> local = ownLocals(marks, own);
			Map<String, Value> effective = new HashMap<>(own);
			Set<String> written = new HashSet<>(names);

			for (String name : names) {
				if (local.contains(name))
					continue;
				// A local mark on a blank value means nothing: the value is inherited
				if (marks.contains(name))
					locals.setLocal(type, id, name, false);
				effective.put(name, inherited.getOrDefault(name, Value.BLANK));
			}

			/*
			 * SubclassRule: a Subclass the Class no longer takes goes, a local one
			 * with its mark, whether a move or a Class passed down made it foreign
			 * (plan, R16a, R21)
			 */
			if (names.contains(HealdanConstants.ATTR_DOCCLASS) || names.contains(HealdanConstants.ATTR_DOCSUBCLASS)) {
				written.add(HealdanConstants.ATTR_DOCSUBCLASS);
				boolean localSubclass = local.contains(HealdanConstants.ATTR_DOCSUBCLASS);
				Value from = inherited.getOrDefault(HealdanConstants.ATTR_DOCSUBCLASS, Value.BLANK);
				Outcome outcome = SubclassRule.settle(
						effective.getOrDefault(HealdanConstants.ATTR_DOCSUBCLASS, Value.BLANK).string(), localSubclass,
						effective.getOrDefault(HealdanConstants.ATTR_DOCCLASS, Value.BLANK).string(), from.string(), true,
						this::belongs);
				if (outcome != Outcome.KEEP) {
					if (localSubclass)
						locals.setLocal(type, id, HealdanConstants.ATTR_DOCSUBCLASS, false);
					effective.put(HealdanConstants.ATTR_DOCSUBCLASS, outcome == Outcome.INHERIT ? from : Value.BLANK);
				}
			}

			if (fixed != null) {
				effective.putAll(fixed);
				written.addAll(fixed.keySet());
			}

			for (String name : written) {
				Value value = effective.getOrDefault(name, Value.BLANK);
				if (!value.same(own.getOrDefault(name, Value.BLANK)))
					write(type, id, name, value);
			}
			return effective;
		}

		private boolean belongs(String subclass, String docClass) {
			return belongs.computeIfAbsent(subclass + "\u0000" + docClass, k -> schema.subclassBelongs(subclass, docClass));
		}

		private void write(char type, long id, String name, Value value) {
			boolean folder = type == LocalValueDAO.FOLDER;
			String table = folder ? "ld_folder_ext" : "ld_document_ext";
			String idColumn = folder ? "ld_folderid" : "ld_docid";
			int updated = jdbc.update(
					"update %s set ld_stringvalue = ?, ld_stringvalues = ?, ld_intvalue = ?, ld_doublevalue = ?, ld_datevalue = ? where %s = ? and ld_name = ?"
							.formatted(table, idColumn),
					new Object[] { value.string(), value.strings(), value.integer(), value.decimal(), value.date(), id,
							name },
					UPDATE_TYPES);
			// A row the object lacks, for an attribute added to the template later, comes from the template
			if (updated == 0 && !value.blank())
				jdbc.update("""
						insert into %s (%s, ld_mandatory, ld_type, ld_editor, ld_position, ld_stringvalue, ld_stringvalues,
						                ld_intvalue, ld_doublevalue, ld_datevalue, ld_name, ld_label, ld_setid, ld_hidden,
						                ld_readonly, ld_multiple, ld_parent, ld_dependson, ld_validation, ld_initialization)
						select ?, ld_mandatory, ld_type, ld_editor, ld_position, ?, ?, ?, ?, ?, ld_name, ld_label, ld_setid,
						       ld_hidden, ld_readonly, ld_multiple, ld_parent, ld_dependson, null, null
						  from ld_template_ext where ld_templateid = ? and ld_name = ?
						""".formatted(table, idColumn),
						new Object[] { id, value.string(), value.strings(), value.integer(), value.decimal(),
								value.date(), templateId, name },
						INSERT_TYPES);
			(folder ? folders : documents).add(id);
		}

		/**
		 * Flags the changed documents for indexing and evicts everything
		 * written: now, for reads later in the transaction, and again when it
		 * ends, for a read elsewhere that cached the committed rows meanwhile
		 * (or for a rollback)
		 */
		private void finish() {
			for (List<Long> chunk : chunks(documents))
				named.update("update ld_document set ld_indexed = :toIndex where ld_indexed = :indexed and ld_id in (:ids)",
						new MapSqlParameterSource("toIndex", IndexingStatus.TO_INDEX.ordinal())
								.addValue("indexed", IndexingStatus.INDEXED.ordinal()).addValue("ids", chunk));
			evictor.evictFolders(folders);
			evictor.evictDocuments(documents);
			evictor.evictFoldersAfterCompletion(folders);
			evictor.evictDocumentsAfterCompletion(documents);
		}
	}
}
