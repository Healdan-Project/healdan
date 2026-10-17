package com.logicaldoc.healdan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.util.plugin.PluginRegistry;
import com.logicaldoc.core.SystemInfo;

public class PluginLoadTest extends AbstractHealdanTestCase {

	@Test
	public void testHealdanPluginIsActiveAndDeclaresFeature71() {
		assertTrue(PluginRegistry.getInstance().getPlugin("healdan-core") != null);
		assertTrue(SystemInfo.get(Tenant.DEFAULT_ID).getFeatures().contains("Feature_71"));
		// Test plugins added by other test classes must not leak into this one
		assertFalse(SystemInfo.get(Tenant.DEFAULT_ID).getFeatures().contains("Feature_9999"));
	}
}
