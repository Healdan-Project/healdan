// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.store.Store;
import com.logicaldoc.util.plugin.PluginRegistry;
import com.logicaldoc.util.spring.Context;

public class StorageModuleTest extends AbstractStorageTestCase {

	@Test
	public void testPluginLoads() {
		var descriptor = PluginRegistry.getInstance().getPlugin("healdan-storage");
		assertTrue(descriptor != null);
		assertTrue(descriptor.getPrerequisites().stream().anyMatch(p -> "healdan-core".equals(p.getPluginId())));
	}

	@Test
	public void testStoreBeanIsCEs() {
		List<Store> stores = Context.get().getBeansOfType(Store.class);
		assertEquals(1, stores.size());
		assertTrue(stores.get(0) instanceof HealdanStorage);
		assertTrue(Store.get() instanceof HealdanStorage);
	}

	@Test
	public void testOriginalHoldsItsMarker() throws Exception {
		Path marker = Path.of(Context.get().getConfig().getProperty("store.1.dir"), ".healdan-backend");
		assertEquals(StorageSettings.get().installId() + " 1\n", Files.readString(marker));
	}
}
