// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.GroupNames;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.TemplateCopier;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.security.PermissionRules;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * The workspace templates (Admin app spec section 8.5): the folders directly in the Templates Store that a matter can
 * be made from ({@link MatterService#templates}), New Template, empty or a copy of another, rename, and what New
 * Matter would refuse in a matter made from one ({@link #problems}), which nothing checks as a template is edited
 * (Admin app plan, P2-R7). Changing a template never changes the matters made from it, which are copies.
 * <p>
 * A template's name is unique among the Templates Store's folders, ignoring case. A create or a rename checks it,
 * and the caller holds a lock on the Templates Store meanwhile (the API's folder locks), so two at once can't both
 * pass; LogicalDOC's folder table has no unique key on names. Each change is recorded in the administration record
 * inside its transaction, a copy too, which is one. Deleting a template goes to the Trash through the API's folder
 * changes, as a folder's delete does.
 */
@Service
public class TemplateAdmin {

	static final String NO_TEMPLATES_STORE = "There's no Templates Store.";

	static final String NO_TEMPLATE = "There's no template %d.";

	static final String NO_COPY = "There's no template %d to copy.";

	static final String TAKEN = "Another template is called %s.";

	/** A folder of the template wider than the folder it's in: its path in the template, then the excess */
	static final String WIDER = "%s grants more than the folder it's in (%s).";

	/** A group, or a person, who could read the matter and can't read a Store, with the Store's name */
	static final String UNREADABLE = "%s can't read the Store %s.";

	/** What the administration record calls a template */
	private static final String TEMPLATE = "template";

	private final MatterService matters;

	private final TemplateCopier copier;

	private final PermissionRules permissionRules;

	private final FilingRules filingRules;

	private final HealdanSettings settings;

	private final StoredRows rows;

	private final CacheEvictor evictor;

	private final AdminEvents events;

	private final NamedParameterJdbcTemplate jdbc;

	@Autowired
	public TemplateAdmin(MatterService matters, TemplateCopier copier, PermissionRules permissionRules,
			FilingRules filingRules, HealdanSettings settings, StoredRows rows, CacheEvictor evictor,
			AdminEvents events, DataSource dataSource) {
		this.matters = matters;
		this.copier = copier;
		this.permissionRules = permissionRules;
		this.filingRules = filingRules;
		this.settings = settings;
		this.rows = rows;
		this.evictor = evictor;
		this.events = events;
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static TemplateAdmin get() {
		return Context.get(TemplateAdmin.class);
	}

	/**
	 * @return every template, by name ignoring case, each with how many folders it holds and how many live matters
	 *         were made from it
	 * @throws PersistenceException error at data layer
	 */
	public List<TemplateInfo> list() throws PersistenceException {
		List<Folder> templates = new ArrayList<>(matters.templates());
		templates.sort(Comparator.comparing(Folder::getName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(Folder::getName).thenComparingLong(Folder::getId));
		Map<Long, Long> made = madeFrom(templates.stream().map(Folder::getId).toList());
		List<TemplateInfo> list = new ArrayList<>();
		for (Folder template : templates)
			list.add(info(template, made));
		return list;
	}

	/**
	 * @param id a folder
	 * @return the template, as {@link #list} gives it; null if the folder isn't a template
	 * @throws PersistenceException error at data layer
	 */
	public TemplateInfo info(long id) throws PersistenceException {
		Folder template = matters.template(id);
		return template != null ? info(template, madeFrom(List.of(id))) : null;
	}

	private TemplateInfo info(Folder template, Map<Long, Long> made) {
		long folders = rows.descendants(template.getId()).stream().filter(row -> !row.alias()).count();
		return new TemplateInfo(template.getId(), template.getName(), folders, made.getOrDefault(template.getId(), 0L));
	}

	/**
	 * @return how many live matters were made from each template: the matter, its client and its folder not deleted
	 */
	private Map<Long, Long> madeFrom(List<Long> templateIds) {
		Map<Long, Long> made = new HashMap<>();
		if (templateIds.isEmpty())
			return made;
		jdbc.query("""
				select m.hd_templateid, count(*) from hd_matter m
				  join hd_client c on c.ld_id = m.hd_clientid and c.ld_deleted = 0
				  join ld_folder f on f.ld_id = m.hd_folderid and f.ld_deleted = 0
				 where m.ld_deleted = 0 and m.hd_templateid in (:ids)
				 group by m.hd_templateid""", new MapSqlParameterSource("ids", templateIds),
				rs -> {
					made.put(rs.getLong(1), rs.getLong(2));
				});
		return made;
	}

	/**
	 * Creates a template in the Templates Store: empty, following the Templates Store's permissions as a folder made
	 * there does, or a copy of another, with its folders, their permissions and values, and its search folders with
	 * their rules, as New Matter copies one ({@link TemplateCopier}). A copy's top folder follows what the other's
	 * follows, or has its own permissions as the other's has.
	 *
	 * @param name its name; it is trimmed, and the caller has checked it as a folder name
	 * @param copyOf the template to copy; null for an empty one
	 * @param actor an administrator
	 * @param sessionId the caller's session, for the history; null for none
	 * @return the new template's id
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence; or an error at data
	 *         layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public long create(String name, Long copyOf, User actor, String sessionId) throws PersistenceException {
		requireAdmin(actor);
		Folder templates = templatesStore();
		String clean = StringUtils.strip(name);
		refuseTaken(templates, 0L, clean);
		Folder source = null;
		if (copyOf != null) {
			source = matters.template(copyOf);
			if (source == null)
				throw new HealdanRuleException(NO_COPY.formatted(copyOf));
		}

		FolderDAO folders = FolderDAO.get();
		long id;
		if (source == null) {
			Folder vo = new Folder(clean);
			vo.setType(Folder.TYPE_DEFAULT);
			vo.setTenantId(templates.getTenantId());
			Folder created = folders.create(templates, vo, true, history(actor, sessionId, FolderEvent.CREATED));
			id = created.getId();
			evictor.evictFoldersOnRollback(List.of(id));
		} else {
			id = copy(source, templates, clean, actor, sessionId);
		}
		// LogicalDOC gives a folder a name of its own making when another took it meanwhile
		if (!clean.equals(folders.findById(id).getName()))
			throw new HealdanRuleException(TAKEN.formatted(clean));

		Map<String, Change> changes = new HashMap<>();
		changes.put("name", new Change(null, clean));
		if (source != null)
			changes.put("copyOf", new Change(null, source.getName()));
		events.record(new AdminEvent(AdminArea.MATTERS, "template.created", TEMPLATE, id, clean, changes), actor);
		return id;
	}

	/**
	 * Copies a template into the Templates Store, in the caller's transaction. The copier creates search folders, which
	 * only a search-folder operation may (the filing rules), so it runs as one.
	 *
	 * @return the copy's id
	 */
	private long copy(Folder source, Folder templates, String name, User actor, String sessionId)
			throws PersistenceException {
		FolderHistory tx = history(actor, sessionId, null);
		Map<Long, Long> copies;
		try {
			copies = HealdanOperation.run(HealdanOperation.Kind.SEARCH_FOLDER,
					() -> copier.copy(source, templates, name, tx));
		} catch (PersistenceException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new PersistenceException(e.getMessage(), e);
		}
		long id = copies.get(source.getId());
		// The copier gives the top folder permissions of its own, as a matter's folder has; a template's that follows
		// the Templates Store (or the root) is copied following it, with the folders that follow the copy
		Folder stored = FolderDAO.get().findById(source.getId());
		Long ref = stored.getSecurityRef();
		if (ref != null && ref != stored.getId())
			FolderDAO.get().updateSecurityRef(id, ref, history(actor, sessionId, null));
		return id;
	}

	/**
	 * Renames a template's folder. Its own name changes nothing; changing only the case of it renames.
	 *
	 * @param id the template
	 * @param name its new name; it is trimmed, and the caller has checked it as a folder name
	 * @param actor an administrator
	 * @param sessionId the caller's session, for the history; null for none
	 * @return false if it has that name already
	 * @throws PersistenceException a refusal, a {@link HealdanRuleException} with its sentence; or an error at data
	 *         layer
	 */
	@Transactional(rollbackFor = Exception.class)
	public boolean rename(long id, String name, User actor, String sessionId) throws PersistenceException {
		requireAdmin(actor);
		Folder template = matters.template(id);
		if (template == null)
			throw new HealdanRuleException(NO_TEMPLATE.formatted(id));
		String clean = StringUtils.strip(name);
		if (clean.equals(template.getName()))
			return false;
		refuseTaken(templatesStore(), id, clean);

		FolderDAO folders = FolderDAO.get();
		Folder current = folders.findById(id, true);
		String old = current.getName();
		FolderHistory history = history(actor, sessionId, FolderEvent.RENAMED);
		history.setFilenameOld(old);
		history.setPathOld(folders.computePathExtended(id));
		current.setName(clean);
		evictor.evictFoldersOnRollback(List.of(id));
		folders.store(current, history);
		if (!clean.equals(current.getName()))
			throw new HealdanRuleException(TAKEN.formatted(clean));

		events.record(new AdminEvent(AdminArea.MATTERS, "template.renamed", TEMPLATE, id, clean,
				Map.of("name", new Change(old, clean))), actor);
		return true;
	}

	/**
	 * What New Matter would refuse in a matter made from the template, with its own rules, as warnings:
	 * <ul>
	 * <li>{@link TemplateProblem#WIDER_THAN_PARENT}, once for each folder or search folder that grants a group
	 * something the folder it's in doesn't (P1), in the tree's order ({@link WorkspaceAdmin#tree}): a folder with
	 * permissions of its own, or one that follows a folder above its parent, as one made while its parent followed
	 * keeps following after its parent gets its own;</li>
	 * <li>{@link TemplateProblem#STORE_UNREADABLE}, for each Store, once for each group that could read a matter made
	 * from the template and can't read that Store (P2), by Store name and then group name, ignoring case. The
	 * matter's owner isn't known, so their own group isn't counted.</li>
	 * </ul>
	 *
	 * @param id a template
	 * @return its problems, none for a clean template; null if the folder isn't a template
	 * @throws PersistenceException error at data layer
	 */
	public List<TemplateProblem> problems(long id) throws PersistenceException {
		WorkspaceNode tree = WorkspaceAdmin.get().tree(id);
		if (tree == null || matters.template(id) == null)
			return null;
		List<TemplateProblem> problems = new ArrayList<>();
		wider(tree, "", problems);

		Map<Long, Set<Permission>> acl = AclMath.effective(id);
		List<Folder> stores = new ArrayList<>(matters.stores());
		stores.sort(Comparator.comparing(Folder::getName, String.CASE_INSENSITIVE_ORDER)
				.thenComparingLong(Folder::getId));
		for (Folder store : stores) {
			List<Long> groups = new ArrayList<>(permissionRules.p2Failures(acl, store.getId(), null));
			Map<Long, String> names = new HashMap<>();
			groups.forEach(group -> names.put(group, GroupNames.of(group)));
			groups.sort(Comparator.comparing(names::get, String.CASE_INSENSITIVE_ORDER));
			for (long group : groups)
				problems.add(new TemplateProblem(TemplateProblem.STORE_UNREADABLE,
						UNREADABLE.formatted(names.get(group), store.getName()), null, store.getId(), group,
						names.get(group)));
		}
		return problems;
	}

	/**
	 * {@link TemplateProblem#WIDER_THAN_PARENT} for each folder below this one, top down, each before its own
	 * folders, as the copy in a matter would be checked against the copy of its parent
	 *
	 * @param node a folder of the template, the template itself first
	 * @param path the folder's path in the template, as {@code Documents/Invoices}; empty for the template itself
	 */
	private static void wider(WorkspaceNode node, String path, List<TemplateProblem> problems) {
		Map<Long, Set<Permission>> parentAcl = AclMath.effective(node.id());
		for (WorkspaceNode child : node.children()) {
			String childPath = path.isEmpty() ? child.name() : path + "/" + child.name();
			Map<Long, Set<Permission>> acl = AclMath.effective(child.id());
			String excess = AclMath.firstExcess(acl, parentAcl);
			if (excess != null) {
				long group = AclMath.firstExcessGroup(acl, parentAcl);
				problems.add(new TemplateProblem(TemplateProblem.WIDER_THAN_PARENT, WIDER.formatted(childPath, excess),
						child.id(), null, group, GroupNames.of(group)));
			}
			wider(child, childPath, problems);
		}
	}

	/**
	 * @return the Templates Store
	 * @throws HealdanRuleException if there's none
	 */
	private Folder templatesStore() throws PersistenceException {
		Long id = settings.templatesStoreId();
		Folder templates = id != null ? FolderDAO.get().findById(id) : null;
		if (templates == null || templates.getDeleted() != 0)
			throw new HealdanRuleException(NO_TEMPLATES_STORE);
		return templates;
	}

	/**
	 * Refuses a name another live folder in the Templates Store has, ignoring case, whatever it is: LogicalDOC would
	 * give a folder named as another a name of its own making
	 */
	private void refuseTaken(Folder templates, long exceptId, String name) throws HealdanRuleException {
		for (FolderRow row : rows.children(templates.getId()))
			if (row.id() != exceptId && name.equalsIgnoreCase(row.name()))
				throw new HealdanRuleException(TAKEN.formatted(name));
	}

	private void requireAdmin(User actor) throws PersistenceException {
		if (actor == null || !filingRules.isAdmin(actor))
			throw new HealdanRuleException(WorkspaceAdmin.ONLY_ADMINS);
	}

	private static FolderHistory history(User actor, String sessionId, FolderEvent event) {
		FolderHistory history = new FolderHistory();
		history.setUser(actor);
		if (sessionId != null)
			history.setSessionId(sessionId);
		if (event != null)
			history.setEvent(event);
		return history;
	}
}
