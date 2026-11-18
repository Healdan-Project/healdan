// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.rules;

import static org.junit.Assert.assertEquals;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
 * folder's; and published, within its publishing dates, unless the person is
 * an administrator or a publisher, who see what isn't. An administrator's
 * counts take every folder and every document still stored.
 */
public class ReadableCountsTest extends AbstractRulesTestCase {

	@Test
	public void testCountsOnlyWhatEachPersonCanRead() throws Exception {
		User outsider = fx.user("outsider");
		// Carol reads as Bob does, and as a publisher sees what isn't published
		User carol = fx.user("carol", "readers", "publisher");
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
		// Published from tomorrow, until yesterday, and from yesterday until tomorrow
		Document future = upload(correspondence, "future.txt", admin);
		Document ended = upload(correspondence, "ended.txt", admin);
		Document current = upload(correspondence, "current.txt", admin);
		awaitAsyncWork();
		fx.documentRow(own.getId(), bob.getUserGroup().getId(), Permission.DELETE);
		jdbc.update("update ld_document set ld_published = 0 where ld_id = ?", unpublished.getId());
		Timestamp yesterday = Timestamp.from(Instant.now().minus(Duration.ofDays(1)));
		Timestamp tomorrow = Timestamp.from(Instant.now().plus(Duration.ofDays(1)));
		jdbc.update("update ld_document set ld_startpublishing = ? where ld_id = ?", tomorrow, future.getId());
		jdbc.update("update ld_document set ld_stoppublishing = ? where ld_id = ?", yesterday, ended.getId());
		jdbc.update("update ld_document set ld_startpublishing = ?, ld_stoppublishing = ? where ld_id = ?", yesterday,
				tomorrow, current.getId());
		CacheEvictor.get()
				.evictDocuments(List.of(unpublished.getId(), future.getId(), ended.getId(), current.getId()));
		StoredRows rows = StoredRows.get();
		List<Long> folders = List.of(correspondence.getId(), walled.getId(), inside.getId());
		// And an id with no row, which no count takes in, an administrator's included
		List<Long> documents = List.of(doc.getId(), open.getId(), own.getId(), plan.getId(), unpublished.getId(),
				future.getId(), ended.getId(), current.getId(), Long.MAX_VALUE);

		Map<User, Long> readsFolders = Map.of(alice, 1L, bob, 3L, carol, 3L, outsider, 0L, admin, 3L);
		// The deleted document in Correspondence isn't live; of the publishing windows, only the current one counts
		// for those who don't see what isn't published
		Map<User, Long> readsDocuments = Map.of(alice, 4L, bob, 4L, carol, 8L, outsider, 0L, admin, 8L);
		for (User user : List.of(alice, bob, carol, outsider, admin)) {
			assertEquals(user.getUsername(), readsFolders.get(user), Long.valueOf(rows.readableFolders(folders, user)));
			assertEquals(user.getUsername(), readsDocuments.get(user),
					Long.valueOf(rows.readableLiveDocumentsIn(folders, user)));
			assertEquals(user.getUsername(), readsDocuments.get(user),
					Long.valueOf(rows.readableDocuments(documents, user)));
		}

		// Deleted, they read the same; none is live
		folderDao.deleteTree(correspondence.getId(), folderHistory(admin));
		for (User user : List.of(alice, bob, carol, outsider, admin)) {
			assertEquals(user.getUsername(), readsFolders.get(user), Long.valueOf(rows.readableFolders(folders, user)));
			assertEquals(user.getUsername(), 0L, rows.readableLiveDocumentsIn(folders, user));
			assertEquals(user.getUsername(), readsDocuments.get(user),
					Long.valueOf(rows.readableDocuments(documents, user)));
		}
	}
}
