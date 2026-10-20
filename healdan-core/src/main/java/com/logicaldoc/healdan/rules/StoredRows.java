package com.logicaldoc.healdan.rules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import javax.sql.DataSource;

import org.hibernate.HibernateException;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.util.spring.Context;

import jakarta.annotation.PostConstruct;

/**
 * The rows the rules read: a folder or document as it was before the store
 * being checked, and the folder trees and their documents, all with JDBC on
 * CE's data source, so the reads join the caller's transaction.
 * <p>
 * A listener can't simply re-read the row. CE's {@code FolderDAO.move} and
 * {@code delete} change a folder the session manages, and the HQL query that
 * {@code FolderDAO.store} runs before the listeners (looking up aliases)
 * flushes that change, so the row already shows the new parent or the delete.
 * So this class also listens to Hibernate's updates: the first time a session
 * writes a folder or document, it keeps the row as it was, and after a store
 * the listeners have checked, they record the row as stored. A store is then
 * compared with the earlier of those, and with the database row only if the
 * session hasn't written the row yet.
 */
@Component
public class StoredRows implements PreUpdateEventListener {

	/**
	 * A folder's row
	 *
	 * @param id the folder
	 * @param parentId its parent
	 * @param name its name
	 * @param type its type
	 * @param foldRef the folder it is an alias of, if any
	 * @param deleted its deletion code, 0 if live
	 * @param securityRef the folder whose permissions it uses, null if it has
	 *        its own
	 */
	public record FolderRow(long id, long parentId, String name, int type, Long foldRef, int deleted,
			Long securityRef) {

		/**
		 * @return true if the folder is an alias of another, or carries the
		 *         reference of one (CE's copy gives an alias's copy its
		 *         reference)
		 */
		public boolean alias() {
			return type == Folder.TYPE_ALIAS || foldRef != null;
		}

		/**
		 * @param folder the folder
		 * @return its row as the folder holds it now
		 */
		public static FolderRow of(Folder folder) {
			return new FolderRow(folder.getId(), folder.getParentId(), folder.getName(), folder.getType(),
					folder.getFoldRef(), folder.getDeleted(), folder.getSecurityRef());
		}
	}

	/**
	 * The documents directly in a folder
	 *
	 * @param documents how many
	 * @param shortcuts how many of them are shortcuts
	 */
	public record Contents(long documents, long shortcuts) {

		static final Contents NONE = new Contents(0, 0);
	}

	private static final Logger log = LoggerFactory.getLogger(StoredRows.class);

	private static final String FOLDER_COLUMNS = "ld_id, ld_parentid, ld_name, ld_type, ld_foldref, ld_deleted, ld_securityref";

	/** Large id lists are queried in chunks */
	private static final int CHUNK = 500;

	private final SessionFactory sessionFactory;

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	/** Each session's folder rows, from before the session wrote them */
	private final Map<Object, Map<Long, FolderRow>> folders = Collections.synchronizedMap(new WeakHashMap<>());

	/** Each session's document folders, from before the session wrote them */
	private final Map<Object, Map<Long, Long>> documentFolders = Collections.synchronizedMap(new WeakHashMap<>());

	/** Each persister's property names */
	private final Map<String, List<String>> propertyNames = Collections.synchronizedMap(new HashMap<>());

	@Autowired
	public StoredRows(@Qualifier("sessionFactory") SessionFactory sessionFactory, DataSource dataSource) {
		this.sessionFactory = sessionFactory;
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
	}

	/**
	 * @return the bean
	 */
	public static StoredRows get() {
		return Context.get(StoredRows.class);
	}

	@PostConstruct
	public void listen() {
		sessionFactory.unwrap(SessionFactoryImplementor.class).getEventListenerRegistry()
				.appendListeners(EventType.PRE_UPDATE, this);
	}

