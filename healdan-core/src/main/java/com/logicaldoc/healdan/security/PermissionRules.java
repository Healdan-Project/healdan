package com.logicaldoc.healdan.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * The permission rules (spec §7.3).
 * <ul>
 * <li><b>P1</b>: inside a matter, a folder grants no group anything its parent
 * doesn't grant it, flag by flag.</li>
 * <li><b>P2</b>: every group that can read a matter's workspace can read its
 * Store; the owner's own group passes if the owner can read the Store.</li>
 * <li><b>Narrowing</b>: when a folder's permissions narrow, the folders below
 * it narrow with it, so that P1 keeps holding.</li>
 * <li><b>Apply to subfolders</b>: CE's {@code applySecurityToTree} replaces
 * every ACL below a folder, which can re-open a restricted folder. Inside the
 * Stores and the templates only an admin, or someone who manages every folder
 * below with its own ACL, may do it.</li>
 * </ul>
 * A user's explicit permission change that breaks P1 or P2 is refused. A move,
 * a copy or a restore that would leave a folder granting more than its new
 * parent narrows that folder instead, as narrowing a parent does; so does any
 * other store of a folder that doesn't fit its parent. Documents aren't bound:
 * a document may grant what its folder doesn't (spec §7.3, owner).
 */
@Component
public class PermissionRules {

	static final String P1 = "A folder can't grant more than its parent (%s).";

	static final String P2 = "%s can read this %s but not its Store.";

	static final String APPLY_TO_TREE = "You can't apply these permissions to subfolders that you can't manage.";

	static final String INHERIT_FROM_ABOVE = "A folder can only inherit permissions from a folder above it.";

	/** Narrowing passes over a tree; one is enough unless folders inherit from folders below them */
	private static final int MAX_PASSES = 5;

	/** Large id lists are queried in chunks */
	private static final int CHUNK = 500;

	/** Key of the Stores the current transaction narrows before it commits */
	private static final String PENDING_STORES = PermissionRules.class.getName() + ".pendingStores";

	/** Set while the current transaction copies a folder tree */
	private static final ThreadLocal<Boolean> COPYING = new ThreadLocal<>();

	/**
	 * What a folder becomes to stay within its parent
	 *
	 * @param securityRef its new security reference, null for its own ACL
	 * @param rows its own rows, empty if it inherits
	 */
	record Outcome(Long securityRef, Map<Long, Set<Permission>> rows) {
	}

	/**
	 * A folder of a tree being narrowed
	 */
	private static final class Node {

		final long id;

		final long parentId;

		final boolean alias;

		final Long originalRef;

		final Map<Long, Set<Permission>> originalRows;

		Long ref;

		Map<Long, Set<Permission>> rows;

		Node(long id, long parentId, Long ref, boolean alias) {
			this.id = id;
			this.parentId = parentId;
			this.alias = alias;
			this.originalRef = ref;
			this.ref = ref;
			this.originalRows = new TreeMap<>();
			this.rows = originalRows;
		}

		boolean ownAcl() {
			return ref == null || ref == id;
		}
	}

	private final MatterLocator locator;

	private final HealdanSettings settings;

	private final FilingRules filingRules;

	private final StoredRows storedRows;

	private final CacheEvictor evictor;

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	@Autowired
	public PermissionRules(MatterLocator locator, HealdanSettings settings, FilingRules filingRules,
			StoredRows storedRows, CacheEvictor evictor, DataSource dataSource) {
		this.locator = locator;
		this.settings = settings;
		this.filingRules = filingRules;
		this.storedRows = storedRows;
		this.evictor = evictor;
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
	}

	/**
	 * @return the bean
	 */
	public static PermissionRules get() {
		return Context.get(PermissionRules.class);
	}

