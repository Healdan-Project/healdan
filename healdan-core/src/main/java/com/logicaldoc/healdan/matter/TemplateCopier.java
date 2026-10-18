package com.logicaldoc.healdan.matter;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.profile.LocalValueDAO;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Copies a workspace template, a folder directly under the Templates Store,
 * into a Store as a matter's workspace (spec 5.2): the folder tree, each
 * folder's own permissions, the Profile values and their local marks, and the
 * search folders with their rules. Templates hold no documents, and nothing
 * links a copy to its template afterwards, so changing the template never
 * touches the matters made from it.
 * <p>
 * <b>Permissions</b> (plan, R27). Inside a matter a folder inherits only from a
 * folder above it, never from outside its Store (R25, R28), so:
 * <ul>
 * <li>a folder with its own ACL gets a copy of its entries;</li>
 * <li>one that inherits from a folder above it inside the template inherits
 * from that folder's copy;</li>
 * <li>one that inherits what its parent inherits, through the same folder
 * outside the template, as CE leaves every folder created to inherit below a
 * template that inherits from the Templates Store, inherits from its parent's
 * copy: it gets what its parent's copy grants, as it got what its parent
 * granted;</li>
 * <li>any other reference (a sibling, the Templates Store, the root) becomes an
 * ACL of the folder's own holding what that reference grants. The workspace is
 * always one of these when the template inherits: the Templates Store is not
 * above it.</li>
 * </ul>
 * Each ACL is saved with the event {@code PERMISSION}, so the permission rules
 * check it: P2 on the workspace, P1 below it. A template that a matter's rules
 * would refuse, such as a folder that grants more than its parent, is refused.
 * <p>
 * It runs inside {@link HealdanOperation.Kind#MATTER_CREATE}, which lets it
 * create the workspace in the Store, its top-level folders and the search
 * folders, and inside the caller's transaction, which a refusal rolls back.
 * Outside a transaction it refuses to run: each folder store would commit on
 * its own, and a refusal halfway would leave part of a copy.
 */
@Component
public class TemplateCopier {

	private final LocalValueDAO locals;

	private final CacheEvictor evictor;

	private final JdbcTemplate jdbc;

	@Autowired
	public TemplateCopier(LocalValueDAO locals, CacheEvictor evictor, DataSource dataSource) {
		this.locals = locals;
		this.evictor = evictor;
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static TemplateCopier get() {
		return Context.get(TemplateCopier.class);
	}

	/**
	 * Copies the template, top-down
	 *
	 * @param template the template, directly under the Templates Store
	 * @param store the Store the workspace goes in
	 * @param workspaceName the name of the workspace, the copy of the template
	 * @param tx who copies; every folder store gets its own copy of it, so it
	 *        must name no event or path. Not null: the permission rules check
	 *        the ACLs saved with the event {@code PERMISSION}
	 * @return the copy of each template folder, by the template folder's id;
	 *         the template's own copy is the workspace
	 * @throws PersistenceException a refusal, or an error at data layer
	 * @throws org.springframework.transaction.IllegalTransactionStateException
	 *         outside a transaction
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public Map<Long, Long> copy(Folder template, Folder store, String workspaceName, FolderHistory tx)
			throws PersistenceException {
		Objects.requireNonNull(tx, "tx");
		Map<Long, Long> copies = new LinkedHashMap<>();
		Folder source = FolderDAO.get().findById(template.getId(), true);
		copyFolder(source, store, workspaceName, Set.of(), copies, tx);
		return copies;
	}

	/**
	 * @param source the template folder
	 * @param parent the copy of its parent, or the Store
	 * @param name the copy's name
	 * @param above the template folders above the source, inside the template
	 * @param copies the copies so far, by template folder
	 */
	private void copyFolder(Folder source, Folder parent, String name, Set<Long> above, Map<Long, Long> copies,
			FolderHistory tx) throws PersistenceException {
		FolderDAO dao = FolderDAO.get();
		Security security = security(source, above, copies);

		Folder vo = new Folder(name);
		vo.setTenantId(parent.getTenantId());
		vo.setType(source.getType() == HealdanConstants.FOLDER_TYPE_SEARCH ? HealdanConstants.FOLDER_TYPE_SEARCH
				: Folder.TYPE_DEFAULT);
		vo.setDescription(source.getDescription());
		vo.setTemplate(source.getTemplate());
		vo.setTemplateLocked(source.isTemplateLocked());
		// Copies: CE puts the value object's attributes in the new folder, and the template's must not change
		source.getAttributes().forEach((attribute, value) -> vo.getAttributes().put(attribute, new Attribute(value)));

		// Inheriting, CE points the copy at its parent's copy, or where that one points
		Folder copy = dao.create(parent, vo, security.inherits(), new FolderHistory(tx));
		// The cache isn't transactional: a refusal further on mustn't leave the copy cached
		evictor.evictFoldersOnRollback(List.of(copy.getId()));
		copies.put(source.getId(), copy.getId());

		if (!security.inherits()) {
			setOwnAcl(copy, AclMath.effective(source.getId()));
			dao.store(copy, permissionHistory(tx));
		} else if (security.from() != null && !security.from().equals(copy.getSecurityRef())) {
			copy.setSecurityRef(security.from());
			dao.store(copy, permissionHistory(tx));
		}

		// After the stores above, which recalculate the marks a create leaves
		locals.copy(LocalValueDAO.FOLDER, source.getId(), copy.getId());
		if (copy.getType() == HealdanConstants.FOLDER_TYPE_SEARCH)
			copySearchFolderRule(source.getId(), copy.getId());

		Set<Long> below = new HashSet<>(above);
		below.add(source.getId());
		for (Folder child : dao.findChildren(source.getId(), null)) {
			if (child.getFoldRef() != null || child.getType() == Folder.TYPE_ALIAS)
				continue;
			Folder initialized = dao.findById(child.getId(), true);
			copyFolder(initialized, copy, initialized.getName(), below, copies, tx);
		}
	}

	/**
	 * How a copy gets its permissions
	 *
	 * @param inherits false if it gets an ACL of its own
	 * @param from the copy it inherits from; null if it inherits what its
	 *        parent's copy uses
	 */
	private record Security(boolean inherits, Long from) {

		static final Security OWN = new Security(false, null);

		static final Security PARENT = new Security(true, null);
	}

	private static Security security(Folder source, Set<Long> above, Map<Long, Long> copies) {
		Long ref = source.getSecurityRef();
		if (ref == null || ref == source.getId())
			return Security.OWN;
		if (above.contains(ref))
			return new Security(true, copies.get(ref));
		// What the parent inherits, through the same folder outside the template
		if (above.contains(source.getParentId())
				&& AclMath.source(source.getId()) == AclMath.source(source.getParentId()))
			return Security.PARENT;
		return Security.OWN;
	}

	/**
	 * Gives a folder about to be stored an ACL of its own
	 *
	 * @param folder the folder
	 * @param acl what it grants, by group
	 */
	static void setOwnAcl(Folder folder, Map<Long, Set<Permission>> acl) {
		folder.setSecurityRef(null);
		folder.getAccessControlList().clear();
		acl.forEach((group, flags) -> {
			FolderAccessControlEntry entry = new FolderAccessControlEntry(group);
			entry.grantPermissions(flags);
			folder.addAccessControlEntry(entry);
		});
	}

	/*
	 * Task 17 replaces this with SearchFolderRuleDAO.copy
	 */
	private void copySearchFolderRule(long fromId, long toId) {
		jdbc.update("""
				insert into hd_searchfolder (hd_folderid, hd_days, hd_onlymine, hd_kind, hd_docclass, hd_docsubclass)
				select ?, hd_days, hd_onlymine, hd_kind, hd_docclass, hd_docsubclass from hd_searchfolder where hd_folderid = ?
				""", toId, fromId);
	}

	private static FolderHistory permissionHistory(FolderHistory tx) {
		FolderHistory history = new FolderHistory(tx);
		history.setEvent(FolderEvent.PERMISSION);
		return history;
	}
}