	@Override
	public boolean onPreUpdate(PreUpdateEvent event) {
		Object[] old = event.getOldState();
		if (old == null || !(event.getId() instanceof Long id))
			return false;

		Object entity = event.getEntity();
		if (!(entity instanceof Folder) && !(entity instanceof Document))
			return false;

		// Never let a problem here break the update: the row is then read from the database
		try {
			List<String> names = propertyNames.computeIfAbsent(event.getPersister().getEntityName(),
					name -> Arrays.asList(event.getPersister().getPropertyNames()));
			if (entity instanceof Folder) {
				FolderRow row = new FolderRow(id, (Long) old[names.indexOf("parentId")],
						(String) old[names.indexOf("name")], (Integer) old[names.indexOf("type")],
						(Long) old[names.indexOf("foldRef")], (Integer) old[names.indexOf("deleted")],
						(Long) old[names.indexOf("securityRef")]);
				sessionMap(folders, event.getSession()).putIfAbsent(id, row);
			} else {
				Folder folder = (Folder) old[names.indexOf("folder")];
				sessionMap(documentFolders, event.getSession()).putIfAbsent(id,
						folder != null ? folder.getId() : null);
			}
		} catch (RuntimeException e) {
			log.warn("Cannot record the row of {} {} before its update", entity.getClass().getSimpleName(), id, e);
		}

		// Never veto the update
		return false;
	}

