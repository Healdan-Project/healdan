package com.logicaldoc.healdan.searchfolder;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.util.BasicPlane;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Search folders (spec 5.4): folders of type
 * {@link HealdanConstants#FOLDER_TYPE_SEARCH} whose list of documents comes
 * from a rule, kept in {@code hd_searchfolder}.
 * <p>
 * Creating and editing run inside {@link HealdanOperation.Kind#SEARCH_FOLDER},
 * which lets the folder have type 1001; the filing rules still decide where it
 * may go (in a matter or below a template, by any route) and who may create
 * or rename it there (a matter's top level and the templates are for admins,
 * and so is changing a template's rule). That operation also exempts the
 * store from the permission rule on security references (plan, R32), so
 * nothing the caller gives reaches the folder but its name: the value object
 * is built here, and an edit changes the name and the rule, never the type or
 * where the permissions come from.
 * <p>
 * The rule runs each time the folder is opened ({@link #evaluate}): the
 * documents of the search folder's own matter with a change in the window,
 * newest change first, that the viewer can read.
 */
@Component
public class SearchFolderService {

	static final int MAX_NAME_LENGTH = 255;

	static final String NO_PARENT = "That folder no longer exists.";

	static final String NO_SEARCH_FOLDER = "That search folder no longer exists.";

	static final String NO_ADD = "You need the add permission on this folder to create a search folder in it.";

	static final String NO_WRITE = "You need the write permission on this search folder to edit it.";

	static final String NO_RENAME = "You need the rename permission on this search folder to rename it.";

	static final String BAD_NAME = "A search folder name can't be empty or longer than %d characters.";

	static final String OUTSIDE_PLANE_NAME = "A search folder name can't contain emoji or other characters the database can't store.";

	static final String BAD_DAYS = "A search folder's window must be at least 1 day.";

	static final String SUBCLASS_WITHOUT_CLASS = "A search folder can't filter by Subclass without a Class.";

	/** Large id lists are queried in chunks */
	private static final int CHUNK = 500;

	private final SearchFolderRuleDAO rules;

	private final FilingRules filingRules;

	private final MatterLocator locator;

	private final CacheEvictor evictor;

	private final NamedParameterJdbcTemplate jdbc;

	@Autowired
	public SearchFolderService(SearchFolderRuleDAO rules, FilingRules filingRules, MatterLocator locator,
			CacheEvictor evictor, DataSource dataSource) {
		this.rules = rules;
		this.filingRules = filingRules;
		this.locator = locator;
		this.evictor = evictor;
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @return the service bean
	 */
	public static SearchFolderService get() {
		return Context.get(SearchFolderService.class);
	}

	/**
	 * Creates a search folder, which inherits its parent's permissions as any
	 * new folder does
	 *
	 * @param parentId where; the actor needs the add permission there
	 * @param name its name; it is trimmed
	 * @param rule its rule; the folder id in it is ignored
	 * @param actor the user who asks
	 * @return the new search folder
	 * @throws PersistenceException a refusal (a {@link HealdanRuleException}
	 *         with a message for the user), or an error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public Folder create(long parentId, String name, SearchFolderRule rule, User actor) throws PersistenceException {
		return run(() -> doCreate(parentId, name, rule, actor));
	}

	private Folder doCreate(long parentId, String name, SearchFolderRule rule, User actor)
			throws PersistenceException {
		String cleanName = cleanName(name);
		SearchFolderRule cleanRule = cleanRule(rule);
		FolderDAO dao = FolderDAO.get();
		Folder parent = dao.findById(parentId);
		if (parent == null)
			throw new HealdanRuleException(NO_PARENT);
		if (!dao.isPermissionAllowed(Permission.ADD, parentId, actor.getId()))
			throw new HealdanRuleException(NO_ADD);

		Folder vo = new Folder(cleanName);
		vo.setType(HealdanConstants.FOLDER_TYPE_SEARCH);
		vo.setTenantId(parent.getTenantId());
		Folder created = dao.create(parent, vo, true, history(actor));
		// The cache isn't transactional: a failure further on mustn't leave the folder cached
		evictor.evictFoldersOnRollback(List.of(created.getId()));
		rules.save(cleanRule.withFolderId(created.getId()));
		return created;
	}

	/**
	 * Changes a search folder's name and rule
	 *
	 * @param folderId the search folder; the actor needs the write permission
	 *        on it, and the rename permission when the name changes. In the
	 *        templates only admins may change it
	 * @param name its name; it is trimmed
	 * @param rule its rule; the folder id in it is ignored
	 * @param actor the user who asks
	 * @throws PersistenceException a refusal (a {@link HealdanRuleException}
	 *         with a message for the user), or an error at data layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public void update(long folderId, String name, SearchFolderRule rule, User actor) throws PersistenceException {
		run(() -> {
			doUpdate(folderId, name, rule, actor);
			return null;
		});
	}

	private void doUpdate(long folderId, String name, SearchFolderRule rule, User actor)
			throws PersistenceException {
		FolderDAO dao = FolderDAO.get();
		Folder folder = dao.findById(folderId, true);
		if (folder == null)
			throw new HealdanRuleException(NO_SEARCH_FOLDER);
		// Permission first, so a user who can't write a folder doesn't learn what kind it is
		if (!dao.isWriteAllowed(folderId, actor.getId()))
			throw new HealdanRuleException(NO_WRITE);
		if (folder.getType() != HealdanConstants.FOLDER_TYPE_SEARCH)
			throw new HealdanRuleException(NO_SEARCH_FOLDER);
		String cleanName = cleanName(name);
		SearchFolderRule cleanRule = cleanRule(rule);
		// A template's rule is copied by every new matter
		filingRules.checkFolderEdit(folderId, actor);
		boolean renamed = !cleanName.equals(folder.getName());
		if (renamed && !dao.isPermissionAllowed(Permission.RENAME, folderId, actor.getId()))
			throw new HealdanRuleException(NO_RENAME);

		rules.save(cleanRule.withFolderId(folderId));
		if (renamed) {
			FolderHistory history = history(actor);
			history.setEvent(FolderEvent.RENAMED);
			history.setFilenameOld(folder.getName());
			history.setPathOld(dao.computePathExtended(folderId));
			// The name alone: the type and the security reference stay as stored
			folder.setName(cleanName);
			evictor.evictFoldersOnRollback(List.of(folderId));
			dao.store(folder, history);
		}
	}

	/**
	 * Runs a search folder's rule
	 *
	 * @param folderId the search folder
	 * @param viewer who opens it
	 * @return the documents of the search folder's matter changed within the
	 *         window (by the viewer, if the rule says so) that match the rule
	 *         and the viewer can read, newest change first; none if the folder
	 *         is not a live search folder in a matter or the viewer can't read
	 *         it
	 * @throws PersistenceException error at data layer
	 */
	public List<Long> evaluate(long folderId, User viewer) throws PersistenceException {
		FolderDAO folders = FolderDAO.get();
		Folder folder = folders.findById(folderId);
		if (folder == null || folder.getType() != HealdanConstants.FOLDER_TYPE_SEARCH
				|| !folders.isReadAllowed(folderId, viewer.getId()))
			return List.of();
		Optional<SearchFolderRule> rule = rules.find(folderId);
		FolderPosition position = locator.locate(folder);
		if (rule.isEmpty() || !position.isInsideMatter())
			return List.of();

		DocumentDAO documents = DocumentDAO.get();
		List<Long> readable = new ArrayList<>();
		for (long docId : changed(rule.get(), subtree(position.workspaceId()), viewer.getId()))
			if (documents.isReadAllowed(docId, viewer.getId()))
				readable.add(docId);
		return readable;
	}

	/**
	 * @return the folder and every live folder below it, walked by parent id
	 */
	private List<Long> subtree(long rootId) {
		Set<Long> seen = new HashSet<>(List.of(rootId));
		List<Long> all = new ArrayList<>(seen);
		List<Long> level = List.of(rootId);
		while (!level.isEmpty()) {
			List<Long> next = new ArrayList<>();
			for (List<Long> chunk : chunks(level))
				for (Long child : jdbc.queryForList(
						"select ld_id from ld_folder where ld_deleted = 0 and ld_parentid in (:ids)",
						Map.of("ids", chunk), Long.class))
					if (seen.add(child))
						next.add(child);
			all.addAll(next);
			level = next;
		}
		return all;
	}

	/**
	 * @return the live documents in the folders with a change in the rule's
	 *         window that match the rule, newest change first (and the higher
	 *         id first between equals)
	 */
	private List<Long> changed(SearchFolderRule rule, List<Long> folderIds, long viewerId) {
		StringBuilder sql = new StringBuilder("""
				select h.ld_docid, max(h.ld_date)
				  from ld_history h
				  join ld_document d on d.ld_id = h.ld_docid
				 where h.ld_deleted = 0
				   and h.ld_event in (:events)
				   and h.ld_date >= :since
				   and d.ld_deleted = 0
				   and d.ld_folderid in (:folders)
				""");
		MapSqlParameterSource params = new MapSqlParameterSource("events", HealdanConstants.CHANGE_EVENT_KEYS)
				// From the start of the epoch at the earliest, whatever the window
				.addValue("since", new Timestamp(Math.max(0L, System.currentTimeMillis() - rule.days() * HealdanConstants.DAY)));
		if (rule.onlyMine()) {
			sql.append(" and h.ld_userid = :viewer");
			params.addValue("viewer", viewerId);
		}
		if (rule.kind() != SearchFolderRule.Kind.ALL) {
			sql.append(rule.kind() == SearchFolderRule.Kind.EMAILS ? " and lower(d.ld_type) in (:emails)"
					: " and (d.ld_type is null or lower(d.ld_type) not in (:emails))");
			params.addValue("emails", HealdanConstants.EMAIL_EXTENSIONS);
		}
		appendValue(sql, params, "docclass", HealdanConstants.ATTR_DOCCLASS, rule.docClass());
		appendValue(sql, params, "docsubclass", HealdanConstants.ATTR_DOCSUBCLASS, rule.docSubclass());
		sql.append(" group by h.ld_docid");

		// A document is in one folder, so each chunk finds other documents
		List<Change> changes = new ArrayList<>();
		for (List<Long> chunk : chunks(folderIds)) {
			params.addValue("folders", chunk);
			changes.addAll(jdbc.query(sql.toString(), params,
					(rs, i) -> new Change(rs.getLong(1), rs.getTimestamp(2).getTime())));
		}
		changes.sort(Comparator.comparingLong(Change::last).thenComparingLong(Change::docId).reversed());
		return changes.stream().map(Change::docId).toList();
	}

	/**
	 * Restricts the query to the documents whose attribute has the value, if
	 * there is a value
	 */
	private static void appendValue(StringBuilder sql, MapSqlParameterSource params, String param,
			String attribute, String value) {
		if (value == null)
			return;
		sql.append("""
				 and exists (select 1 from ld_document_ext x where x.ld_docid = d.ld_id
				                and x.ld_name = :%1$sname and x.ld_stringvalue = :%1$s)
				""".formatted(param));
		params.addValue(param + "name", attribute).addValue(param, value);
	}

	/**
	 * A document's latest change
	 *
	 * @param docId the document
	 * @param last when, in milliseconds
	 */
	private record Change(long docId, long last) {
	}

	private static List<List<Long>> chunks(List<Long> ids) {
		List<List<Long>> chunks = new ArrayList<>();
		for (int i = 0; i < ids.size(); i += CHUNK)
			chunks.add(ids.subList(i, Math.min(ids.size(), i + CHUNK)));
		return chunks;
	}

	/**
	 * @return the name, trimmed
	 * @throws HealdanRuleException if it is empty or too long for a folder
	 *         name, or holds a character beyond the Basic Multilingual Plane,
	 *         which would fail the insert on MariaDB (R33)
	 */
	private static String cleanName(String name) throws HealdanRuleException {
		String clean = StringUtils.strip(name);
		if (StringUtils.isEmpty(clean) || clean.length() > MAX_NAME_LENGTH)
			throw new HealdanRuleException(BAD_NAME.formatted(MAX_NAME_LENGTH));
		if (!BasicPlane.holds(clean))
			throw new HealdanRuleException(OUTSIDE_PLANE_NAME);
		return clean;
	}

	/**
	 * @return the rule with a blank Class or Subclass as none
	 * @throws HealdanRuleException if the window is shorter than a day, or
	 *         there is a Subclass without a Class
	 */
	private static SearchFolderRule cleanRule(SearchFolderRule rule) throws HealdanRuleException {
		Objects.requireNonNull(rule, "rule");
		if (rule.days() < 1)
			throw new HealdanRuleException(BAD_DAYS);
		String docClass = StringUtils.stripToNull(rule.docClass());
		String docSubclass = StringUtils.stripToNull(rule.docSubclass());
		if (docSubclass != null && docClass == null)
			throw new HealdanRuleException(SUBCLASS_WITHOUT_CLASS);
		return new SearchFolderRule(rule.folderId(), rule.days(), rule.onlyMine(), rule.kind(), docClass,
				docSubclass);
	}

	private static FolderHistory history(User actor) {
		FolderHistory history = new FolderHistory();
		history.setUser(actor);
		return history;
	}

	/**
	 * Runs the work as a search-folder operation, passing its refusals on as
	 * they are
	 */
	private static <T> T run(Callable<T> work) throws PersistenceException {
		try {
			return HealdanOperation.run(HealdanOperation.Kind.SEARCH_FOLDER, work);
		} catch (PersistenceException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new PersistenceException(e.getMessage(), e);
		}
	}
}
