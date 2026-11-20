// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.matters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.GroupNames;
import com.logicaldoc.healdan.admin.matters.MatterCheck.Problem;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.MatterNumbering;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NumberingMode;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.security.PermissionRules;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * The Admin app's matters (Admin app spec section 8.3): New Matter's check, which says beforehand what
 * {@link MatterService#create} would refuse in the form as it stands, with the same rules and sentences, so the
 * problems show before <b>Create Matter</b>. It reads and writes nothing.
 */
@Component
public class MatterAdmin {

	/** The refusal of a check asked for by someone who isn't an administrator */
	static final String ONLY_ADMINS = "Only an administrator can check a new matter.";

	/** A template's folder that grants more than the folder it's in, with its path in the template and the excess */
	static final String WIDER = "In the template, %s grants more than the folder it's in (%s).";

	/** A group, or a person, who could read the matter and can't read its Store, with the Store's name */
	static final String UNREADABLE = "%s can't read the Store %s.";

	private final MatterService matters;

	private final MatterNumbering numbering;

	private final PermissionRules permissionRules;

	private final FilingRules filingRules;

	private final HealdanSettings settings;

	@Autowired
	public MatterAdmin(MatterService matters, MatterNumbering numbering, PermissionRules permissionRules,
			FilingRules filingRules, HealdanSettings settings) {
		this.matters = matters;
		this.numbering = numbering;
		this.permissionRules = permissionRules;
		this.filingRules = filingRules;
		this.settings = settings;
	}

	/**
	 * @return the bean
	 */
	public static MatterAdmin get() {
		return Context.get(MatterAdmin.class);
	}

	/**
	 * New Matter's form as it stands, {@code openapi-admin.yaml}'s {@code MatterDraft}: {@link MatterService#create}'s
	 * request, any member of which may be missing while the form is filled in.
	 *
	 * @param clientId the client; null if none is chosen yet
	 * @param number the matter's number; null if none is given yet
	 * @param name its name; null or blank if none is given yet
	 * @param description its description; null for none
	 * @param storeId the Store; null if none is chosen yet
	 * @param ownerId the owner; null if none is chosen yet
	 * @param templateId the workspace template; null if none is chosen yet
	 */
	public record Draft(Long clientId, Long number, String name, String description, Long storeId, Long ownerId,
			Long templateId) {
	}

	/**
	 * Looks for what {@link MatterService#create} would refuse in the draft, each problem only once the draft gives
	 * every member it needs ({@code MatterProblemCode}). A blank name is no problem here, as the form asks for one
	 * itself.
	 *
	 * @param draft the form as it stands
	 * @param actor who asks, an administrator
	 * @return the problems, in the order of {@link MatterCheck#CODES}; none if nothing is wrong
	 * @throws HealdanRuleException if the actor isn't an administrator
	 * @throws PersistenceException error at data layer
	 */
	public MatterCheck check(Draft draft, User actor) throws PersistenceException {
		if (!filingRules.isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
		List<Problem> problems = new ArrayList<>();

		Client client = draft.clientId() != null ? ClientDAO.get().findById(draft.clientId()) : null;
		if (draft.clientId() != null && client == null)
			problems.add(Problem.of(MatterCheck.CLIENT_MISSING, MatterService.NO_CLIENT));

		Long number = draft.number();
		if (number != null)
			checkNumber(client, number, problems);

		if (draft.name() != null && !draft.name().isBlank())
			try {
				// Its length counts only once the numbers before it in the folder's name are known
				matters.checkName(draft.name(), client != null ? client.getNumber() : null, number);
			} catch (HealdanRuleException e) {
				problems.add(Problem.of(MatterCheck.NAME_INVALID, e.getMessage()));
			}

		if (draft.description() != null)
			try {
				matters.checkDescription(draft.description());
			} catch (HealdanRuleException e) {
				problems.add(Problem.of(MatterCheck.DESCRIPTION_INVALID, e.getMessage()));
			}

		Folder store = draft.storeId() != null ? matters.liveStore(draft.storeId()) : null;
		if (draft.storeId() != null && store == null)
			problems.add(Problem.of(MatterCheck.STORE_MISSING, MatterService.NO_STORE));

		Folder template = draft.templateId() != null ? matters.template(draft.templateId()) : null;
		if (draft.templateId() != null && template == null)
			problems.add(Problem.of(MatterCheck.TEMPLATE_MISSING, MatterService.NO_TEMPLATE));

		User owner = draft.ownerId() != null ? UserDAO.get().findById(draft.ownerId()) : null;
		if (draft.ownerId() != null && owner == null)
			problems.add(Problem.of(MatterCheck.OWNER_MISSING, MatterService.NO_OWNER));
		if (owner != null && !owner.isEnabled())
			problems.add(Problem.of(MatterCheck.OWNER_DISABLED,
					MatterService.DISABLED_OWNER.formatted(owner.getUsername())));

		if (template != null)
			checkTemplate(template, "", problems);
		if (template != null && store != null)
			checkStore(template, store, owner, problems);
		return new MatterCheck(problems);
	}

	/**
	 * {@code number-invalid}, and {@code number-taken} once the client is known, or whatever the client when numbers
	 * are system-wide: {@link MatterNumbering#checkAvailable}'s refusals, as the create's
	 */
	private void checkNumber(Client client, long number, List<Problem> problems) {
		boolean systemWide = settings.numberingMode() == NumberingMode.SYSTEM_WIDE;
		if (number >= 1 && !systemWide && client == null)
			return;
		try {
			// Per client the client is known by now; system-wide, or below 1, the client isn't asked about
			numbering.checkAvailable(client != null ? client.getId() : 0L, number);
		} catch (HealdanRuleException e) {
			String code = number < 1 ? MatterCheck.NUMBER_INVALID : MatterCheck.NUMBER_TAKEN;
			problems.add(Problem.of(code, e.getMessage()));
		}
	}

	/**
	 * {@code wider-than-parent} (P1), for each folder of the template, at every level, that grants a group something
	 * the folder it's in doesn't, as its copy in the matter would: top down, each folder's own before its folders',
	 * those by name
	 *
	 * @param folder a folder of the template, the template itself first
	 * @param path the folder's path in the template, as {@code Documents/Invoices}; empty for the template itself
	 */
	private void checkTemplate(Folder folder, String path, List<Problem> problems) throws PersistenceException {
		FolderDAO folders = FolderDAO.get();
		Map<Long, Set<Permission>> parentAcl = AclMath.effective(folder.getId());
		List<Folder> children = new ArrayList<>(folders.findChildren(folder.getId(), (Integer) null));
		children.sort(
				Comparator.comparing(Folder::getName, String.CASE_INSENSITIVE_ORDER).thenComparingLong(Folder::getId));
		for (Folder child : children) {
			// The copy skips aliases, as a workspace holds none
			if (child.getFoldRef() != null || child.getType() == Folder.TYPE_ALIAS)
				continue;
			String childPath = path.isEmpty() ? child.getName() : path + "/" + child.getName();
			Map<Long, Set<Permission>> acl = permissionRules.prospective(folders.findById(child.getId(), true));
			String excess = AclMath.firstExcess(acl, parentAcl);
			if (excess != null) {
				long groupId = AclMath.firstExcessGroup(acl, parentAcl);
				problems.add(new Problem(MatterCheck.WIDER_THAN_PARENT, WIDER.formatted(childPath, excess), null, null,
						groupId, GroupNames.of(groupId)));
			}
			checkTemplate(child, childPath, problems);
		}
	}

	/**
	 * {@code store-unreadable} (P2), for each group the matter's own folder would let read it that can't read the
	 * Store: what the template grants, and the owner's own group with every permission, as the create gives it, which
	 * passes if the owner can read the Store through any group
	 */
	private void checkStore(Folder template, Folder store, User owner, List<Problem> problems)
			throws PersistenceException {
		Map<Long, Set<Permission>> acl = new TreeMap<>(AclMath.effective(template.getId()));
		Group ownGroup = owner != null ? GroupDAO.get().findByName(owner.getUserGroupName(), Tenant.DEFAULT_ID) : null;
		if (ownGroup != null)
			acl.put(ownGroup.getId(), EnumSet.allOf(Permission.class));
		List<Long> failures = permissionRules.p2Failures(acl, store.getId(), owner != null ? owner.getId() : null);
		// Every group named, in two queries
		Map<Long, String> names = GroupNames.of(failures);
		for (long groupId : failures) {
			String name = names.get(groupId);
			problems.add(new Problem(MatterCheck.STORE_UNREADABLE, UNREADABLE.formatted(name, store.getName()),
					MatterCheck.GRANT_STORE_READ, store.getId(), groupId, name));
		}
	}

}
