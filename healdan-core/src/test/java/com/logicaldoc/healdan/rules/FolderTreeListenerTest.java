// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.rules;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;

/**
 * CE restores a tree with JDBC, where no folder listener runs, so {@link HealdanHooksImpl} tells every
 * {@link FolderTreeListener} once, after its own work, with the tree's documents.
 */
public class FolderTreeListenerTest extends AbstractRulesTestCase {

	@Test
	public void testRestoreTellsTheListenerOnce() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder inner = fx.folder(letters, "Inner");
		Document one = upload(letters, "one.txt", alice);
		Document two = upload(inner, "two.txt", alice);
		awaitAsyncWork();
		allowed(() -> folderDao.deleteTree(letters.getId(), folderHistory(admin)));

		RecordingTreeListener.CALLS.clear();
		allowed(() -> folderDao.restore(letters.getId(), documents.getId(), folderHistory(admin)));

		assertEquals(1, RecordingTreeListener.CALLS.size());
		RecordingTreeListener.Call call = RecordingTreeListener.CALLS.get(0);
		assertEquals(letters.getId(), call.folderId());
		assertEquals(documents.getId(), call.parentId());
		assertEquals(List.of(one.getId(), two.getId()), call.documentIds().stream().sorted().toList());
	}
}
