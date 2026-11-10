// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.navigation.MyMatters;

/**
 * {@code GET /v1/clients/recent} (M6 spec section 9.1).
 */
public class ClientResourceTest extends ApiTestCase {

	private User clerk;

	private Folder store;

	private Matter zebra;

	private Matter apple;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		zebra = fx.matter(fx.client(1001, "Zebra Shipping"), 1, "Ships", store, "Documents");
		apple = fx.matter(fx.client(1002, "Apple Holdings"), 1, "Orchard", store, "Documents");
	}

	private void changeIn(Matter matter, long id) throws Exception {
		Folder documents = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		Timestamp date = Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
		jdbc().update("""
				insert into ld_history (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,
				                        ld_tenantid, ld_folderid, ld_userid, ld_username, ld_date, ld_event,
				                        ld_notified)
				values (?, ?, ?, 1, 0, ?, ?, ?, ?, ?, ?, 0)
				""", id, date, date, Tenant.DEFAULT_ID, documents.getId(), clerk.getId(), clerk.getUsername(), date,
				DocumentEvent.STORED.toKey());
	}

	@Test
	public void testEmptyAtFirst() throws Exception {
		ApiResponse response = get("/api/v1/clients/recent").token(signIn(clerk)).send();
		assertEquals(response.toString(), 200, response.status());
		assertEquals(0, response.json().size());
	}

	@Test
	public void testClientsOfRecentAndMyMatters() throws Exception {
		changeIn(zebra, 3_000_001L);
		MyMatters.get().add(clerk.getId(), apple.getFolderId());

		ApiResponse response = get("/api/v1/clients/recent").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode clients = response.json();
		assertEquals(2, clients.size());
		assertEquals(1002, clients.get(0).path("number").asLong());
		assertEquals("Apple Holdings", clients.get(0).path("name").textValue());
		assertEquals(1001, clients.get(1).path("number").asLong());
		assertEquals("Zebra Shipping", clients.get(1).path("name").textValue());
	}

	@Test
	public void testUnreadableMatterIsLeftOut() throws Exception {
		User outsider = fx.user("outsider");
		MyMatters.get().add(clerk.getId(), apple.getFolderId());
		changeIn(zebra, 3_000_002L);
		// Another person's list never shows through
		assertEquals(0, get("/api/v1/clients/recent").token(signIn(outsider)).send().json().size());

		jdbc().update("delete from ld_folder_acl where ld_folderid in (?, ?)", store.getId(), apple.getFolderId());
		FolderDAO.get().evict(Folder.class, apple.getFolderId());
		FolderDAO.get().evict(Folder.class, store.getId());

		// Apple's workspace is no longer readable; Zebra's still is
		JsonNode clients = get("/api/v1/clients/recent").token(signIn(clerk)).send().json();
		assertEquals(clients.toString(), 1, clients.size());
		assertEquals(1001, clients.get(0).path("number").asLong());
	}
}
