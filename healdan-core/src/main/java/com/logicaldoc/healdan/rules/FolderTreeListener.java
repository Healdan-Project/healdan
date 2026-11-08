// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.rules;

import java.util.List;

import com.logicaldoc.core.PersistenceException;

/**
 * Told after CE moved a folder tree with JDBC, where no {@code FolderListener} runs. {@link HealdanHooksImpl} calls
 * every bean that implements it at the end of {@code afterFolderRestore}, inside CE's transaction, so a listener that
 * writes does so after the commit.
 */
public interface FolderTreeListener {

	/**
	 * @param folderId the restored folder
	 * @param parentId the folder it was restored under
	 * @param documentIds the documents of the folder and its subtree
	 * @throws PersistenceException if the listener vetoes or fails
	 */
	void afterTreeRestored(long folderId, long parentId, List<Long> documentIds) throws PersistenceException;
}
