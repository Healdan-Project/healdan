// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.navigation;

import static org.junit.Assert.assertEquals;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * Recent Clients: the clients of Recent Matters and My Matters, each once, by
 * name, and only through matters the user can read.
 */
public class RecentClientsTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private User alice;

	private Folder store;

	private Matter zebraOne;

	private Matter zebraTwo;

	private Matter apple;

	private long nextHistoryId = 2_000_000L;

	@Before
	public void createLayout() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		Group lawyers = fx.group("lawyers");
		alice = fx.user("alice", lawyers.getName());
		store = fx.store("Main");
		fx.grant(store, lawyers, Permission.values());
		Client zebra = fx.client(1001, "Zebra Shipping");
		Client appleClient = fx.client(1002, "apple Holdings");
		zebraOne = fx.matter(zebra, 1, "One", store, "Documents");
		zebraTwo = fx.matter(zebra, 2, "Two", store, "Documents");
		apple = fx.matter(appleClient, 1, "Orchard", store, "Documents");
	}

	private void changeIn(Matter matter) throws Exception {
		Folder documents = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		Timestamp date = Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
		jdbc.update("""
				insert into ld_history (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,
				                        ld_tenantid, ld_folderid, ld_userid, ld_username, ld_date, ld_event,
				                        ld_notified)
				values (?, ?, ?, 1, 0, ?, ?, ?, ?, ?, ?, 0)
				""", nextHistoryId++, date, date, Tenant.DEFAULT_ID, documents.getId(), alice.getId(),
				alice.getUsername(), date, DocumentEvent.STORED.toKey());
	}

	@Test
	public void testNone() throws Exception {
		assertEquals(List.of(), RecentClients.get().list(alice.getId()));
	}

	@Test
	public void testDistinctByNameFromBothLists() throws Exception {
		changeIn(zebraOne);
		changeIn(zebraTwo);
		MyMatters.get().add(alice.getId(), apple.getFolderId());
		// In both lists, still once
		MyMatters.get().add(alice.getId(), zebraTwo.getFolderId());

		List<RecentClients.ClientEntry> clients = RecentClients.get().list(alice.getId());

		assertEquals(List.of("apple Holdings", "Zebra Shipping"),
				clients.stream().map(RecentClients.ClientEntry::name).toList());
		assertEquals(List.of(1002L, 1001L), clients.stream().map(RecentClients.ClientEntry::number).toList());
	}

	@Test
	public void testUnreadableMatterLeavesNothing() throws Exception {
		changeIn(apple);
		MyMatters.get().add(alice.getId(), apple.getFolderId());
		assertEquals(1, RecentClients.get().list(alice.getId()).size());

		// The matter's workspace stops being readable to the user's group
		jdbc.update("delete from ld_folder_acl where ld_folderid in (?, ?)", store.getId(), apple.getFolderId());
		FolderDAO.get().evict(Folder.class, apple.getFolderId());

		assertEquals(List.of(), RecentClients.get().list(alice.getId()));
	}
}