	/**
	 * Marks the current transaction as copying a folder tree, until it ends.
	 * CE's copy creates each folder and then gives it its permissions in
	 * separate stores, with documents created in between, so a refusal halfway
	 * would leave files behind (plan, D3): while it copies, folders that don't
	 * fit are narrowed instead.
	 */
	public static void copying() {
		if (!TransactionSynchronizationManager.isSynchronizationActive() || isCopying())
			return;
		COPYING.set(Boolean.TRUE);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				COPYING.remove();
			}
		});
	}

	/**
	 * @return true if the current transaction is copying a folder tree
	 */
	public static boolean isCopying() {
		return Boolean.TRUE.equals(COPYING.get());
	}

	/**
	 * P1: the folder, as it is about to be stored, grants no more than its
	 * parent
	 *
	 * @param folder the folder
	 * @throws HealdanRuleException if it grants more
	 */
	public void checkP1(Folder folder) throws HealdanRuleException {
		String excess = AclMath.firstExcess(prospective(folder), AclMath.effective(folder.getParentId()));
		if (excess != null)
			throw refuse(P1.formatted(excess), folder.getId(), folder.getParentId());
	}

	/**
	 * @param folder a folder about to be stored
	 * @return the ACL it will grant: its security reference's, or its own
	 *         entries
	 */
	public Map<Long, Set<Permission>> prospective(Folder folder) {
		Long ref = folder.getSecurityRef();
		if (ref != null && ref != folder.getId())
			return AclMath.effective(ref);
		return AclMath.ofEntries(folder.getAccessControlList());
	}

	/**
	 * P2, on the workspace's ACL as it is stored
	 *
	 * @param workspaceId the matter's workspace
	 * @param storeId the Store it is, or goes, in
	 * @param ownerUserId the matter's owner, null if there is none yet
	 * @throws HealdanRuleException if a group can read the workspace and not
	 *         the Store
	 */
	public void checkP2(long workspaceId, long storeId, Long ownerUserId) throws HealdanRuleException {
		checkP2(AclMath.effective(workspaceId), storeId, ownerUserId, workspaceId);
	}

	/**
	 * P2, on a workspace's ACL as it is about to be stored
	 *
	 * @param workspaceAcl what the workspace grants
	 * @param storeId the Store it is, or goes, in
	 * @param ownerUserId the matter's owner, null if there is none yet
	 * @param workspaceId the workspace, 0 if it is new
	 * @throws HealdanRuleException if a group can read the workspace and not
	 *         the Store
	 */
	public void checkP2(Map<Long, Set<Permission>> workspaceAcl, long storeId, Long ownerUserId, long workspaceId)
			throws HealdanRuleException {
		Map<Long, Set<Permission>> storeAcl = AclMath.effective(storeId);
		Long ownersGroup = ownerUserId != null ? personalGroup(ownerUserId) : null;
		for (Map.Entry<Long, Set<Permission>> entry : new TreeMap<>(workspaceAcl).entrySet()) {
			long group = entry.getKey();
			if (!entry.getValue().contains(Permission.READ)
					|| storeAcl.getOrDefault(group, Set.of()).contains(Permission.READ))
				continue;
			if (ownersGroup != null && ownersGroup == group && canRead(ownerUserId, storeAcl))
				continue;
			throw refuse(P2.formatted(capitalize(AclMath.describe(group)), label()), workspaceId, storeId);
		}
	}

	/**
	 * P2 for a matter's workspace restored into a Store, before CE restores it
	 *
	 * @param folderId the deleted folder
	 * @param parentId where it is restored
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkRestore(long folderId, long parentId) throws PersistenceException {
		FolderRow row = storedRows.folder(folderId);
		if (row == null || row.alias() || locator.locate(row.parentId()).kind() != Kind.STORE)
			return;
		Folder target = FolderDAO.get().findById(parentId);
		if (target != null && target.getFoldRef() != null)
			target = FolderDAO.get().findById(target.getFoldRef());
		// Anywhere else the structure rules refuse it
		if (target == null || locator.locate(target).kind() != Kind.STORE)
			return;
		Matter matter = MatterDAO.get().findByFolderId(folderId);
		checkP2(AclMath.effective(folderId), target.getId(), matter != null ? matter.getOwnerId() : null, folderId);
	}

	/**
	 * Narrows the folders below a folder so that each grants no more than its
	 * parent: in a folder with its own ACL, the rows of groups the parent
	 * lacks are deleted and the flags the parent lacks are cleared. A folder
	 * that inherits from a folder above its parent, as CE leaves one created
	 * while its parent inherited, inherits from its parent again. Inside a
	 * matter a folder inherits only from a folder above it (plan, R25), so
	 * one that inherits from anywhere else, as a move, copy or restore leaves
	 * it, gets an ACL of its own with what that folder and its parent both
	 * grant (or inherits from its parent, when that is the same). The tree is
	 * walked top-down; the writes
	 * are JDBC, with no history, version or listener, and the changed folders
	 * are evicted from the second-level cache now and when the transaction
	 * ends. Below a Store, the matters' workspaces are left to P2; the folders
	 * inside them are narrowed.
	 *
	 * @param folderId the folder
	 * @throws PersistenceException error at data layer
	 */
	public void narrowDescendants(long folderId) throws PersistenceException {
		normalize(folderId, false, locator.locate(folderId).kind() == Kind.STORE);
	}

	/**
	 * Narrows the matters below a Store, as {@link #narrowDescendants} does,
	 * just before the current transaction commits: after the JDBC CE runs
	 * once its store is over, such as {@code updateSecurityRef}'s update of the
	 * folders that inherited from the Store, or {@code applySecurityToTree}.
	 * Outside a transaction it narrows at once.
	 *
	 * @param storeId the Store
	 * @throws PersistenceException error at data layer
	 */
	public void narrowBeforeCommit(long storeId) throws PersistenceException {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			narrowDescendants(storeId);
			return;
		}
		@SuppressWarnings("unchecked")
		Set<Long> pending = (Set<Long>) TransactionSynchronizationManager.getResource(PENDING_STORES);
		if (pending == null) {
			Set<Long> stores = new LinkedHashSet<>();
			TransactionSynchronizationManager.bindResource(PENDING_STORES, stores);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void beforeCommit(boolean readOnly) {
					try {
						for (Long store : List.copyOf(stores))
							narrowDescendants(store);
					} catch (PersistenceException e) {
						throw new IllegalStateException(e.getMessage(), e);
					}
				}

				@Override
				public void afterCompletion(int status) {
					TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_STORES);
				}
			});
			pending = stores;
		}
		pending.add(storeId);
	}

	/**
	 * "Apply to subfolders" from the root or a Store points the folders below
	 * at the root's or the Store's source, which may be the root (R28): the
	 * matters below are narrowed once CE's JDBC is done
	 *
	 * @param rootId where it is applied from
	 * @throws PersistenceException error at data layer
	 */
	public void afterApplySecurityToTree(long rootId) throws PersistenceException {
		Kind kind = locator.locate(rootId).kind();
		if (kind == Kind.STORE) {
			narrowBeforeCommit(rootId);
		} else if (kind == Kind.ROOT) {
			for (FolderRow row : storedRows.children(rootId))
				if (locator.locate(row.id()).kind() == Kind.STORE)
					narrowBeforeCommit(row.id());
		}
	}

	/**
	 * Narrows a folder inside a matter to its parent, and the folders below it
	 * to theirs, as {@link #narrowDescendants} does: for a folder restored
	 * with JDBC. A matter's workspace isn't narrowed (P2 checked it before the
	 * restore), but a reference to anything but its Store is replaced, as
	 * {@link #fitWorkspace} does on a move.
	 *
	 * @param folderId the folder
	 * @throws PersistenceException error at data layer
	 */
	public void narrowTree(long folderId) throws PersistenceException {
		Kind kind = locator.locate(folderId).kind();
		if (kind == Kind.IN_MATTER || kind == Kind.MATTER_WORKSPACE)
			normalize(folderId, true, false);
	}

	/**
	 * Inside a matter a folder inherits permissions only from a folder above it
	 * (plan, R25), up to its Store and never from the root (R28): a reference
	 * to any other folder would follow that folder's changes, which no
	 * narrowing below this folder's parent sees
	 *
	 * @param folder a folder about to be stored, with its new parent
	 * @throws HealdanRuleException if it inherits from a folder that isn't
	 *         above it
	 */
	public void checkInheritsFromAbove(Folder folder) throws HealdanRuleException {
		if (!inheritsFromAbove(folder))
			throw refuse(INHERIT_FROM_ABOVE, folder.getId(), folder.getParentId());
	}

	/**
	 * @param folder a folder about to be stored, with its new parent
	 * @return true if it has its own ACL, or inherits from a folder above it
	 */
	private boolean inheritsFromAbove(Folder folder) {
		Long ref = folder.getSecurityRef();
		return ref == null || ref == folder.getId() || ancestors(folder.getParentId()).contains(ref);
	}

	/**
	 * Narrows a folder about to be stored to its parent, in the object itself,
	 * as {@link #narrowDescendants} narrows the folders below
	 *
	 * @param folder the folder, with its new parent
	 * @return true if it changed
	 */
	public boolean fit(Folder folder) {
		long parentId = folder.getParentId();
		Long ref = folder.getSecurityRef();
		boolean own = ref == null || ref == folder.getId();
		Outcome outcome = decide(folder.getId(), ref,
				own ? AclMath.entriesWithEmpty(folder.getAccessControlList()) : Map.of(),
				own ? Map.of() : AclMath.effective(ref), ancestors(parentId), AclMath.effective(parentId),
				AclMath.source(parentId));
		if (outcome == null)
			return false;
		apply(folder, outcome);
		return true;
	}

	/**
	 * A matter's workspace that inherits from anything but its Store, as a
	 * move to another Store or CE's create under a Store that inherits from
	 * the root leaves it, gets an ACL of its own with what that folder grants,
	 * or inherits from its Store when that grants the same and has an ACL of
	 * its own (plan, R25, R28). Its permissions aren't narrowed: P2 checks
	 * them.
	 *
	 * @param workspace the workspace about to be stored, with its new parent
	 * @return true if it changed
	 */
	public boolean fitWorkspace(Folder workspace) {
		Long ref = workspace.getSecurityRef();
		long storeId = workspace.getParentId();
		Outcome outcome = decideWorkspace(workspace.getId(), ref,
				ref != null && ref != workspace.getId() ? AclMath.effective(ref) : Map.of(), ancestors(storeId),
				AclMath.effective(storeId), AclMath.source(storeId));
		if (outcome == null)
			return false;
		apply(workspace, outcome);
		return true;
	}

	/**
	 * What a matter's workspace becomes so that it inherits only from its
	 * Store
	 *
	 * @param id the workspace
	 * @param ref its security reference, null if it has its own ACL
	 * @param refAcl what its reference grants (if it inherits)
	 * @param allowed what it may inherit from: its Store
	 * @param storeAcl what its Store grants
	 * @param storeSource the folder whose ACL its Store uses
	 * @return what it becomes, null if it may stay as it is
	 */
	static Outcome decideWorkspace(long id, Long ref, Map<Long, Set<Permission>> refAcl, Set<Long> allowed,
			Map<Long, Set<Permission>> storeAcl, long storeSource) {
		if (ref == null || ref == id || allowed.contains(ref))
			return null;
		if (refAcl.equals(storeAcl) && allowed.contains(storeSource))
			return new Outcome(storeSource, Map.of());
		return new Outcome(null, AclMath.granted(refAcl));
	}

	private static void apply(Folder folder, Outcome outcome) {
		folder.setSecurityRef(outcome.securityRef());
		folder.getAccessControlList().clear();
		outcome.rows().forEach((group, flags) -> {
			FolderAccessControlEntry entry = new FolderAccessControlEntry(group);
			entry.grantPermissions(flags);
			folder.addAccessControlEntry(entry);
		});
	}

	/**
	 * "Apply to subfolders" replaces the ACL of every folder below the root
	 * with the root's, deleted ones included (a restore would bring them back
	 * re-opened). Inside the Stores and the templates, a non-admin needs
	 * SECURITY on every folder with its own ACL it would replace; from the
	 * root, that means every such folder in every Store and in the Templates
	 * Store, the Stores included.
	 *
	 * @param rootId the root
	 * @param user who applies it
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void checkApplyToTree(long rootId, User user) throws PersistenceException {
		FolderPosition position = locator.locate(rootId);
		Kind kind = position.kind();
		if (!position.isUnderAStore() && kind != Kind.TEMPLATES_STORE && kind != Kind.IN_TEMPLATES
				&& kind != Kind.ROOT)
			return;
		if (filingRules.isAdmin(user))
			return;

		List<FolderRow> level = storedRows.children(List.of(rootId), true);
		// Below the root, only the Stores and the Templates Store are Healdan's
		if (kind == Kind.ROOT)
			level = level.stream()
					.filter(row -> row.type() == Folder.TYPE_WORKSPACE && row.id() != Folder.DEFAULTWORKSPACEID)
					.toList();
		Set<Long> seen = new HashSet<>(List.of(rootId));
		while (!level.isEmpty()) {
			List<Long> ids = new ArrayList<>();
			for (FolderRow row : level) {
				if (!seen.add(row.id()))
					continue;
				ids.add(row.id());
				boolean own = row.securityRef() == null || row.securityRef() == row.id();
				if (!row.alias() && own && (user == null || !hasSecurity(row.id(), user.getId())))
					throw refuse(APPLY_TO_TREE, rootId);
			}
			level = ids.isEmpty() ? List.of() : storedRows.children(ids, true);
		}
	}

	/**
	 * What a folder must become to stay within its parent
	 *
	 * @param id the folder
	 * @param ref its security reference, null if it has its own ACL
	 * @param own its own rows, groups granted nothing included (if it has its
	 *        own ACL)
	 * @param refAcl what its security reference grants (if it inherits)
	 * @param ancestors the folders above it: its parent and up
	 * @param parentAcl what its parent grants
	 * @param parentSource the folder whose ACL the parent uses
	 * @return what the folder becomes, null if it may stay as it is
	 */
	static Outcome decide(long id, Long ref, Map<Long, Set<Permission>> own, Map<Long, Set<Permission>> refAcl,
			Set<Long> ancestors, Map<Long, Set<Permission>> parentAcl, long parentSource) {
		if (ref == null || ref == id) {
			Map<Long, Set<Permission>> narrowed = new TreeMap<>();
			own.forEach((group, flags) -> {
				Set<Permission> allowed = parentAcl.get(group);
				if (allowed != null) {
					Set<Permission> kept = EnumSet.noneOf(Permission.class);
					kept.addAll(flags);
					kept.retainAll(allowed);
					narrowed.put(group, kept);
				}
			});
			return narrowed.equals(own) ? null : new Outcome(null, narrowed);
		}

		if (ancestors.contains(ref)) {
			if (ref == parentSource)
				return null;
			// CE gave it its parent's reference when it was created: it follows its parent
			return inheritParent(ancestors, parentAcl, parentSource);
		}

		// Not above it (R25): what it grants now, within its parent, becomes its own
		Map<Long, Set<Permission>> both = AclMath.intersect(refAcl, parentAcl);
		return both.equals(parentAcl) ? inheritParent(ancestors, parentAcl, parentSource) : new Outcome(null, both);
	}

	/**
	 * @return the folder inherits what its parent grants: from the parent's
	 *         source if that is above it, otherwise as an ACL of its own
	 */
	private static Outcome inheritParent(Set<Long> ancestors, Map<Long, Set<Permission>> parentAcl,
			long parentSource) {
		if (ancestors.contains(parentSource))
			return new Outcome(parentSource, Map.of());
		return new Outcome(null, AclMath.granted(parentAcl));
	}

	/**
	 * @param rootId the top of the tree
	 * @param includeRoot true to narrow the top to its parent too
	 * @param storeChildren true if the top is a Store, whose children are the
	 *        matters' workspaces: those aren't narrowed (P2 is theirs), but a
	 *        reference to anything but the Store is replaced
	 */
	private void normalize(long rootId, boolean includeRoot, boolean storeChildren) throws PersistenceException {
		Map<Long, Node> tree = loadTree(rootId);
		Node root = tree.get(rootId);
		if (root == null)
			return;
		Set<Long> aboveRoot = ancestors(root.parentId);
		boolean rootWorkspace = includeRoot && locator.locate(rootId).kind() == Kind.MATTER_WORKSPACE;

		Set<Long> changed = new LinkedHashSet<>();
		for (int pass = 0; pass < MAX_PASSES; pass++) {
			boolean any = false;
			for (Node node : tree.values()) {
				if (node.alias || (node == root && !includeRoot) || node.parentId == node.id)
					continue;

				Map<Long, Set<Permission>> parentAcl;
				long parentSource;
				Set<Long> ancestors;
				if (node == root) {
					parentAcl = AclMath.effective(node.parentId);
					parentSource = AclMath.source(node.parentId);
					ancestors = aboveRoot;
				} else {
					Node parent = tree.get(node.parentId);
					parentAcl = acl(parent, tree);
					parentSource = source(parent, tree);
					ancestors = ancestors(node, tree, aboveRoot);
				}

				boolean own = node.ownAcl();
				boolean workspace = (storeChildren && node.parentId == rootId) || (node == root && rootWorkspace);
				Outcome outcome = workspace
						? decideWorkspace(node.id, node.ref, own ? Map.of() : acl(node.ref, tree), ancestors,
								parentAcl, parentSource)
						: decide(node.id, node.ref, own ? node.rows : Map.of(), own ? Map.of() : acl(node.ref, tree),
								ancestors, parentAcl, parentSource);
				if (outcome != null) {
					node.ref = outcome.securityRef();
					node.rows = outcome.rows();
					changed.add(node.id);
					any = true;
				}
			}
			if (!any)
				break;
		}
		write(tree, changed);
	}

	/**
	 * @return the root and every live folder below it, top-down, with their
	 *         own rows
	 */
	private Map<Long, Node> loadTree(long rootId) {
		Map<Long, Node> tree = new LinkedHashMap<>();
		String columns = "select ld_id, ld_parentid, ld_securityref, ld_type, ld_foldref from ld_folder where ";
		List<Node> level = named.query(columns + "ld_id = :id and ld_deleted = 0",
				new MapSqlParameterSource("id", rootId), (rs, i) -> node(rs));
		while (!level.isEmpty()) {
			List<Long> ids = new ArrayList<>();
			for (Node node : level)
				if (!tree.containsKey(node.id)) {
					tree.put(node.id, node);
					ids.add(node.id);
				}
			List<Node> next = new ArrayList<>();
			for (List<Long> chunk : chunks(ids))
				next.addAll(named.query(
						columns + "ld_deleted = 0 and ld_parentid in (:ids) and not ld_id = ld_parentid order by ld_id",
						new MapSqlParameterSource("ids", chunk), (rs, i) -> node(rs)));
			level = next;
		}

		for (List<Long> chunk : chunks(tree.keySet()))
			named.query("select * from ld_folder_acl where ld_folderid in (:ids)",
					new MapSqlParameterSource("ids", chunk), rs -> {
						Set<Permission> granted = EnumSet.noneOf(Permission.class);
						for (Permission flag : AclMath.FLAGS)
							if (rs.getInt(AclMath.column(flag)) == 1)
								granted.add(flag);
						tree.get(rs.getLong("ld_folderid")).originalRows.put(rs.getLong("ld_groupid"), granted);
					});
		return tree;
	}

	private static Node node(ResultSet rs) throws SQLException {
		long id = rs.getLong(1);
		long parentId = rs.getLong(2);
		long ref = rs.getLong(3);
		Long securityRef = rs.wasNull() ? null : ref;
		int type = rs.getInt(4);
		rs.getLong(5);
		boolean alias = type == Folder.TYPE_ALIAS || !rs.wasNull();
		return new Node(id, parentId, securityRef, alias);
	}

	/**
	 * @return what the folder grants, from the tree's current state where the
	 *         tree holds it
	 */
	private static Map<Long, Set<Permission>> acl(Long folderId, Map<Long, Node> tree) {
		Node node = tree.get(folderId);
		return node != null ? acl(node, tree) : AclMath.effective(folderId);
	}

	private static Map<Long, Set<Permission>> acl(Node node, Map<Long, Node> tree) {
		Set<Long> seen = new HashSet<>();
		Node current = node;
		while (seen.add(current.id)) {
			if (current.ownAcl())
				return AclMath.granted(current.rows);
			Node next = tree.get(current.ref);
			if (next == null)
				return AclMath.effective(current.ref);
			current = next;
		}
		// A cycle grants nothing
		return Map.of();
	}

	/**
	 * @return the folder whose ACL the node uses
	 */
	private static long source(Node node, Map<Long, Node> tree) {
		Set<Long> seen = new HashSet<>();
		Node current = node;
		while (seen.add(current.id)) {
			if (current.ownAcl())
				return current.id;
			Node next = tree.get(current.ref);
			if (next == null)
				return AclMath.source(current.ref);
			current = next;
		}
		return current.id;
	}

	/**
	 * @return the node's ancestors: its parents in the tree, then the root's
	 */
	private static Set<Long> ancestors(Node node, Map<Long, Node> tree, Set<Long> aboveRoot) {
		Set<Long> ancestors = new HashSet<>(aboveRoot);
		Node current = tree.get(node.parentId);
		while (current != null && ancestors.add(current.id))
			current = tree.get(current.parentId);
		return ancestors;
	}

	/**
	 * @param folderId a folder
	 * @return the folder and every folder above it that a folder inside a
	 *         matter may inherit from: up to its Store, never the root (plan,
	 *         R28), whose permission changes narrow no matter
	 */
	private Set<Long> ancestors(long folderId) {
		Set<Long> ancestors = new LinkedHashSet<>();
		long current = folderId;
		while (true) {
			List<Long> parents = jdbc.queryForList("select ld_parentid from ld_folder where ld_id = ?", Long.class,
					current);
			// The root is its own parent
			if (parents.isEmpty() || parents.get(0) == current || !ancestors.add(current))
				break;
			current = parents.get(0);
		}
		return ancestors;
	}

	private void write(Map<Long, Node> tree, Set<Long> changed) {
		if (changed.isEmpty())
			return;

		String flagColumns = AclMath.FLAGS.stream().map(AclMath::column).collect(Collectors.joining(", "));
		String insert = "insert into ld_folder_acl (ld_folderid, ld_groupid, %s) values (?, ?%s)".formatted(flagColumns,
				", ?".repeat(AclMath.FLAGS.size()));
		String update = "update ld_folder_acl set %s where ld_folderid = ? and ld_groupid = ?".formatted(AclMath.FLAGS
				.stream().map(flag -> AclMath.column(flag) + " = ?").collect(Collectors.joining(", ")));

		for (Long id : changed) {
			Node node = tree.get(id);
			if (!Objects.equals(node.ref, node.originalRef))
				jdbc.update("update ld_folder set ld_securityref = ? where ld_id = ?", new Object[] { node.ref, id },
						new int[] { Types.BIGINT, Types.BIGINT });

			Map<Long, Set<Permission>> rows = node.ownAcl() ? node.rows : Map.of();
			for (Long group : node.originalRows.keySet())
				if (!rows.containsKey(group))
					jdbc.update("delete from ld_folder_acl where ld_folderid = ? and ld_groupid = ?", id, group);
			rows.forEach((group, flags) -> {
				Set<Permission> before = node.originalRows.get(group);
				if (before == null) {
					List<Object> args = new ArrayList<>(List.of(id, group));
					args.addAll(flags(flags));
					jdbc.update(insert, args.toArray());
				} else if (!before.equals(flags)) {
					List<Object> args = new ArrayList<>(flags(flags));
					args.add(id);
					args.add(group);
					jdbc.update(update, args.toArray());
				}
			});
		}

		evictor.evictFolders(changed);
		evictor.evictFoldersAfterCompletion(changed);
	}

	private static List<Integer> flags(Set<Permission> granted) {
		return AclMath.FLAGS.stream().map(flag -> granted.contains(flag) ? 1 : 0).toList();
	}

	/**
	 * @return true if one of the user's groups has SECURITY in the folder's
	 *         own rows
	 */
	private boolean hasSecurity(long folderId, long userId) {
		Integer count = jdbc.queryForObject(
				"select count(*) from ld_folder_acl where ld_folderid = ? and ld_security = 1 and ld_groupid in (select ld_groupid from ld_usergroup where ld_userid = ?)",
				Integer.class, folderId, userId);
		return count != null && count > 0;
	}

	/**
	 * @return the user's own group, null if there is none
	 */
	private Long personalGroup(long userId) {
		List<Long> ids = jdbc.queryForList("select ld_id from ld_group where ld_name = ? and ld_tenantid = ?",
				Long.class, "_user_" + userId, Tenant.DEFAULT_ID);
		return ids.isEmpty() ? null : ids.get(0);
	}

	/**
	 * @return true if the user is an admin or one of the user's groups can
	 *         read the Store
	 */
	private boolean canRead(long userId, Map<Long, Set<Permission>> storeAcl) {
		List<Long> groups = jdbc.queryForList("select ld_groupid from ld_usergroup where ld_userid = ?", Long.class,
				userId);
		return groups.contains(Group.GROUPID_ADMIN) || groups.stream()
				.anyMatch(group -> storeAcl.getOrDefault(group, Set.of()).contains(Permission.READ));
	}

	private String label() {
		return settings.matterLabel().toLowerCase(Locale.ROOT);
	}

	private static String capitalize(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private HealdanRuleException refuse(String message, Long... folderIds) {
		List<Long> ids = new ArrayList<>();
		for (Long id : folderIds)
			if (id != null && id != 0L)
				ids.add(id);
		evictor.evictFoldersOnRollback(ids);
		return new HealdanRuleException(message);
	}

	private static List<List<Long>> chunks(Collection<Long> ids) {
		List<Long> all = new ArrayList<>(ids);
		List<List<Long>> chunks = new ArrayList<>();
		for (int i = 0; i < all.size(); i += CHUNK)
			chunks.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
		return chunks;
	}
}
