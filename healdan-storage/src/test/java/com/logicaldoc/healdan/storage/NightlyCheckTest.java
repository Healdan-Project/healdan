// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.plugin.PluginException;

/**
 * The nightly check backs up the listeners (spec section 5, call 10): every row's Store is worked out again from the
 * folders' parent ids.
 */
public class NightlyCheckTest extends AbstractStorageTestCase {

	private StorageFixtures sf;

	private HealdanFixtures fx;

	private Side a;

	private Side b;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			sf = new StorageFixtures();
			fx = sf.fx;
			a = sf.localSide("Alpha", false);
			b = sf.s3Side("Beta", false);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	public void testWrongStoreIsFixedAndLeftMisplaced() throws Exception {
		Document doc = fx.upload(a.folder(), "a.txt", "text", fx.admin());
		Document other = fx.upload(b.folder(), "b.txt", "text", fx.admin());
		assertEquals(0, NightlyCheck.get().run());

		// By hand: Alpha's file given Beta's Store, so it looks in place for Beta... on Alpha's backend
		StorageFixtures.jdbc().update("update hd_storage_file set hd_storeid = ?, hd_waiting = null "
				+ "where hd_docid = ?", b.store().getId(), doc.getId());

		assertEquals(1, NightlyCheck.get().run());
		StoredFile fixed = row(doc.getId(), "1.0");
		assertEquals(a.store().getId(), (long) fixed.storeId());
		assertEquals(a.backendId(), fixed.backendId());
		assertNull("Alpha's backend is Alpha's Store's", fixed.waiting());
		assertEquals(b.store().getId(), (long) row(other.getId(), "1.0").storeId());

		// And the other way: a row of Beta's, given Alpha's, sits on staging, so Beta wants it
		StorageFixtures.jdbc().update("update hd_storage_file set hd_storeid = ?, hd_waiting = null "
				+ "where hd_docid = ?", a.store().getId(), other.getId());
		assertEquals(1, NightlyCheck.get().run());
		StoredFile misplaced = row(other.getId(), "1.0");
		assertEquals(b.store().getId(), (long) misplaced.storeId());
		assertNotNull("left for the mover, toward Beta's backend", misplaced.waiting());
		assertEquals(0L, misplaced.backendId());
		assertEquals(0, NightlyCheck.get().run());
	}

	@Test
	public void testPathIsNotUsed() throws Exception {
		Document doc = fx.upload(a.folder(), "a.txt", "text", fx.admin());
		// The path names Beta, the parent ids say Alpha
		StorageFixtures.jdbc().update("update ld_folder set ld_path = ? where ld_id = ?",
				"/" + b.store().getId() + "/x", a.folder().getId());
		assertEquals(0, NightlyCheck.get().run());
		assertEquals(a.store().getId(), (long) row(doc.getId(), "1.0").storeId());
	}

	@Test
	public void testCorrectRepositoryHasNoFixes() throws Exception {
		fx.upload(a.folder(), "a.txt", "text", fx.admin());
		fx.upload(b.folder(), "b.txt", "text", fx.admin());
		assertEquals(0, NightlyCheck.get().run());
		assertTrue(StoredFiles.get().misplaced(10, List.of()).stream().allMatch(r -> r.backendId() == 0L));
	}

	@Test
	public void testFixIsLoggedWithBothStores() throws Exception {
		Document doc = fx.upload(a.folder(), "a.txt", "text", fx.admin());
		StorageFixtures.jdbc().update("update hd_storage_file set hd_storeid = ? where hd_docid = ?",
				b.store().getId(), doc.getId());
		String line = NightlyCheck.describe(doc.getId(), "1.0", b.store().getId(), a.store().getId());
		assertTrue(line, line.contains(String.valueOf(doc.getId())) && line.contains("1.0")
				&& line.contains(String.valueOf(b.store().getId())) && line.contains(String.valueOf(a.store().getId())));
		assertEquals(1, NightlyCheck.get().run());
	}
}
