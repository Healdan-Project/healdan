// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * A document's Store (call 10): the top-level workspace above its folder, found by walking {@code ld_parentid}.
 */
public class StoreOfTest extends AbstractStorageTestCase {

	private HealdanFixtures fx;

	private StorageFixtures sf;

	private StorageFixtures.Side a;

	private StorageFixtures.Side b;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			sf = new StorageFixtures();
			fx = sf.fx;
			a = sf.side("Alpha", StorageBackend.ORIGINAL, null, null);
			b = sf.side("Bravo", StorageBackend.ORIGINAL, null, null);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static Optional<Long> of(long docId) {
		return StoreOf.get().of(docId);
	}

	private static Optional<Long> ofFolder(long folderId) {
		return StoreOf.get().ofFolder(folderId);
	}

	/** Puts a document in another folder with JDBC, as rules forbid it there */
	private static void moveByJdbc(Document doc, long folderId) {
		StorageFixtures.jdbc().update("update ld_document set ld_folderid = ? where ld_id = ?", folderId, doc.getId());
		Context.get(CacheEvictor.class).evictDocuments(List.of(doc.getId()));
	}

	private static void reparentByJdbc(Folder folder, long parentId) {
		StorageFixtures.jdbc().update("update ld_folder set ld_parentid = ? where ld_id = ?", parentId, folder.getId());
		Context.get(CacheEvictor.class).evictFolders(List.of(folder.getId()));
	}

	@Test
	public void testDocumentInAMatter() throws Exception {
		Document doc = fx.upload(a.folder(), "a.txt", "a", fx.admin());
		assertEquals(Optional.of(a.store().getId()), of(doc.getId()));
		assertEquals(Optional.of(a.store().getId()), ofFolder(a.folder().getId()));
		assertEquals(Optional.of(a.store().getId()), ofFolder(a.store().getId()));
		assertEquals(Optional.of(b.store().getId()), ofFolder(b.folder().getId()));
	}

	@Test
	public void testParentIdsNotPath() throws Exception {
		Folder moved = fx.folder(a.folder(), "Moved");
		Folder below = fx.folder(moved, "Below");
		Document doc = fx.upload(below, "a.txt", "a", fx.admin());
		String path = StorageFixtures.jdbc().queryForObject("select ld_path from ld_folder where ld_id = ?",
				String.class, below.getId());

		// As CE's folder restore leaves it: the parent changed with JDBC, the path not
		reparentByJdbc(moved, b.folder().getId());
		assertEquals(path, StorageFixtures.jdbc().queryForObject("select ld_path from ld_folder where ld_id = ?",
				String.class, below.getId()));
		assertTrue("the path still names Alpha", path.startsWith("/" + a.store().getId() + "/"));

		assertEquals(Optional.of(b.store().getId()), ofFolder(moved.getId()));
		assertEquals(Optional.of(b.store().getId()), ofFolder(below.getId()));
		assertEquals(Optional.of(b.store().getId()), of(doc.getId()));

		// And with no path at all
		StorageFixtures.jdbc().update("update ld_folder set ld_path = null where ld_id in (?, ?)", moved.getId(),
				below.getId());
		assertEquals(Optional.of(b.store().getId()), ofFolder(below.getId()));
		assertEquals(Optional.of(b.store().getId()), of(doc.getId()));
	}

	@Test
	public void testTemplatesIsAStore() throws Exception {
		long templates = HealdanSettings.get().templatesStoreId();
		Folder template = fx.storeTemplate(a.store());
		assertEquals(Optional.of(templates), ofFolder(templates));
		assertEquals(Optional.of(templates), ofFolder(template.getId()));

		Document doc = fx.upload(a.folder(), "a.txt", "a", fx.admin());
		moveByJdbc(doc, template.getId());
		assertEquals(Optional.of(templates), of(doc.getId()));
	}

	@Test
	public void testDefaultWorkspaceIsNoStore() throws Exception {
		assertEquals(Optional.empty(), ofFolder(Folder.DEFAULTWORKSPACEID));

		Folder below = fx.folder(a.folder(), "Below");
		reparentByJdbc(below, Folder.DEFAULTWORKSPACEID);
		assertEquals(Optional.empty(), ofFolder(below.getId()));

		Document doc = fx.upload(a.folder(), "a.txt", "a", fx.admin());
		moveByJdbc(doc, Folder.DEFAULTWORKSPACEID);
		assertEquals(Optional.empty(), of(doc.getId()));
		moveByJdbc(doc, below.getId());
		assertEquals(Optional.empty(), of(doc.getId()));
	}

	@Test
	public void testAliasIsItsTarget() throws Exception {
		Document target = fx.upload(b.folder(), "target.txt", "target", fx.admin());
		Document alias = fx.legacyShortcut(target, a.folder());
		assertEquals(target.getId(), alias.getDocRef().longValue());

		// CE's resource of an alias is its target's
		StoreResource resource = StoreResource.builder().document(alias).build();
		assertEquals(target.getId(), resource.getDocId());
		assertEquals(Optional.of(b.store().getId()), of(resource.getDocId()));
		// and so is the alias's own Store, for its files are the target's
		assertEquals(Optional.of(b.store().getId()), of(alias.getId()));
	}

	@Test
	public void testUnknownIsNone() throws Exception {
		assertEquals(Optional.empty(), of(987654L));
		assertEquals(Optional.empty(), ofFolder(987654L));
		assertEquals(Optional.empty(), ofFolder(Folder.ROOTID));
	}

	@Test
	public void testBulkAgrees() throws Exception {
		Folder moved = fx.folder(a.folder(), "Moved");
		Folder below = fx.folder(moved, "Below");
		reparentByJdbc(moved, b.folder().getId());
		Folder outside = fx.folder(a.folder(), "Outside");
		reparentByJdbc(outside, Folder.DEFAULTWORKSPACEID);
		fx.storeTemplate(b.store());
		fx.upload(below, "a.txt", "a", fx.admin());
		fx.legacyShortcut(fx.upload(b.folder(), "b.txt", "b", fx.admin()), a.folder());

		StoreOf.Folders folders = StoreOf.get().folders();
		List<Long> ids = StorageFixtures.jdbc().queryForList("select ld_id from ld_folder", Long.class);
		assertTrue(ids.size() > 10);
		int inStores = 0;
		for (long id : ids) {
			assertEquals("folder " + id, ofFolder(id), folders.of(id));
			if (folders.of(id).isPresent())
				inStores++;
		}
		assertTrue(inStores > 5);
		assertNotEquals(ids.size(), inStores);
		assertEquals(Optional.of(b.store().getId()), folders.of(below.getId()));
		assertEquals(Optional.empty(), folders.of(outside.getId()));
		assertEquals(Optional.empty(), folders.of(987654L));

		// Read once: a change after the read isn't seen
		reparentByJdbc(below, a.folder().getId());
		assertEquals(Optional.of(b.store().getId()), folders.of(below.getId()));
		assertEquals(Optional.of(a.store().getId()), StoreOf.get().folders().of(below.getId()));

		// A document's Store is its folder's (an alias's is its target's, above)
		List<Map<String, Object>> docs = StorageFixtures.jdbc()
				.queryForList("select ld_id, ld_folderid from ld_document where ld_docref is null");
		assertTrue(docs.size() >= 2);
		StoreOf.Folders now = StoreOf.get().folders();
		for (Map<String, Object> doc : docs)
			assertEquals(of(((Number) doc.get("LD_ID")).longValue()),
					now.of(((Number) doc.get("LD_FOLDERID")).longValue()));
	}
}
