// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.folder.Folder;
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
 * Security tab's and REST's {@code setAccessControlList}. Every other store
 * passes, so a document that still has such a row, written by CE's JDBC copy
 * of a folder's rows, can be saved as before. CE takes the forbidden
 * permissions off a guest's row before the listeners run, so the check sees
 * the row as it would be stored. Admins aren't exempt. CE creates it by
 * reflection, hence the public no-arg constructor.
 */
public class PermissionsDocumentListener implements DocumentListener {

	static final String GRANTS_NOTHING = "Nothing is ticked for %s, and a row with nothing ticked keeps no one out: "
			+ "they'd still have the folder's access. To keep someone out of a document, move it to a folder they "
			+ "can't read.";

	public PermissionsDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (transaction == null || !DocumentEvent.PERMISSION.toString().equals(transaction.getEvent()))
			return;

		List<String> names = new ArrayList<>();
		for (DocumentAccessControlEntry row : document.getAccessControlList())
			if (row.grantedPermissions().isEmpty())
				names.add(name(row.getGroupId()));
		if (names.isEmpty())
			return;

		names.sort(String.CASE_INSENSITIVE_ORDER);
		Folder folder = document.getFolder();
		Vetoes.check(Vetoes.ids(folder != null ? folder.getId() : null), Vetoes.ids(document.getId()), () -> {
			throw new HealdanRuleException(GRANTS_NOTHING.formatted(join(names)));
		});
	}

	/**
	 * @return a person's own group as the person, any other group by its name
	 */
	private static String name(long groupId) throws PersistenceException {
		Group group = GroupDAO.get().findById(groupId);
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
	 * @return the names as a list in prose: {@code A}, {@code A and B},
	 *         {@code A, B and C}
	 */
	static String join(List<String> names) {
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
