// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.rules;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.util.CacheEvictor;

/**
 * What a person can read among folders and documents, deleted or not, counted
 * as the Trash's views and a folder's contents count them (folders and Trash
 * spec 4.2): a folder by its own Read or the Read of the folder it takes its
 * security from; a document by its own rows that grant anything, else by its
 * folder's; and published unless the person sees what isn't. An
 * administrator's counts take everything.
 */
public class ReadableCountsTest extends AbstractRulesTestCase {

	@Test
	public void testCountsOnlyWhatEachPersonCanRead() throws Exception {
		User outsider = fx.user("outsider");
		// Walled has permissions of its own, Bob's group's alone, and Inside takes its security from it
		Group readers = GroupDAO.get().findByName("readers", Tenant.DEFAULT_ID);
		Folder walled = fx.folder(correspondence, "Walled");
		Folder inside = fx.folder(walled, "Inside");
		Folder unreferred = folderDao.findById(walled.getId(), true);
		unreferred.setSecurityRef(null);
		folderDao.store(unreferred, folderHistory(admin));
		fx.grant(walled, readers, Permission.READ);
		jdbc.update("update ld_folder set ld_securityref = ? where ld_id = ?", walled.getId(), inside.getId());
		CacheEvictor.get().evictFolders(List.of(inside.getId()));
		assertEquals(List.of(readers.getId()), jdbc.queryForList(
				"select ld_groupid from ld_folder_acl where ld_folderid = ? and ld_read = 1", Long.class,
				walled.getId()));
		Document open = upload(correspondence, "open.txt", admin);
		// Bob's own row grants something, not Read, so it decides for him; Alice has none, so the folder decides
		Document own = upload(correspondence, "own.txt", admin);
		Document plan = upload(inside, "plan.txt", admin);
		Document unpublished = upload(correspondence, "unpublished.txt", admin);
		awaitAsyncWork();
		fx.documentRow(own.getId(), bob.getUserGroup().getId(), Permission.DELETE);
		jdbc.update("update ld_document set ld_published = 0 where ld_id = ?", unpublished.getId());
		CacheEvictor.get().evictDocuments(List.of(unpublished.getId()));
		StoredRows rows = StoredRows.get();
		List<Long> folders = List.of(correspondence.getId(), walled.getId(), inside.getId());
		List<Long> documents = List.of(doc.getId(), open.getId(), own.getId(), plan.getId(), unpublished.getId());

		Map<User, Long> readsFolders = Map.of(alice, 1L, bob, 3L, outsider, 0L, admin, 3L);
		// The deleted document in Correspondence isn't live
		Map<User, Long> readsDocuments = Map.of(alice, 3L, bob, 3L, outsider, 0L, admin, 5L);
		for (User user : List.of(alice, bob, outsider, admin)) {
			assertEquals(user.getUsername(), readsFolders.get(user), Long.valueOf(rows.readableFolders(folders, user)));
			assertEquals(user.getUsername(), readsDocuments.get(user),
					Long.valueOf(rows.readableLiveDocumentsIn(folders, user)));
			assertEquals(user.getUsername(), readsDocuments.get(user),
					Long.valueOf(rows.readableDocuments(documents, user)));
		}

		// Deleted, they read the same; none is live
		folderDao.deleteTree(correspondence.getId(), folderHistory(admin));
		for (User user : List.of(alice, bob, outsider, admin)) {
			assertEquals(user.getUsername(), readsFolders.get(user), Long.valueOf(rows.readableFolders(folders, user)));
			assertEquals(user.getUsername(), 0L, rows.readableLiveDocumentsIn(folders, user));
			assertEquals(user.getUsername(), readsDocuments.get(user),
					Long.valueOf(rows.readableDocuments(documents, user)));
		}
	}
}
