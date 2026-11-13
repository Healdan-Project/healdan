// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.rules.Vetoes;

/**
 * Refuses a save of a document's permissions in which a row grants nothing
 * (spec 2026-11-13-document-rows-that-grant-nothing). CE's
 * {@code getAllowedPermissions} reads such a row as no row and gives its group
 * the folder's permissions, so it keeps no one out, while CE's folder list and
 * searches hide the document from that group: a row with nothing ticked looks
 * like a denial and isn't one.
 * <p>
 * Only stores with the event {@code PERMISSION} are checked: the Classic
 * Security tab's and REST's {@code setAccessControlList}. The Classic bulk
 * Update, whose loop would log the refusal and go on, checks its rows first
 * through {@link #check}. Every other store passes, so a document that still
 * has such a row, written by CE's JDBC copy of a folder's rows, can be saved
 * as before. A guest's row (the guest group's, or a read-only user's) is
 * checked as CE stores it, without the permissions a guest can't have. Admins
 * aren't exempt. CE creates it by reflection, hence the public no-arg
 * constructor.
 */
public class PermissionsDocumentListener implements DocumentListener {

	static final String NOTHING_TICKED = "Nothing is ticked for %s.";

	static final String NOTHING_A_GUEST_CAN_HAVE = "Nothing is ticked for %s that a read-only account can have: "
			+ "Write, Delete, Move and the other changes don't count for one.";

	static final String KEEPS_NO_ONE_OUT = "A row that grants nothing keeps no one out: they'd still have the "
			+ "folder's access. Tick what they may do, or remove the row. To keep someone out of a document, move it "
			+ "to a folder they can't read.";

	/**
	 * What CE takes off a guest's row before it stores it
	 * ({@code HibernateDocumentDAO.removeForbiddenPermissionsForGuests})
	 */
	private static final Set<Permission> NOT_FOR_GUESTS = EnumSet.of(Permission.ARCHIVE, Permission.AUTOMATION,
			Permission.CALENDAR, Permission.DELETE, Permission.IMMUTABLE, Permission.MOVE, Permission.PASSWORD,
			Permission.RENAME, Permission.SECURITY, Permission.SIGN, Permission.WORKFLOW, Permission.WRITE);

	public PermissionsDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (transaction == null || !DocumentEvent.PERMISSION.toString().equals(transaction.getEvent()))
			return;

		String refusal = refusal(document.getAccessControlList());
		if (refusal == null)
			return;

		Folder folder = document.getFolder();
		Vetoes.check(Vetoes.ids(folder != null ? folder.getId() : null), Vetoes.ids(document.getId()), () -> {
			throw new HealdanRuleException(refusal);
		});
	}

	/**
	 * Refuses a document's permission rows when one of them grants nothing
	 *
	 * @param rows the rows, as they'd be saved
	 *
	 * @throws HealdanRuleException naming each row that grants nothing
	 * @throws PersistenceException error at data layer
	 */
	public static void check(Collection<DocumentAccessControlEntry> rows) throws PersistenceException {
		String refusal = refusal(rows);
		if (refusal != null)
			throw new HealdanRuleException(refusal);
	}

	/**
	 * @return the refusal naming each row that grants nothing, a guest's once
	 *         the permissions a guest can't have are off it, or null if every
	 *         row grants something
	 */
	private static String refusal(Collection<DocumentAccessControlEntry> rows) throws PersistenceException {
		List<String> nothing = new ArrayList<>();
		List<String> guests = new ArrayList<>();
		for (DocumentAccessControlEntry row : rows) {
			Set<Permission> granted = row.grantedPermissions();
			if (!NOT_FOR_GUESTS.containsAll(granted))
				continue;
			Group group = GroupDAO.get().findById(row.getGroupId());
			if (group != null && group.isGuest())
				guests.add(name(group, row.getGroupId()));
			else if (granted.isEmpty())
				nothing.add(name(group, row.getGroupId()));
		}
		if (nothing.isEmpty() && guests.isEmpty())
			return null;

		List<String> sentences = new ArrayList<>();
		if (!nothing.isEmpty())
			sentences.add(NOTHING_TICKED.formatted(join(nothing)));
		if (!guests.isEmpty())
			sentences.add(NOTHING_A_GUEST_CAN_HAVE.formatted(join(guests)));
		sentences.add(KEEPS_NO_ONE_OUT);
		return String.join(" ", sentences);
	}

	/**
	 * @return a person's own group as the person, any other group by its name
	 */
	private static String name(Group group, long groupId) {
		if (group == null)
			return "group " + groupId;
		if (group.isUserGroup()) {
			User user = group.getUser();
			if (user != null)
				return user.getFullName();
		}
		return group.getName();
	}

	/**
	 * @return the names, sorted without regard to case, as a list in prose:
	 *         {@code A}, {@code A and B}, {@code A, B and C}
	 */
	static String join(List<String> names) {
		names.sort(String.CASE_INSENSITIVE_ORDER);
		if (names.size() == 1)
			return names.get(0);
		return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// A check-in stores no permissions
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
		// Nothing to do
	}
}
