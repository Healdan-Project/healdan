// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.security;

import java.util.HashMap;
import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;

/**
 * Whether a user may read folders, each asked of CE once
 * ({@code FolderDAO.isReadAllowed}) and remembered, for one request that
 * checks many documents across many folders, such as a search's hits. An
 * administrator reads every folder. Keep one for a request only: a folder's
 * grants can change between requests.
 */
public final class FolderReads {

	private final User user;

	private final boolean admin;

	private final Map<Long, Boolean> answers = new HashMap<>();

	/**
	 * @param user who reads, with its groups
	 */
	public FolderReads(User user) {
		this.user = user;
		this.admin = user.isAdmin();
	}

	/**
	 * @return who reads
	 */
	public User user() {
		return user;
	}

	/**
	 * @param folderId a folder's id
	 * @return whether the user may read the folder
	 * @throws PersistenceException error at data layer
	 */
	public boolean readable(long folderId) throws PersistenceException {
		if (admin)
			return true;
		Boolean known = answers.get(folderId);
		if (known == null) {
			known = FolderDAO.get().isReadAllowed(folderId, user.getId());
			answers.put(folderId, known);
		}
		return known;
	}
}