	/**
	 * @param folderId the folder
	 * @return its row before the store being checked, null if there is none
	 */
	public FolderRow folder(long folderId) {
		Map<Long, FolderRow> written = currentMap(folders);
		if (written != null && written.containsKey(folderId))
			return written.get(folderId);
		List<FolderRow> found = jdbc.query("select %s from ld_folder where ld_id = ?".formatted(FOLDER_COLUMNS),
				(rs, i) -> row(rs), folderId);
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * @param documentId the document
	 * @return its folder before the store being checked, null if there is no
	 *         row
	 */
	public Long documentFolder(long documentId) {
		Map<Long, Long> written = currentMap(documentFolders);
		if (written != null && written.containsKey(documentId))
			return written.get(documentId);
		List<Long> found = jdbc.queryForList("select ld_folderid from ld_document where ld_id = ?", Long.class,
				documentId);
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * @param entity a folder or document being stored
	 * @return true if the current session manages that very object, so that a
	 *         query may already have flushed its changes and a JDBC read can't
	 *         tell what it held before; false outside a transaction or for an
	 *         object the session doesn't hold (CE merges it at the store)
	 */
	public boolean inSession(Object entity) {
		try {
			return sessionFactory.getCurrentSession().contains(entity);
		} catch (RuntimeException e) {
			// Outside a transaction, or not an entity
			return false;
		}
	}

	/**
	 * Records the folder as stored, once the listeners have let the store
	 * through
	 *
	 * @param folder the folder
	 */
	public void stored(Folder folder) {
		Map<Long, FolderRow> map = currentMap(folders, true);
		if (map != null && folder.getId() != 0L)
			map.put(folder.getId(), FolderRow.of(folder));
	}

	/**
	 * Records the document as stored, once the listeners have let the store
	 * through
	 *
	 * @param document the document
	 */
	public void stored(Document document) {
		Map<Long, Long> map = currentMap(documentFolders, true);
		if (map != null && document.getId() != 0L)
			map.put(document.getId(), document.getFolder() != null ? document.getFolder().getId() : null);
	}

	/**
	 * @param documentId a document, deleted or not
	 * @return the document it is a shortcut to, null if it isn't one
	 */
	public Long documentRef(long documentId) {
		List<Long> found = jdbc.queryForList("select ld_docref from ld_document where ld_id = ?", Long.class,
				documentId);
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * @param parentId a folder
	 * @param name a name
	 * @param like true to match as CE's merge does ({@code like}), false as its
	 *        copy does ({@code =})
	 * @return the live child of that name, null if none
	 */
	public FolderRow liveChild(long parentId, String name, boolean like) {
		List<FolderRow> found = jdbc.query(
				"select %s from ld_folder where ld_deleted = 0 and ld_parentid = ? and ld_name %s ? and not ld_id = ? order by ld_id"
						.formatted(FOLDER_COLUMNS, like ? "like" : "="),
				(rs, i) -> row(rs), parentId, name, parentId);
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * @param parentId a folder
	 * @return its live children
	 */
	public List<FolderRow> children(long parentId) {
		return children(List.of(parentId), false);
	}

	/**
	 * @param parentIds folders
	 * @param withDeleted true to include the children deleted with code 1, as
	 *        a folder restore brings back
	 * @return their children
	 */
	public List<FolderRow> children(Collection<Long> parentIds, boolean withDeleted) {
		List<FolderRow> rows = new ArrayList<>();
		for (List<Long> chunk : chunks(parentIds))
			rows.addAll(named.query(
					"select %s from ld_folder where %s and ld_parentid in (:ids) and not ld_id = ld_parentid order by ld_id"
							.formatted(FOLDER_COLUMNS, deleted(withDeleted)),
					new MapSqlParameterSource("ids", chunk), (rs, i) -> row(rs)));
		return rows;
	}

	/**
	 * @param rootId a folder
	 * @return every live folder below it, top-down
	 */
	public List<FolderRow> descendants(long rootId) {
		List<FolderRow> all = new ArrayList<>();
		Set<Long> seen = new HashSet<>(List.of(rootId));
		List<Long> level = List.of(rootId);
		while (!level.isEmpty()) {
			List<Long> next = new ArrayList<>();
			for (FolderRow child : children(level, false))
				if (seen.add(child.id())) {
					all.add(child);
					next.add(child.id());
				}
			level = next;
		}
		return all;
	}

	/**
	 * @param folderId a folder
	 * @return its live documents
	 */
	public Contents contents(long folderId) {
		return contents(List.of(folderId), false).getOrDefault(folderId, Contents.NONE);
	}

	/**
	 * @param folderIds folders
	 * @param withDeleted true to include the documents deleted with code 1, as
	 *        a folder restore brings back
	 * @return the documents of each folder that has some
	 */
	public Map<Long, Contents> contents(Collection<Long> folderIds, boolean withDeleted) {
		Map<Long, Contents> contents = new HashMap<>();
		for (List<Long> chunk : chunks(folderIds))
			named.query(
					"select ld_folderid, count(*), count(ld_docref) from ld_document where %s and ld_folderid in (:ids) group by ld_folderid"
							.formatted(deleted(withDeleted)),
					new MapSqlParameterSource("ids", chunk),
					rs -> {
						contents.put(rs.getLong(1), new Contents(rs.getLong(2), rs.getLong(3)));
					});
		return contents;
	}

	/**
	 * @param rootId a folder
	 * @return the folder and every live folder below it
	 */
	public List<Long> tree(long rootId) {
		List<Long> ids = new ArrayList<>(List.of(rootId));
		descendants(rootId).forEach(row -> ids.add(row.id()));
		return ids;
	}

	/**
	 * @param folderIds folders
	 * @return the documents in them, deleted or not
	 */
	public List<Long> documentIds(Collection<Long> folderIds) {
		List<Long> ids = new ArrayList<>();
		for (List<Long> chunk : chunks(folderIds))
			ids.addAll(named.queryForList("select ld_id from ld_document where ld_folderid in (:ids)",
					new MapSqlParameterSource("ids", chunk), Long.class));
		return ids;
	}

	/**
	 * @param rootId a folder
	 * @param type a folder type
	 * @return true if the folder or a live folder below it has the type
	 */
	public boolean treeHasType(long rootId, int type) {
		FolderRow root = folder(rootId);
		if (root != null && root.type() == type)
			return true;
		return descendants(rootId).stream().anyMatch(row -> row.type() == type);
	}

	/**
	 * Reads the delete permission on a folder, deleted or not; CE's own check
	 * sees nothing on a deleted folder
	 *
	 * @param folderId the folder
	 * @param userId the user
	 * @return true if one of the user's groups has DELETE on the folder, or on
	 *         the folder whose security it uses
	 */
	public boolean mayDelete(long folderId, long userId) {
		List<Long> refs = jdbc.queryForList("select ld_securityref from ld_folder where ld_id = ?", Long.class,
				folderId);
		long aclFolder = !refs.isEmpty() && refs.get(0) != null ? refs.get(0) : folderId;
		Integer count = jdbc.queryForObject(
				"select count(*) from ld_folder_acl where ld_folderid = ? and ld_delete = 1 and ld_groupid in (select ld_groupid from ld_usergroup where ld_userid = ?)",
				Integer.class, aclFolder, userId);
		return count != null && count > 0;
	}

	/**
	 * @param documentId a document
	 * @return its {@code ld_deleted} code, null if there is no row
	 */
	public Integer documentDeleted(long documentId) {
		List<Integer> found = jdbc.queryForList("select ld_deleted from ld_document where ld_id = ?", Integer.class,
				documentId);
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * Reads the delete permission on a document, deleted or not, as CE's
	 * {@code DocumentDAO.getAllowedPermissions} reads it on a live one: the
	 * entries of the document's own ACL for the user's groups, else its
	 * folder's
	 *
	 * @param documentId the document
	 * @param userId the user
	 * @return true if the user has DELETE on the document
	 */
	public boolean mayDeleteDocument(long documentId, long userId) {
		// The entries that grant something, on the permission columns CE reads
		List<Integer> entries = jdbc.queryForList("""
				select ld_delete from ld_document_acl
				 where ld_docid = ? and ld_groupid in (select ld_groupid from ld_usergroup where ld_userid = ?)
				   and (ld_read = 1 or ld_write = 1 or ld_security = 1 or ld_immutable = 1 or ld_delete = 1
				        or ld_rename = 1 or ld_sign = 1 or ld_archive = 1 or ld_workflow = 1 or ld_download = 1
				        or ld_calendar = 1 or ld_subscription = 1 or ld_print = 1 or ld_password = 1 or ld_move = 1
				        or ld_email = 1 or ld_automation = 1 or ld_readingreq = 1 or ld_preview = 1
				        or ld_customid = 1 or ld_revision = 1)
				""", Integer.class, documentId, userId);
		if (!entries.isEmpty())
			return entries.contains(1);
		Long folderId = documentFolder(documentId);
		return folderId != null && mayDelete(folderId, userId);
	}

	private static String deleted(boolean withDeleted) {
		return withDeleted ? "ld_deleted in (0, 1)" : "ld_deleted = 0";
	}

	private static List<List<Long>> chunks(Collection<Long> ids) {
		List<Long> all = new ArrayList<>(ids);
		List<List<Long>> chunks = new ArrayList<>();
		for (int i = 0; i < all.size(); i += CHUNK)
			chunks.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
		return chunks;
	}

	private static FolderRow row(ResultSet rs) throws SQLException {
		long id = rs.getLong(1);
		long parentId = rs.getLong(2);
		String name = rs.getString(3);
		int type = rs.getInt(4);
		long foldRef = rs.getLong(5);
		Long ref = rs.wasNull() ? null : foldRef;
		int deleted = rs.getInt(6);
		long securityRef = rs.getLong(7);
		return new FolderRow(id, parentId, name, type, ref, deleted, rs.wasNull() ? null : securityRef);
	}

	private static <V> Map<Long, V> sessionMap(Map<Object, Map<Long, V>> maps, Object session) {
		synchronized (maps) {
			return maps.computeIfAbsent(session, s -> new HashMap<>());
		}
	}

	private <V> Map<Long, V> currentMap(Map<Object, Map<Long, V>> maps) {
		return currentMap(maps, false);
	}

	private <V> Map<Long, V> currentMap(Map<Object, Map<Long, V>> maps, boolean create) {
		Object session;
		try {
			session = sessionFactory.getCurrentSession();
		} catch (HibernateException e) {
			// Outside a transaction: nothing was written
			return null;
		}
		if (create)
			return sessionMap(maps, session);
		return maps.get(session);
	}
}
