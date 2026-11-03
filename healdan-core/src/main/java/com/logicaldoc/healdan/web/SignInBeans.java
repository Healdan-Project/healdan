// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.gui.common.client.beans.GUIGroup;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIGroupMapping;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUILinkedAccount;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInProvider;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.SignInAdmin.Linked;
import com.logicaldoc.healdan.signin.SignInProvider;

/**
 * The Sign-In tab's beans, from and to the sign-in package's records (M4 spec
 * section 10.3). A provider's bean carries {@code hasSecret}, never the secret,
 * which {@link SignInProvider} doesn't hold either.
 */
final class SignInBeans {

	private SignInBeans() {
	}

	/**
	 * @param p the provider
	 * @param redirect its redirect address
	 * @param groups its group map
	 * @param linked how many accounts are linked to it
	 * @return the provider's bean
	 */
	static GUISignInProvider toGUI(SignInProvider p, String redirect, GUIGroupMapping[] groups, int linked) {
		GUISignInProvider gui = new GUISignInProvider();
		gui.setId(p.id());
		gui.setPreset(p.preset().key());
		gui.setLabel(p.label());
		gui.setEnabled(p.enabled());
		gui.setIssuer(p.issuer());
		gui.setClientId(p.clientId());
		gui.setHasSecret(p.hasSecret());
		gui.setIdentityClaim(p.identityClaim());
		gui.setUsernameClaim(p.usernameClaim());
		gui.setEmailClaim(p.emailClaim());
		gui.setGroupsClaim(p.groupsClaim());
		gui.setLinkByEmail(p.linkByEmail());
		gui.setRedirect(redirect);
		gui.setGroups(groups);
		gui.setLinked(linked);
		return gui;
	}

	/**
	 * @param gui the provider as the tab sent it
	 * @return the provider to save; its {@code hasSecret} is ignored
	 */
	static SignInProvider fromGUI(GUISignInProvider gui) {
		return new SignInProvider(gui.getId(), Preset.of(gui.getPreset()), gui.getLabel(), gui.isEnabled(),
				gui.getIssuer(), gui.getClientId(), false, gui.getIdentityClaim(), gui.getUsernameClaim(),
				gui.getEmailClaim(), gui.getGroupsClaim(), gui.isLinkByEmail());
	}

	/**
	 * @param rows the group map as the tab sent it
	 * @return the rows to save, leaving out those of deleted groups, which are
	 *         ignored anyway (spec section 8)
	 */
	static List<GroupMapping> mappings(GUIGroupMapping[] rows) {
		List<GroupMapping> mappings = new ArrayList<>();
		if (rows != null)
			for (GUIGroupMapping row : rows)
				if (row != null && !row.isDeleted())
					mappings.add(new GroupMapping(row.getValue(), row.getGroupId()));
		return mappings;
	}

	/**
	 * @param mappings a provider's group map
	 * @return its rows, each with its group's name, and marked when the group was
	 *         deleted
	 * @throws PersistenceException error at data layer
	 */
	static GUIGroupMapping[] groups(List<GroupMapping> mappings) throws PersistenceException {
		List<GUIGroupMapping> rows = new ArrayList<>();
		for (GroupMapping mapping : mappings) {
			GUIGroupMapping row = new GUIGroupMapping(mapping.value(), mapping.groupId());
			Group group = GroupDAO.get().findById(mapping.groupId());
			if (group != null) {
				row.setGroupName(group.getName());
			} else {
				row.setDeleted(true);
				row.setGroupName(deletedName(mapping.groupId()));
			}
			rows.add(row);
		}
		return rows.toArray(GUIGroupMapping[]::new);
	}

	/**
	 * @return the name a deleted group had: CE's delete adds {@code .<id>} to
	 *         it; null if there is no such row at all
	 */
	private static String deletedName(long groupId) throws PersistenceException {
		String name = GroupDAO.get().queryForString("select ld_name from ld_group where ld_id = :id",
				Map.of("id", groupId));
		String suffix = "." + groupId;
		return name != null && name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
	}

	/**
	 * @param tenantId the tenant
	 * @return the groups a provider group can be mapped to, by name: the
	 *         tenant's groups but people's own, which Security &gt; Groups hides
	 *         and {@code SignInProviders.saveGroups} refuses
	 * @throws PersistenceException error at data layer
	 */
	static GUIGroup[] mappableGroups(long tenantId) throws PersistenceException {
		List<GUIGroup> groups = new ArrayList<>();
		for (Group group : GroupDAO.get().findByWhere(
				"_entity.type = %d and _entity.tenantId = %d".formatted(Group.Type.DEFAULT.ordinal(), tenantId), null,
				null)) {
			GUIGroup gui = new GUIGroup(group.getId());
			gui.setName(group.getName());
			gui.setDescription(group.getDescription());
			groups.add(gui);
		}
		groups.sort(Comparator.comparing(GUIGroup::getName, String.CASE_INSENSITIVE_ORDER));
		return groups.toArray(GUIGroup[]::new);
	}

	/**
	 * @param linked an account linked to a provider
	 * @return its bean
	 */
	static GUILinkedAccount toGUI(Linked linked) {
		GUILinkedAccount gui = new GUILinkedAccount();
		gui.setUserId(linked.userId());
		gui.setUsername(linked.username());
		gui.setName(linked.name());
		gui.setHow(linked.how());
		gui.setLinked(linked.linked());
		gui.setLastSignIn(linked.lastSignIn());
		return gui;
	}
}
